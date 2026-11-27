//! Category state lives above the page so details can unmount it without
//! losing filters, pages or the last native scroll offset. No TTL cache.
use super::*;
use nsh_core::element::{ScrollEvent, Scrollable};
use nsh_core::geometry::point;

const PAGE_SIZE: usize = 25;
type OptionItem = (&'static str, &'static str);

#[derive(Clone, PartialEq)]
pub struct Filters {
    pub kind: String,
    pub category: String,
    pub type_value: String,
    pub weekday: String,
}

impl Default for Filters {
    fn default() -> Self {
        Self { kind: "movie".into(), category: "热门".into(), type_value: "全部".into(), weekday: "Mon".into() }
    }
}

#[derive(Clone)]
pub struct Controller {
    pub filters: Signal<Filters>,
    pub items: Signal<Vec<Movie>>,
    pub loading: Signal<bool>,
    pub appending: Signal<bool>,
    pub error: Signal<String>,
    pub append_error: Signal<String>,
    pub more: Signal<bool>,
    pub offset: Signal<f32>,
    pub revision: Signal<u64>,
    next_start: Signal<usize>,
    initialized: Signal<bool>,
    server: Signal<String>,
    token: Signal<String>,
    page: Signal<&'static str>,
    site: Signal<String>,
}

impl Controller {
    pub fn new(server: Signal<String>, token: Signal<String>, page: Signal<&'static str>) -> Self {
        Self { filters: signal(Filters::default()), items: signal(Vec::new()),
            loading: signal(false), appending: signal(false), error: signal(String::new()),
            append_error: signal(String::new()), more: signal(false), offset: signal(0.0),
            revision: signal(0), next_start: signal(0), initialized: signal(false),
            site: signal(String::new()), server, token, page }
    }

    pub fn open(&self, route: String) {
        let kind = match route.as_str() {
            "movie" | "电影" | "热门电影" => "movie",
            "tv" | "剧集" | "热门剧集" => "tv",
            "show" | "综艺" | "热门综艺" => "show",
            "anime" | "动漫" | "番剧" | "新番放送" => "anime",
            _ => {
                // Other home routes are outside batch 3, not fake movie lists.
                self.revision.set(self.revision.get() + 1);
                self.initialized.set(false);
                self.items.set(Vec::new());
                self.loading.set(false); self.appending.set(false);
                self.append_error.set(String::new());
                self.more.set(false);
                self.error.set("此列表尚未接入，请选择电影、剧集、动漫或综艺".into());
                self.page.set("browse");
                return;
            }
        };
        self.page.set("browse");
        if self.initialized.get() && self.filters.get().kind == kind && self.site.get() == self.server.get() { return; }
        let (category, type_value) = match kind {
            "movie" => ("热门", "全部"), "tv" => ("最近热门", "tv"),
            "show" => ("最近热门", "show"), _ => ("每日放送", "all"),
        };
        self.filters.set(Filters { kind: kind.into(), category: category.into(),
            type_value: type_value.into(), weekday: today() });
        self.site.set(self.server.get());
        self.initialized.set(true);
        self.reload();
    }

    pub fn choose(&self, category: Option<String>, type_value: Option<String>, weekday: Option<String>) {
        let mut filters = self.filters.get();
        if let Some(category) = category {
            filters.category = category;
            filters.type_value = if filters.category == "全部" || filters.kind == "anime" { "all" }
                else { match filters.kind.as_str() { "tv" => "tv", "show" => "show", _ => "全部" } }.into();
        }
        if let Some(value) = type_value { filters.type_value = value; }
        if let Some(value) = weekday { filters.weekday = value; }
        if filters == self.filters.get() { return; }
        self.filters.set(filters);
        self.reload();
    }

    pub fn reload(&self) {
        self.revision.set(self.revision.get() + 1);
        self.offset.set(0.0);
        self.items.set(Vec::new());
        self.next_start.set(0);
        self.error.set(String::new()); self.append_error.set(String::new());
        self.loading.set(false); self.appending.set(false); self.more.set(false);
        self.load(false);
    }

