//! Batch 4: real source discovery, detail loading and episode handoff.
use super::*;

#[derive(Clone, PartialEq, Default)]
pub struct Info {
    pub title: String,
    pub poster: String,
    pub year: String,
    pub description: String,
    pub source: String,
    pub id: String,
    pub source_name: String,
    pub episodes: Vec<String>,
    pub episode_titles: Vec<String>,
}

#[derive(Clone, PartialEq, Default)]
pub struct Selection {
    pub info: Info,
    pub index: usize,
    pub url: String,
}

#[derive(Clone)]
pub struct Controller {
    pub info: Signal<Info>,
    pub sources: Signal<Vec<search::SearchItem>>,
    pub loading: Signal<bool>,
    pub discovering: Signal<bool>,
    pub error: Signal<String>,
    pub source_error: Signal<String>,
    pub tab: Signal<&'static str>,
    pub selection: Signal<Selection>,
    /// Site favorite state for the loaded source (`source+id` key).
    pub favorite: Signal<bool>,
    pub favorite_busy: Signal<bool>,
    pub favorite_error: Signal<String>,
    generation: Signal<u64>,
    request: Signal<u64>,
    server: Signal<String>,
    token: Signal<String>,
    username: Signal<String>,
    page: Signal<&'static str>,
    port: Signal<u16>,
}

impl Controller {
    pub fn new(server: Signal<String>, token: Signal<String>, username: Signal<String>,
        page: Signal<&'static str>, port: Signal<u16>) -> Self {
        Self { info: signal(Info::default()), sources: signal(Vec::new()),
            loading: signal(false), discovering: signal(false), error: signal(String::new()),
            source_error: signal(String::new()), tab: signal("episodes"),
            selection: signal(Selection::default()), favorite: signal(false),
            favorite_busy: signal(false), favorite_error: signal(String::new()),
            generation: signal(0), request: signal(0),
            server, token, username, page, port }
    }

    fn accept_token(&self, next: Option<String>) {
        if let Some(next) = next {
            save_session(&self.server.get(), &self.username.get(), &next);
            self.token.set(next.clone());
            if let Ok(port) = proxy::ensure(self.server.get(), next) { self.port.set(port); }
        }
    }

    fn failure(&self, error: ApiError) -> String {
        match error {
            ApiError::AuthExpired => {
                clear_session();
                self.token.set(String::new());
                self.port.set(0);
                self.page.set("login");
                "登录已过期，请重新登录".to_owned()
            }
            ApiError::Network(message) => message,
        }
    }

    pub fn open(&self, movie: Movie, seed: Option<search::SearchItem>, extra: Vec<search::SearchItem>) {
        self.generation.set(self.generation.get() + 1);
        // Invalidate a detail response belonging to the previously open title.
        self.request.set(self.request.get() + 1);
        self.info.set(Info { title: movie.title.clone(), poster: movie.poster,
            year: movie.year.clone(), ..Default::default() });
        self.sources.set(Vec::new());
        self.error.set(String::new());
        self.source_error.set(String::new());
        self.favorite.set(false);
        self.favorite_busy.set(false);
        self.favorite_error.set(String::new());
        self.loading.set(false);
        self.discovering.set(false);
        self.tab.set("episodes");
        self.page.set("detail");
        let mut candidates = search::cache_get(&movie.title).map(|snapshot| {
            snapshot.groups.into_iter().flat_map(|group| group.items).filter(|item| {
                item.title.trim() == movie.title.trim()
                    && (movie.year.is_empty() || item.year.is_empty() || item.year == movie.year)
            }).collect::<Vec<_>>()
        }).unwrap_or_default();
        candidates.extend(extra);
        if let Some(seed) = seed { candidates.insert(0, seed); }
        dedup_sources(&mut candidates);
        if let Some(first) = candidates.first().cloned() {
            self.sources.set(candidates);
            self.discovering.set(false);
            self.load(first);
        } else {
            self.discover();
        }
    }

    pub fn discover(&self) {
        if self.discovering.get() { return; }
        self.discovering.set(true);
        self.source_error.set(String::new());
        let this = self.clone();
        let generation = self.generation.get();
        let title = self.info.get().title;
        spawn(async move {
            let path = format!("/api/search?q={}", urlencoding::encode(&title));
            let result = api(&this.server.get(), &this.token.get(), &path, "GET", None, true).await;
            if this.generation.get() != generation { return; }
            this.discovering.set(false);
            match result {
                Ok((status, body, next)) => {
                    this.accept_token(next);
                    if status != 200 {
                        this.source_error.set(format!("查找片源失败 HTTP {status}，请重试"));
                        return;
                    }
                    let parsed = serde_json::from_str::<serde_json::Value>(&body);
                    let mut items = match parsed.ok().and_then(|value| value["results"].as_array().cloned()) {
                        Some(items) => items.iter().filter_map(search::parse_item).collect::<Vec<_>>(),
                        None => {
                            this.source_error.set("片源响应格式错误，请重试".to_owned());
                            return;
                        }
                    };
                    let year = this.info.get().year;
                    items.retain(|item| item.title.trim() == title.trim()
                        && (year.is_empty() || item.year.is_empty() || item.year == year));
                    dedup_sources(&mut items);
                    let first = items.first().cloned();
                    this.sources.set(items);
                    if let Some(first) = first { this.load(first); }
                    else { this.source_error.set("未找到同名真实片源，可返回搜索其他片名".to_owned()); }
                }
                Err(error) => { let message = this.failure(error); this.source_error.set(message); }
            }
        });
    }

    pub fn load(&self, item: search::SearchItem) {
        self.request.set(self.request.get() + 1);
        let request = self.request.get();
        self.loading.set(true);
        self.error.set(String::new());
        self.favorite.set(false);
        self.favorite_error.set(String::new());
        let mut info = self.info.get();
        info.source = item.source.clone();
        info.id = item.id.clone();
        info.source_name = c_source_label(&item);
        info.episodes.clear();
        info.episode_titles.clear();
        info.description.clear();
        self.info.set(info);
        let this = self.clone();
        spawn(async move {
            let path = format!("/api/detail?source={}&id={}",
                urlencoding::encode(&item.source), urlencoding::encode(&item.id));
            let result = api(&this.server.get(), &this.token.get(), &path, "GET", None, true).await;
            if this.request.get() != request { return; }
            this.loading.set(false);
            match result {
                Ok((status, body, next)) => {
                    this.accept_token(next);
                    let value = serde_json::from_str::<serde_json::Value>(&body).unwrap_or_default();
                    if status != 200 {
                        let message = value["error"].as_str().unwrap_or("片源加载失败");
                        this.error.set(format!("{message}（HTTP {status}），请重试或换源"));
                        return;
                    }
                    let mut info = this.info.get();
                    if let Some(title) = value["title"].as_str() { info.title = title.to_owned(); }
                    if let Some(poster) = value["poster"].as_str().filter(|s| !s.is_empty()) { info.poster = poster.to_owned(); }
                    if let Some(year) = value["year"].as_str() { info.year = year.to_owned(); }
                    info.description = value["desc"].as_str().unwrap_or_default().to_owned();
                    info.episodes = strings(&value["episodes"]);
                    info.episode_titles = strings(&value["episodes_titles"]);
                    if info.episodes.is_empty() { this.error.set("该源暂无集数，请重试或换源".to_owned()); }
                    this.info.set(info);
                    this.check_favorite();
                }
                Err(error) => { let message = this.failure(error); this.error.set(message); }
            }
        });
    }

    pub fn retry(&self) {
        let info = self.info.get();
        if let Some(item) = self.sources.get().into_iter()
            .find(|item| item.source == info.source && item.id == info.id) { self.load(item); }
        else { self.discover(); }
    }

    fn favorite_key(info: &Info) -> String {
        if info.source.is_empty() || info.id.is_empty() { String::new() }
        else { format!("{}+{}", info.source, info.id) }
    }

    /// `GET /api/favorites?key=source+id` shows whether this source is saved.
    pub fn check_favorite(&self) {
        let key = Self::favorite_key(&self.info.get());
        if key.is_empty() { return; }
        let this = self.clone();
        spawn(async move {
            let path = format!("/api/favorites?key={}", urlencoding::encode(&key));
            match api(&this.server.get(), &this.token.get(), &path, "GET", None, true).await {
                Ok((status, body, next)) => {
                    this.accept_token(next);
                    if status != 200 { return; }
                    let saved = serde_json::from_str::<serde_json::Value>(&body)
                        .ok()
                        .and_then(|value| value["title"].as_str().map(|title| !title.is_empty()))
                        .unwrap_or(false);
                    this.favorite.set(saved);
                }
                Err(error) => {
                    if let ApiError::Network(reason) = error {
                        eprintln!("favorite check failed: {reason}");
                    }
                }
            }
        });
    }