    pub fn load(&self, append: bool) {
        if self.loading.get() || self.appending.get() || (append && !self.more.get()) { return; }
        if append { self.appending.set(true); self.append_error.set(String::new()); }
        else { self.loading.set(true); self.error.set(String::new()); }
        let this = self.clone();
        let revision = self.revision.get();
        let filters = self.filters.get();
        let start = if append { self.next_start.get() } else { 0 };
        let server = self.server.get(); let token = self.token.get();
        bg(async move {
            let path = request_path(&filters, start);
            // The site's middleware requires the session for every `/api` path,
            // the catalog included: without the token these answer 401.
            let response = api(&server, &token, &path, "GET", None, true).await;
            if this.revision.get() != revision || this.server.get() != server { return; }
            this.loading.set(false); this.appending.set(false);
            let result = match response {
                Ok((200, body, _)) => decode(&filters, &body),
                Ok((status, _, _)) => Err(format!("加载失败 HTTP {status}")),
                Err(ApiError::Network(message)) => Err(message),
                Err(ApiError::AuthExpired) => Err("分类暂不可用".into()),
            };
            match result {
                Ok(page) => {
                    let received = page.len();
                    let mut items = if append { this.items.get() } else { Vec::new() };
                    let old_len = items.len();
                    for item in page {
                        if !items.iter().any(|old| old.id == item.id) { items.push(item); }
                    }
                    this.more.set(!(filters.kind == "anime" && filters.category == "每日放送")
                        && received == PAGE_SIZE && items.len() > old_len);
                    // Advance by the server's page, not the deduplicated count.
                    this.next_start.set(start + PAGE_SIZE);
                    this.items.set(items);
                }
                Err(message) => {
                    if append { this.append_error.set(message); }
                    else { this.error.set(message); }
                }
            }
        });
    }
}

fn today() -> String {
    // NSH's calendar uses Mon..Sun. UTC day is only an initial selection;
    // the explicit weekday pills let the user choose their local day.
    let days = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default().as_secs() / 86400;
    ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"][(days % 7) as usize].into()
}

fn request_path(filters: &Filters, start: usize) -> String {
    if filters.kind == "anime" && filters.category == "每日放送" { return "/api/bangumi/calendar".into(); }
    let kind = if filters.kind == "movie" || (filters.kind == "anime" && filters.category == "剧场版") { "movie" } else { "tv" };
    let params = if filters.category == "全部" || filters.kind == "anime" {
        let format = match filters.kind.as_str() { "tv" => "电视剧", "show" => "综艺",
            "anime" if filters.category == "番剧" => "电视剧", _ => "" };
        let mut params = vec![("kind", kind), ("category", if filters.kind == "anime" { "动画" } else if filters.category == "全部" { &filters.type_value } else { "" }),
            ("format", format), ("region", "all"), ("year", "all"), ("platform", "all"), ("sort", "T"), ("label", "all")];
        if filters.kind == "tv" { params[1].1 = if filters.type_value == "all" { "" } else { &filters.type_value }; }
        params
    } else {
        // Match douban/page.tsx getRequestParams exactly.
        vec![("kind", kind), ("category", if filters.kind == "movie" { &filters.category } else { &filters.kind }),
            ("type", &filters.type_value)]
    };
    let endpoint = if filters.category == "全部" || filters.kind == "anime" { "recommends" } else { "categories" };
    let query = params.into_iter().map(|(key, value)| format!("{key}={}", urlencoding::encode(value))).collect::<Vec<_>>().join("&");
    format!("/api/douban/{endpoint}?{query}&start={start}&limit={PAGE_SIZE}")
}

fn decode(filters: &Filters, body: &str) -> Result<Vec<Movie>, String> {
    let value: serde_json::Value = serde_json::from_str(body).map_err(|_| "分类响应不是有效 JSON".to_owned())?;
    if filters.kind == "anime" && filters.category == "每日放送" {
        let days = value.as_array().ok_or("放送日历响应格式错误")?;
        let selected = days.iter().find(|day| day["weekday"]["en"].as_str() == Some(filters.weekday.as_str()));
        return Ok(selected.map(|day| parse_bangumi(&serde_json::json!([day]).to_string())).unwrap_or_default());
    }
    if value["code"].as_u64() != Some(200) || !value["list"].is_array() {
        return Err(value["message"].as_str().unwrap_or("分类响应缺少 list").to_owned());
    }
    Ok(parse_movies(body))
}