    /// `POST /api/favorites` with `{key, favorite}` adds; `DELETE ?key=` removes.
    pub fn toggle_favorite(&self) {
        if self.favorite_busy.get() { return; }
        let info = self.info.get();
        let key = Self::favorite_key(&info);
        if key.is_empty() { return; }
        let saved = self.favorite.get();
        self.favorite_busy.set(true);
        self.favorite_error.set(String::new());
        let this = self.clone();
        spawn(async move {
            let (path, method, body) = if saved {
                (format!("/api/favorites?key={}", urlencoding::encode(&key)), "DELETE", None)
            } else {
                let favorite = serde_json::json!({
                    "title": info.title,
                    "source_name": info.source_name,
                    "year": info.year,
                    "cover": info.poster,
                    "total_episodes": info.episodes.len(),
                    // Milliseconds, as the web client writes it.
                    "save_time": std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|value| value.as_millis() as f64).unwrap_or(0.0),
                    "search_title": info.title,
                });
                let body = serde_json::json!({ "key": key, "favorite": favorite }).to_string();
                ("/api/favorites".to_owned(), "POST", Some(body))
            };
            let result = api(&this.server.get(), &this.token.get(), &path, method, body, true).await;
            this.favorite_busy.set(false);
            match result {
                Ok((200..=299, _, next)) => {
                    this.accept_token(next);
                    this.favorite.set(!saved);
                }
                Ok((status, text, _)) => {
                    eprintln!("favorite toggle HTTP {status}: {text}");
                    this.favorite_error.set(if saved {
                        format!("取消收藏失败（HTTP {status}）")
                    } else {
                        format!("收藏失败（HTTP {status}）")
                    });
                }
                Err(error) => {
                    let message = this.failure(error);
                    this.favorite_error.set(message);
                }
            }
        });
    }

    pub fn play(&self, index: usize) {
        if self.loading.get() { return; }
        let info = self.info.get();
        let Some(url) = info.episodes.get(index).cloned() else { return; };
        let url = if url.starts_with('/') { format!("{}{}", self.server.get().trim_end_matches('/'), url) } else { url };
        if !(url.starts_with("https://") || url.starts_with("http://")) {
            self.error.set("该集不是可交给现有播放器的 HTTP(S) 地址，请换源".to_owned());
            return;
        }
        self.selection.set(Selection { info, index, url });
        self.page.set("play");
    }
}

fn dedup_sources(items: &mut Vec<search::SearchItem>) {
    let mut seen = std::collections::HashSet::new();
    items.retain(|item| !item.source.is_empty() && !item.id.is_empty()
        && seen.insert((item.source.clone(), item.id.clone())));
}

fn strings(value: &serde_json::Value) -> Vec<String> {
    value.as_array().map(|items| items.iter().map(|item| item.as_str().unwrap_or_default().to_owned()).collect()).unwrap_or_default()
}