fn primary(kind: &str) -> Vec<OptionItem> {
    match kind {
        "movie" => vec![("全部", "全部"), ("热门电影", "热门"), ("最新电影", "最新"), ("豆瓣高分", "豆瓣高分"), ("冷门佳片", "冷门佳片")],
        "anime" => vec![("每日放送", "每日放送"), ("番剧", "番剧"), ("剧场版", "剧场版")],
        _ => vec![("全部", "全部"), ("最近热门", "最近热门")],
    }
}

fn secondary(filters: &Filters) -> Vec<OptionItem> {
    if filters.kind == "anime" { return Vec::new(); }
    if filters.category == "全部" {
        let mut options = vec![("全部", "all")];
        options.extend(match filters.kind.as_str() {
            "show" => vec![("真人秀", "reality"), ("脱口秀", "talkshow"), ("音乐", "music"), ("歌舞", "musical")],
            "tv" => vec![("喜剧", "comedy"), ("爱情", "romance"), ("悬疑", "suspense"), ("武侠", "wuxia"), ("古装", "costume"), ("家庭", "family"), ("犯罪", "crime"), ("科幻", "sci-fi"), ("恐怖", "horror"), ("历史", "history"), ("战争", "war"), ("动作", "action"), ("冒险", "adventure"), ("传记", "biography"), ("剧情", "drama"), ("奇幻", "fantasy"), ("惊悚", "thriller"), ("灾难", "disaster"), ("歌舞", "musical"), ("音乐", "music")],
            _ => vec![("喜剧", "comedy"), ("爱情", "romance"), ("动作", "action"), ("科幻", "sci-fi"), ("悬疑", "suspense"), ("犯罪", "crime"), ("惊悚", "thriller"), ("冒险", "adventure"), ("音乐", "music"), ("历史", "history"), ("奇幻", "fantasy"), ("恐怖", "horror"), ("战争", "war"), ("传记", "biography"), ("歌舞", "musical"), ("武侠", "wuxia"), ("情色", "erotic"), ("灾难", "disaster"), ("西部", "western"), ("纪录片", "documentary"), ("短片", "short")],
        });
        return options;
    }
    match filters.kind.as_str() {
        "movie" => vec![("全部", "全部"), ("华语", "华语"), ("欧美", "欧美"), ("韩国", "韩国"), ("日本", "日本")],
        "tv" => vec![("全部", "tv"), ("国产", "tv_domestic"), ("欧美", "tv_american"), ("日本", "tv_japanese"), ("韩国", "tv_korean"), ("动漫", "tv_animation"), ("纪录片", "tv_documentary")],
        _ => vec![("全部", "show"), ("国内", "show_domestic"), ("国外", "show_foreign")],
    }
}

fn pills(options: Vec<OptionItem>, selected: String, scale: f32,
    choose: impl Fn(String) + Clone + 'static) -> impl IntoElement {
    scroll().horizontal().h(px(44.0 * scale)).child(view().flex_row().gap(px(8.0 * scale))
        .px(px(16.0 * scale)).children(options.into_iter().map(move |(label, value)| {
            let choose = choose.clone(); let active = selected == value;
            view().px(px(12.0 * scale)).py(px(8.0 * scale)).rounded(px(16.0 * scale))
                .bg(move || if active { BRAND } else { theme().muted })
                .on_press(move || choose(value.into()))
                .child(text(label).text_size(px(14.0 * scale))
                    .text_color(move || if active { rgb(0xffffff).into() } else { theme().foreground }))
        })))
}