pub fn view_detail(controller: Controller, back: impl Fn() + Clone + 'static,
    port: u16, site: String, scale: f32) -> impl IntoElement {
    let retry = controller.clone();
    let discover = controller.clone();
    let source_tab = controller.clone();
    let episode_tab = controller.clone();
    let source_items = controller.sources.clone();
    let episode_info = controller.info.clone();
    let select = controller.clone();
    let play = controller.clone();
    scroll().flex_1().child(super::view().p_4().gap_3()
        .child(super::view().on_press(move || back()).child(text("返回")))
        .child(super::view().flex_row().gap_3()
            .child(image({ let info = controller.info.clone(); move || image_url(port, &site, &info.get().poster) }).w(px(96.0 * scale))
                .aspect_ratio(2.0 / 3.0).rounded(px(8.0 * scale)).bg(move || theme().muted))
            .child(super::view().flex_1().gap_2()
                .child(text({ let info = controller.info.clone(); move || info.get().title }).text_size(px(20.0 * scale)))
                .child(text({ let info = controller.info.clone(); move || info.get().year }).text_color(move || theme().muted_foreground))
                .child(text({ let info = controller.info.clone(); move || info.get().source_name }).text_size(px(12.0 * scale)))
                .child(when(
                    { let c = controller.clone(); move || !c.info.get().source.is_empty() },
                    {
                        let c = controller.clone();
                        move || {
                            let toggle = c.clone();
                            view().gap(px(4.0 * scale))
                                .child(view().px(px(10.0 * scale)).py(px(6.0 * scale))
                                    .rounded(px(8.0 * scale)).bg(move || theme().muted)
                                    .on_press(move || toggle.toggle_favorite())
                                    .child(text({ let c = c.clone(); move || {
                                        if c.favorite_busy.get() { "处理中…".to_owned() }
                                        else if c.favorite.get() { "已收藏".to_owned() }
                                        else { "收藏".to_owned() }
                                    }}).text_size(px(12.0 * scale))))
                                .child(text({ let c = c.clone(); move || c.favorite_error.get() })
                                    .text_size(px(11.0 * scale))
                                    .text_color(rgb(0xcc2222)))
                        }
                    },
                ))))
        .child(text({ let info = controller.info.clone(); move || info.get().description }).text_size(px(14.0 * scale)).number_of_lines(8))
        .child(super::view().flex_row().gap_3()
            .child(super::view().on_press(move || episode_tab.tab.set("episodes")).child(text("选集")))
            .child(super::view().on_press(move || source_tab.tab.set("sources")).child(text("换源"))))
        .child(when({ let c = controller.clone(); move || c.loading.get() || c.discovering.get() },
            || text("正在加载真实片源…").text_color(move || theme().muted_foreground)))
        .child(text({ let c = controller.clone(); move || c.error.get() })
            .text_color(move || theme().muted_foreground))
        .child(when({ let c = controller.clone(); move || !c.error.get().is_empty() },
            move || { let c = retry.clone(); super::view().on_press(move || c.retry()).child(text("重试当前源")) }))
        .child(text({ let c = controller.clone(); move || c.source_error.get() }))
        .child(when({ let c = controller.clone(); move || !c.source_error.get().is_empty() },
            move || { let c = discover.clone(); super::view().on_press(move || c.discover()).child(text("重新查找片源")) }))
        .child(when({ let c = controller.clone(); move || c.tab.get() == "sources" }, move || {
            let c = select.clone();
            let discover = select.clone();
            super::view().gap_2()
                .child(super::view().p_3().on_press(move || discover.discover()).child(text("重新查找同名片源")))
                .child(each(move || source_items.get(),
                |item: &search::SearchItem| format!("{}:{}", item.source, item.id),
                move |item: ReadSignal<search::SearchItem>, _| {
                    let c = c.clone(); let click_item = item.clone();
                    let selected_info = c.info.clone();
                    super::view().p_3().rounded(px(8.0)).bg(move || theme().muted)
                        .on_press(move || c.load(click_item.get()))
                        .child(text(move || {
                            let item = item.get();
                            let info = selected_info.get();
                            let prefix = if info.source == item.source && info.id == item.id { "当前 · " } else { "" };
                            format!("{prefix}{}", c_source_label(&item))
                        }))
                }))
        }))
        .child(when({ let c = controller.clone(); move || c.tab.get() == "episodes" }, move || {
            let c = play.clone();
            super::view().gap_2().child(each(move || {
                let info = episode_info.get();
                (0..info.episodes.len()).map(|index| (index,
                    info.episode_titles.get(index).filter(|title| !title.is_empty()).cloned()
                        .unwrap_or_else(|| format!("第 {} 集", index + 1)))).collect::<Vec<_>>()
            }, |item: &(usize, String)| item.0, move |item: ReadSignal<(usize, String)>, _| {
                let c = c.clone(); let click = item.clone();
                super::view().p_3().rounded(px(8.0)).bg(move || theme().muted)
                    .on_press(move || c.play(click.get().0)).child(text(move || item.get().1))
            }))
        })))
}

fn c_source_label(item: &search::SearchItem) -> String {
    // Avoid unsupported emoji in provider display names on Android.
    let name: String = item.source_name.chars().filter(|c| {
        !matches!(*c as u32, 0x1F000..=0x1FAFF | 0x2600..=0x27BF | 0xFE0F | 0x200D)
    }).collect();
    if name.trim().is_empty() { item.source.clone() } else { name.trim().to_owned() }
}