pub fn page(controller: Controller, open: impl Fn(Movie) + Clone + 'static,
    back: impl Fn() + Clone + 'static, port: u16, site: String, scale: f32) -> impl IntoElement {
    let c = controller.clone();
    let refresh = controller.clone();
    view().flex_1().gap_2()
        .child(view().flex_row().items_center().justify_between().px_4()
            .child(view().on_press(move || back()).child(text("返回")))
            .child(text({ let filters = c.filters.clone(); move || match filters.get().kind.as_str() {
                "tv" => "剧集", "show" => "综艺", "anime" => "动漫", _ => "电影" } }).text_size(px(18.0 * scale)))
            .child(view().on_press(move || refresh.reload()).child(text("刷新"))))
        // Only this subtree remounts on filter reset. Native content_offset is
        // initial-only; a revision key resets it without remounting on append.
        .child(each({ let c = c.clone(); move || vec![c.revision.get()] }, |revision: &u64| *revision, move |_, _| {
            let filters = c.filters.get();
            let category = c.clone(); let type_value = c.clone(); let weekday = c.clone();
            let scrolled = c.clone(); let rows = c.items.clone(); let row_site = site.clone();
            let open = open.clone(); let footer = c.clone();
            view().flex_1().gap_2()
                .child(pills(primary(&filters.kind), filters.category.clone(), scale, move |value| category.choose(Some(value), None, None)))
                .child(pills(secondary(&filters), filters.type_value.clone(), scale, move |value| type_value.choose(None, Some(value), None)))
                .child(when(move || filters.kind == "anime" && filters.category == "每日放送", move || {
                    let c = weekday.clone();
                    pills(vec![("周一", "Mon"), ("周二", "Tue"), ("周三", "Wed"), ("周四", "Thu"), ("周五", "Fri"), ("周六", "Sat"), ("周日", "Sun")], c.filters.get().weekday, scale,
                        move |value| c.choose(None, None, Some(value)))
                }))
                .child(scroll().flex_1().content_offset(point(px(0.0), px(c.offset.get())))
                    .event_throttle(100.0)
                    .on_scroll(move |event: &ScrollEvent| scrolled.offset.set(event.offset.y.0.max(0.0)))
                    .child(view().gap(px(12.0 * scale)).px(px(16.0 * scale)).pb_8()
                        .child(each(move || rows.get().chunks(3).map(|row| row.to_vec()).collect::<Vec<_>>(),
                            |row: &Vec<Movie>| row.first().map(|item| item.id.clone()).unwrap_or_default(), {
                                let open = open.clone();
                                move |row: ReadSignal<Vec<Movie>>, _| view().flex_row().gap(px(8.0 * scale)).child(each(
                                    move || row.get(), |item: &Movie| item.id.clone(), {
                                        let open = open.clone(); let site = row_site.clone();
                                        move |item: ReadSignal<Movie>, _| card(item.get(), port, scale, site.clone(), open.clone())
                                    }))
                            }))
                        .child(status(footer, scale))))
        }))
}

fn status(c: Controller, scale: f32) -> impl IntoElement {
    let retry = c.clone(); let more = c.clone();
    view().items_center().gap_2().py(px(16.0 * scale))
        .child(text({ let c = c.clone(); move || {
            if c.loading.get() { "加载中…".into() }
            else if c.appending.get() { "正在加载更多…".into() }
            else if !c.error.get().is_empty() { c.error.get() }
            else if !c.append_error.get().is_empty() { format!("追加加载失败：{}", c.append_error.get()) }
            else if c.items.get().is_empty() { "暂无内容，换个分类试试".into() }
            else if !c.more.get() { "已全部加载".into() } else { String::new() }
        }}).text_color(move || theme().muted_foreground).text_size(px(14.0 * scale)))
        .child(when({ let c = c.clone(); move || !c.error.get().is_empty() || !c.append_error.get().is_empty() }, move || {
            let c = retry.clone(); view().p_3().on_press(move || c.load(!c.append_error.get().is_empty())).child(text("重试"))
        }))
        .child(when(move || c.more.get() && !c.loading.get() && !c.appending.get() && c.append_error.get().is_empty(), move || {
            let c = more.clone(); view().p_3().rounded(px(8.0)).bg(BRAND)
                .on_press(move || c.load(true)).child(text("加载更多").text_color(rgb(0xffffff)))
        }))
}
