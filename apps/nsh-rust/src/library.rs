//! Batch 6: the site's own favorites and play records, plus the home
//! continue-watching row they feed.
use super::*;

#[derive(Clone, PartialEq, Default)]
pub struct FavoriteItem {
    pub key: String,
    pub title: String,
    pub cover: String,
    pub year: String,
    pub source_name: String,
    pub total_episodes: i64,
    pub save_time: f64,
    /// Episode from the matching play record, when the site has one.
    pub current_episode: Option<i64>,
}

impl FavoriteItem {
    fn movie(&self) -> Movie {
        Movie {
            id: self.key.clone(),
            title: self.title.clone(),
            poster: self.cover.clone(),
            year: self.year.clone(),
            ..Movie::default()
        }
    }
}

/// A continue-watching row built from play records; shared with the history
/// page so clearing there removes the home row too.
pub fn history_row(records: &[History]) -> Option<Row> {
    let mut seen: Vec<String> = Vec::new();
    let items: Vec<Movie> = records
        .iter()
        .filter(|item| {
            if seen.iter().any(|title| title == &item.title) {
                false
            } else {
                seen.push(item.title.clone());
                true
            }
        })
        .map(|item| Movie {
            id: item.key.clone(),
            title: item.title.clone(),
            poster: item.cover.clone(),
            rate: String::new(),
            year: item.year.clone(),
            progress: if item.total_time > 0.0 {
                (item.play_time / item.total_time).clamp(0.0, 1.0)
            } else {
                0.0
            },
            source_name: item.source_name.clone(),
            elapsed: human_time(item.play_time),
        })
        .collect();
    if items.is_empty() {
        None
    } else {
        Some(Row {
            id: "history".to_owned(),
            title: "继续观看".to_owned(),
            kind: "history".to_owned(),
            items,
        })
    }
}

fn record_movie(item: &History) -> Movie {
    Movie {
        id: item.key.clone(),
        title: item.title.clone(),
        poster: item.cover.clone(),
        year: item.year.clone(),
        ..Movie::default()
    }
}

#[derive(Clone)]
pub struct Controller {
    pub favorites: Signal<Vec<FavoriteItem>>,
    pub records: Signal<Vec<History>>,
    pub favorites_loading: Signal<bool>,
    pub records_loading: Signal<bool>,
    pub favorites_error: Signal<String>,
    pub records_error: Signal<String>,
    pub clearing: Signal<bool>,
    /// Shared confirmation dialog the app renders: "", "favorites",
    /// "records" or "logout".
    pub confirm: Signal<&'static str>,
    /// Page the favorites/history screens were opened from.
    pub origin: Signal<&'static str>,
    server: Signal<String>,
    token: Signal<String>,
    username: Signal<String>,
    page: Signal<&'static str>,
    port: Signal<u16>,
    /// The home signals, kept in step so a clear/removal is visible there.
    home_history: Signal<Vec<History>>,
    home_rows: Signal<Vec<Row>>,
}

impl Controller {
    pub fn new(
        server: Signal<String>,
        token: Signal<String>,
        username: Signal<String>,
        page: Signal<&'static str>,
        port: Signal<u16>,
        home_history: Signal<Vec<History>>,
        home_rows: Signal<Vec<Row>>,
        confirm: Signal<&'static str>,
    ) -> Self {
        Self {
            favorites: signal(Vec::new()),
            records: signal(Vec::new()),
            favorites_loading: signal(false),
            records_loading: signal(false),
            favorites_error: signal(String::new()),
            records_error: signal(String::new()),
            clearing: signal(false),
            confirm,
            origin: signal("home"),
            server,
            token,
            username,
            page,
            port,
            home_history,
            home_rows,
        }
    }

    fn accept_token(&self, next: Option<String>) {
        if let Some(next) = next {
            save_session(&self.server.get(), &self.username.get(), &next);
            self.token.set(next.clone());
            if let Ok(port) = proxy::ensure(self.server.get(), next) {
                self.port.set(port);
            }
        }
    }

    fn fail(&self, error: ApiError) -> String {
        match error {
            ApiError::AuthExpired => {
                clear_session();
                self.token.set(String::new());
                self.port.set(0);
                self.page.set("login");
                "登录已过期，请重新登录".to_owned()
            }
            ApiError::Network(message) => format!("网络错误：{message}"),
        }
    }

    pub fn open_me(&self) {
        self.page.set("me");
        self.confirm.set("");
        self.load_favorites(None);
        self.load_records(None);
    }

    /// Opens the shared confirmation dialog for a clear action.
    pub fn request_clear(&self, kind: &'static str) {
        self.confirm.set(kind);
    }

    /// Drops per-account data, for logout.
    pub fn reset(&self) {
        self.favorites.set(Vec::new());
        self.records.set(Vec::new());
        self.favorites_error.set(String::new());
        self.records_error.set(String::new());
        self.confirm.set("");
        self.origin.set("home");
        self.home_history.set(Vec::new());
        self.home_rows
            .set(self.home_rows.get().into_iter().filter(|row| row.kind != "history").collect());
    }

    fn remember_origin(&self) {
        let from = self.page.get();
        self.origin.set(if from == "me" { "me" } else { "home" });
    }

    pub fn open_favorites(&self) {
        self.remember_origin();
        self.page.set("favorites");
        self.confirm.set("");
        self.load_favorites(None);
    }

    pub fn open_history(&self) {
        self.remember_origin();
        self.page.set("history");
        self.confirm.set("");
        self.load_records(None);
    }

    /// `GET /api/favorites` (all) plus `GET /api/playrecords`, whose episode
    /// numbers the favorites list shows like the web panel does.
    pub fn load_favorites(&self, notice: Option<String>) {
        if self.favorites_loading.get() {
            return;
        }
        self.favorites_loading.set(true);
        self.favorites_error.set(String::new());
        let this = self.clone();
        spawn(async move {
            let server = this.server.get();
            let token = this.token.get();
            let favorites = api(&server, &token, "/api/favorites", "GET", None, true).await;
            let records = api(&server, &token, "/api/playrecords", "GET", None, true).await;
            this.favorites_loading.set(false);
            match favorites {
                Ok((status, body, next)) => {
                    this.accept_token(next);
                    if status == 401 {
                        this.favorites_error.set("登录已过期，请重新登录".to_owned());
                        return;
                    }
                    if status != 200 {
                        this.favorites_error
                            .set(format!("加载收藏失败（HTTP {status}），请重试"));
                        return;
                    }
                    let record_body = match records {
                        Ok((_, body, next)) => {
                            this.accept_token(next);
                            body
                        }
                        Err(_) => String::new(),
                    };
                    this.favorites.set(parse_favorites(&body, &record_body));
                    this.favorites_error.set(notice.unwrap_or_default());
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.favorites_error.set(message);
                }
            }
        });
    }

    /// `GET /api/playrecords`, newest first.
    pub fn load_records(&self, notice: Option<String>) {
        if self.records_loading.get() {
            return;
        }
        self.records_loading.set(true);
        self.records_error.set(String::new());
        let this = self.clone();
        spawn(async move {
            let result = api(
                &this.server.get(),
                &this.token.get(),
                "/api/playrecords",
                "GET",
                None,
                true,
            )
            .await;
            this.records_loading.set(false);
            match result {
                Ok((status, body, next)) => {
                    this.accept_token(next);
                    if status == 401 {
                        this.records_error.set("登录已过期，请重新登录".to_owned());
                        return;
                    }
                    if status != 200 {
                        this.records_error
                            .set(format!("加载播放记录失败（HTTP {status}），请重试"));
                        return;
                    }
                    this.records.set(parse_history(&body));
                    this.sync_home();
                    this.records_error.set(notice.unwrap_or_default());
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.records_error.set(message);
                }
            }
        });
    }

    fn sync_home(&self) {
        let records = self.records.get();
        self.home_history.set(records.clone());
        let mut rows = self.home_rows.get();
        if rows.is_empty() {
            return;
        }
        rows.retain(|row| row.kind != "history");
        if let Some(row) = history_row(&records) {
            rows.insert(0, row);
        }
        self.home_rows.set(rows);
    }

    /// `DELETE /api/favorites?key=source+id`.
    pub fn remove_favorite(&self, key: String) {
        if key.is_empty() || self.clearing.get() {
            return;
        }
        self.clearing.set(true);
        self.favorites.set(
            self.favorites
                .get()
                .into_iter()
                .filter(|item| item.key != key)
                .collect(),
        );
        let this = self.clone();
        spawn(async move {
            let path = format!("/api/favorites?key={}", urlencoding::encode(&key));
            let result = api(
                &this.server.get(),
                &this.token.get(),
                &path,
                "DELETE",
                None,
                true,
            )
            .await;
            this.clearing.set(false);
            match result {
                Ok((200..=299, _, next)) => this.accept_token(next),
                Ok((status, body, _)) => {
                    eprintln!("favorite delete HTTP {status}: {body}");
                    this.load_favorites(Some(format!("取消收藏失败（HTTP {status}），请重试")));
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.load_favorites(Some(message));
                }
            }
        });
    }

    /// `DELETE /api/favorites` with no key clears everything.
    pub fn clear_favorites(&self) {
        if self.favorites.get().is_empty() || self.clearing.get() {
            return;
        }
        self.clearing.set(true);
        self.favorites.set(Vec::new());
        let this = self.clone();
        spawn(async move {
            let result = api(
                &this.server.get(),
                &this.token.get(),
                "/api/favorites",
                "DELETE",
                Some("{}".to_owned()),
                true,
            )
            .await;
            this.clearing.set(false);
            match result {
                Ok((200..=299, _, next)) => this.accept_token(next),
                Ok((status, body, _)) => {
                    eprintln!("favorites clear HTTP {status}: {body}");
                    this.load_favorites(Some(format!("清空收藏失败（HTTP {status}），请重试")));
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.load_favorites(Some(message));
                }
            }
        });
    }

    /// `DELETE /api/playrecords?key=source+id`.
    pub fn remove_record(&self, key: String) {
        if key.is_empty() || self.clearing.get() {
            return;
        }
        self.clearing.set(true);
        self.records.set(
            self.records
                .get()
                .into_iter()
                .filter(|item| item.key != key)
                .collect(),
        );
        self.sync_home();
        let this = self.clone();
        spawn(async move {
            let path = format!("/api/playrecords?key={}", urlencoding::encode(&key));
            let result = api(
                &this.server.get(),
                &this.token.get(),
                &path,
                "DELETE",
                None,
                true,
            )
            .await;
            this.clearing.set(false);
            match result {
                Ok((200..=299, _, next)) => this.accept_token(next),
                Ok((status, body, _)) => {
                    eprintln!("record delete HTTP {status}: {body}");
                    this.load_records(Some(format!("删除记录失败（HTTP {status}），请重试")));
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.load_records(Some(message));
                }
            }
        });
    }

    /// `DELETE /api/playrecords` with the visible keys, as the site does.
    pub fn clear_records(&self) {
        if self.clearing.get() {
            return;
        }
        let mut keys: Vec<String> = self
            .records
            .get()
            .iter()
            .map(|item| item.key.clone())
            .collect();
        for item in self.home_history.get() {
            if !keys.contains(&item.key) {
                keys.push(item.key.clone());
            }
        }
        if keys.is_empty() {
            self.records.set(Vec::new());
            self.sync_home();
            return;
        }
        self.clearing.set(true);
        self.records.set(Vec::new());
        self.sync_home();
        let this = self.clone();
        spawn(async move {
            let body = serde_json::json!({ "keys": keys }).to_string();
            let result = api(
                &this.server.get(),
                &this.token.get(),
                "/api/playrecords",
                "DELETE",
                Some(body),
                true,
            )
            .await;
            this.clearing.set(false);
            match result {
                Ok((200..=299, _, next)) => this.accept_token(next),
                Ok((status, body, _)) => {
                    eprintln!("records clear HTTP {status}: {body}");
                    this.load_records(Some(format!("清空记录失败（HTTP {status}），请重试")));
                }
                Err(error) => {
                    let message = this.fail(error);
                    this.load_records(Some(message));
                }
            }
        });
    }
}

/// Favorites keyed `source+id`, newest first; the matching play record adds
/// the episode number the web list shows.
fn parse_favorites(favorites: &str, records: &str) -> Vec<FavoriteItem> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(favorites) else {
        return Vec::new();
    };
    let Some(map) = json.as_object() else {
        return Vec::new();
    };
    let saved = serde_json::from_str::<serde_json::Value>(records).unwrap_or_default();
    let mut list: Vec<FavoriteItem> = map
        .iter()
        .map(|(key, value)| FavoriteItem {
            key: key.clone(),
            title: value["title"].as_str().unwrap_or_default().to_owned(),
            cover: value["cover"].as_str().unwrap_or_default().to_owned(),
            year: value["year"].as_str().unwrap_or_default().to_owned(),
            source_name: value["source_name"].as_str().unwrap_or_default().to_owned(),
            total_episodes: value["total_episodes"].as_i64().unwrap_or(0),
            save_time: value["save_time"].as_f64().unwrap_or(0.0),
            current_episode: saved[key.as_str()]["index"].as_i64(),
        })
        .filter(|item| !item.title.is_empty())
        .collect();
    list.sort_by(|a, b| {
        b.save_time
            .partial_cmp(&a.save_time)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    list
}

fn page_header(
    title: &'static str,
    back: impl Fn() + Clone + 'static,
    scale: f32,
) -> impl IntoElement {
    view()
        .flex_row()
        .items_center()
        .gap(px(8.0 * scale))
        .px(px(12.0 * scale))
        .py(px(8.0 * scale))
        .child(
            view()
                .w(px(36.0 * scale))
                .h(px(36.0 * scale))
                .rounded(px(8.0 * scale))
                .items_center()
                .justify_center()
                .on_press(back)
                .child(
                    image(ICON_CHEVRON)
                        .w(px(20.0 * scale))
                        .h(px(20.0 * scale))
                        .rotate(deg(180.0))
                        .tint_color(move || theme().foreground),
                ),
        )
        .child(
            text(title)
                .flex_1()
                .text_size(px(18.0 * scale))
                .font_weight(nsh_core::element::FontWeight::SEMIBOLD),
        )
}

fn state_note(message: impl Fn() -> String + 'static, scale: f32) -> impl IntoElement {
    view().items_center().py(px(48.0 * scale)).child(
        text(message)
            .text_size(px(14.0 * scale))
            .text_color(move || theme().muted_foreground),
    )
}

fn retry_button(run: impl Fn() + Clone + 'static, scale: f32) -> impl IntoElement {
    view()
        .px(px(16.0 * scale))
        .py(px(8.0 * scale))
        .rounded(px(8.0 * scale))
        .bg(BRAND)
        .on_press(move || run())
        .child(
            text("重试")
                .text_size(px(14.0 * scale))
                .text_color(rgb(0xffffff)),
        )
}

/// "清空" opens the app-wide confirmation dialog; the dialog runs the clear.
fn clear_button(kind: &'static str, controller: &Controller, scale: f32) -> impl IntoElement {
    let c = controller.clone();
    view()
        .px(px(10.0 * scale))
        .py(px(6.0 * scale))
        .rounded(px(8.0 * scale))
        .on_press(move || c.request_clear(kind))
        .child(
            text("清空")
                .text_size(px(13.0 * scale))
                .text_color(move || theme().muted_foreground),
        )
}

pub fn favorites_page(
    controller: Controller,
    back: impl Fn() + Clone + 'static,
    open: impl Fn(Movie) + Clone + 'static,
    port: u16,
    site: String,
    scale: f32,
) -> impl IntoElement {
    let content = BASE_WIDTH * scale - 32.0 * scale;
    let card_w = (content - 16.0 * scale) / 3.0;
    let items = controller.favorites.clone();
    let loading = controller.favorites_loading.clone();
    let error = controller.favorites_error.clone();
    let retry = controller.clone();
    scroll().flex_1().child(
        view()
            .gap(px(4.0 * scale))
            .pb(px(24.0 * scale))
            .child(page_header("我的收藏", back, scale))
            .child(
                view()
                    .flex_row()
                    .items_center()
                    .justify_end()
                    .px(px(12.0 * scale))
                    .child(clear_button("favorites", &controller, scale)),
            )
            .child(when(
                {
                    let loading = loading.clone();
                    let items = items.clone();
                    move || loading.get() && items.get().is_empty()
                },
                {
                    let scale = scale;
                    move || state_note(|| "加载中…".to_owned(), scale)
                },
            ))
            .child(when(
                {
                    let error = error.clone();
                    move || !error.get().is_empty()
                },
                {
                    let error = error.clone();
                    let retry = retry.clone();
                    move || {
                        let message = error.get();
                        let retry = retry.clone();
                        view()
                            .items_center()
                            .gap(px(12.0 * scale))
                            .py(px(24.0 * scale))
                            .child(
                                text(message)
                                    .text_size(px(13.0 * scale))
                                    .text_color(move || theme().muted_foreground),
                            )
                            .child(retry_button(move || retry.open_favorites(), scale))
                    }
                },
            ))
            .child(when(
                {
                    let items = items.clone();
                    let loading = loading.clone();
                    let error = controller.favorites_error.clone();
                    move || !loading.get() && error.get().is_empty() && items.get().is_empty()
                },
                {
                    move || {
                        view()
                            .items_center()
                            .gap(px(8.0 * scale))
                            .py(px(64.0 * scale))
                            .child(
                                image(ICON_LIST)
                                    .w(px(40.0 * scale))
                                    .h(px(40.0 * scale))
                                    .tint_color(move || theme().border),
                            )
                            .child(
                                text("还没有收藏，去详情页点「收藏」")
                                    .text_size(px(14.0 * scale))
                                    .text_color(move || theme().muted_foreground),
                            )
                    }
                },
            ))
            .child(each(
                move || {
                    items
                        .get()
                        .chunks(3)
                        .map(|row| row.to_vec())
                        .collect::<Vec<_>>()
                },
                |row: &Vec<FavoriteItem>| {
                    row.first()
                        .map(|item| item.key.clone())
                        .unwrap_or_default()
                },
                {
                    let controller = controller.clone();
                    let site = site.clone();
                    let open = open.clone();
                    move |row: ReadSignal<Vec<FavoriteItem>>, _index| {
                        let site = site.clone();
                        let open = open.clone();
                        let controller = controller.clone();
                        view()
                            .flex_row()
                            .gap(px(8.0 * scale))
                            .px(px(16.0 * scale))
                            .pb(px(12.0 * scale))
                            .child(each(
                                move || row.get(),
                                |item: &FavoriteItem| item.key.clone(),
                                {
                                    let site = site.clone();
                                    let open = open.clone();
                                    let controller = controller.clone();
                                    move |item: ReadSignal<FavoriteItem>, _index| {
                                        favorite_card(
                                            item,
                                            controller.clone(),
                                            port,
                                            scale,
                                            card_w,
                                            site.clone(),
                                            open.clone(),
                                        )
                                    }
                                },
                            ))
                    }
                },
            )),
    )
}

#[allow(clippy::too_many_arguments)]
fn favorite_card(
    item: ReadSignal<FavoriteItem>,
    controller: Controller,
    port: u16,
    scale: f32,
    card_w: f32,
    site: String,
    open: impl Fn(Movie) + Clone + 'static,
) -> impl IntoElement {
    let selected = item.clone();
    let pick = open.clone();
    let remove = item.clone();
    let c = controller;
    let site_for_image = site;
    view()
        .w(px(card_w))
        .gap(px(6.0 * scale))
        .child(
            view()
                .w(px(card_w))
                .aspect_ratio(2.0 / 3.0)
                .rounded(px(8.0 * scale))
                .overflow_hidden()
                .bg(move || theme().muted)
                .on_press({
                    let selected = selected.clone();
                    let pick = pick.clone();
                    move || pick(selected.get().movie())
                })
                .child(
                    view()
                        .absolute()
                        .inset_0()
                        .items_center()
                        .justify_center()
                        .child(
                            image(ICON_FILM)
                                .w(px(28.0 * scale))
                                .h(px(28.0 * scale))
                                .tint_color(move || theme().border),
                        ),
                )
                .child(
                    image({
                        let item = item.clone();
                        let site = site_for_image.clone();
                        move || image_url(port, &site, &item.get().cover)
                    })
                    .absolute()
                    .inset_0()
                    .w_full()
                    .h_full(),
                )
                .child(
                    view()
                        .absolute()
                        .top(px(4.0 * scale))
                        .right(px(4.0 * scale))
                        .w(px(24.0 * scale))
                        .h(px(24.0 * scale))
                        .rounded(px(12.0 * scale))
                        .bg(rgba(0x000000A6))
                        .items_center()
                        .justify_center()
                        .on_press({
                            let remove = remove.clone();
                            let c = c.clone();
                            move || c.remove_favorite(remove.get().key.clone())
                        })
                        .child(
                            text("×")
                                .text_size(px(14.0 * scale))
                                .text_color(rgb(0xffffff)),
                        ),
                ),
        )
        .child(
            text(move || item.get().title.clone())
                .text_size(px(13.0 * scale))
                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                .number_of_lines(1),
        )
        .child(
            text({
                let item = item.clone();
                move || {
                    let item = item.get();
                    match item.current_episode {
                        Some(index) if index > 0 && item.total_episodes > 0 => {
                            format!("看到第 {index} 集 / 共 {} 集", item.total_episodes)
                        }
                        _ => item.year.clone(),
                    }
                }
            })
            .text_size(px(11.0 * scale))
            .text_color(move || theme().muted_foreground)
            .number_of_lines(1),
        )
}

pub fn history_page(
    controller: Controller,
    back: impl Fn() + Clone + 'static,
    open: impl Fn(Movie) + Clone + 'static,
    port: u16,
    site: String,
    scale: f32,
) -> impl IntoElement {
    let records = controller.records.clone();
    let loading = controller.records_loading.clone();
    let error = controller.records_error.clone();
    let retry = controller.clone();
    scroll().flex_1().child(
        view()
            .gap(px(4.0 * scale))
            .pb(px(24.0 * scale))
            .child(page_header("播放记录", back, scale))
            .child(
                view()
                    .flex_row()
                    .items_center()
                    .justify_end()
                    .px(px(12.0 * scale))
                    .child(clear_button("records", &controller, scale)),
            )
            .child(when(
                {
                    let loading = loading.clone();
                    let records = records.clone();
                    move || loading.get() && records.get().is_empty()
                },
                {
                    let scale = scale;
                    move || state_note(|| "加载中…".to_owned(), scale)
                },
            ))
            .child(when(
                {
                    let error = error.clone();
                    move || !error.get().is_empty()
                },
                {
                    let error = error.clone();
                    let retry = retry.clone();
                    move || {
                        let message = error.get();
                        let retry = retry.clone();
                        view()
                            .items_center()
                            .gap(px(12.0 * scale))
                            .py(px(24.0 * scale))
                            .child(
                                text(message)
                                    .text_size(px(13.0 * scale))
                                    .text_color(move || theme().muted_foreground),
                            )
                            .child(retry_button(move || retry.open_history(), scale))
                    }
                },
            ))
            .child(when(
                {
                    let records = records.clone();
                    let loading = loading.clone();
                    let error = controller.records_error.clone();
                    move || !loading.get() && error.get().is_empty() && records.get().is_empty()
                },
                {
                    move || {
                        view()
                            .items_center()
                            .gap(px(8.0 * scale))
                            .py(px(64.0 * scale))
                            .child(
                                image(ICON_FILM)
                                    .w(px(40.0 * scale))
                                    .h(px(40.0 * scale))
                                    .tint_color(move || theme().border),
                            )
                            .child(
                                text("还没有播放记录")
                                    .text_size(px(14.0 * scale))
                                    .text_color(move || theme().muted_foreground),
                            )
                    }
                },
            ))
            .child(each(
                move || records.get(),
                |item: &History| item.key.clone(),
                {
                    let site = site.clone();
                    let open = open.clone();
                    let controller = controller.clone();
                    move |item: ReadSignal<History>, _index| {
                        record_row(
                            item,
                            controller.clone(),
                            open.clone(),
                            port,
                            site.clone(),
                            scale,
                        )
                    }
                },
            )),
    )
}

fn record_row(
    item: ReadSignal<History>,
    controller: Controller,
    open: impl Fn(Movie) + Clone + 'static,
    port: u16,
    site: String,
    scale: f32,
) -> impl IntoElement {
    let selected = item.clone();
    let pick = open.clone();
    let site_for_image = site;
    let key = item.get().key.clone();
    view()
        .flex_row()
        .items_center()
        .gap(px(10.0 * scale))
        .px(px(16.0 * scale))
        .py(px(8.0 * scale))
        .child(
            view()
                .flex_row()
                .items_center()
                .gap(px(10.0 * scale))
                .flex_1()
                .on_press({
                    let pick = pick.clone();
                    let selected = selected.clone();
                    move || pick(record_movie(&selected.get()))
                })
                .child(
                    view()
                        .w(px(112.0 * scale))
                        .aspect_ratio(16.0 / 9.0)
                        .rounded(px(6.0 * scale))
                        .overflow_hidden()
                        .bg(move || theme().muted)
                        .child(
                            image({
                                let item = item.clone();
                                let site = site_for_image.clone();
                                move || image_url(port, &site, &item.get().cover)
                            })
                            .absolute()
                            .inset_0()
                            .w_full()
                            .h_full(),
                        ),
                )
                .child(
                    view()
                        .flex_1()
                        .gap(px(4.0 * scale))
                        .child(
                            text(move || item.get().title.clone())
                                .text_size(px(14.0 * scale))
                                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                                .number_of_lines(1),
                        )
                        .child(
                            text({
                                let item = item.clone();
                                move || {
                                    let item = item.get();
                                    let episode = if item.index > 0.0 {
                                        format!("第 {} 集", item.index as i64)
                                    } else {
                                        "第 1 集".to_owned()
                                    };
                                    if item.source_name.is_empty() {
                                        episode
                                    } else {
                                        format!("{episode} · {}", item.source_name)
                                    }
                                }
                            })
                            .text_size(px(11.0 * scale))
                            .text_color(move || theme().muted_foreground)
                            .number_of_lines(1),
                        )
                        .child(
                            text({
                                let item = item.clone();
                                move || {
                                    let item = item.get();
                                    let watched = human_time(item.play_time);
                                    if item.total_time > 0.0 {
                                        format!("已看 {watched} / {}", human_time(item.total_time))
                                    } else {
                                        format!("已看 {watched}")
                                    }
                                }
                            })
                            .text_size(px(11.0 * scale))
                            .text_color(move || theme().muted_foreground)
                            .number_of_lines(1),
                        ),
                ),
        )
        .child(
            view()
                .px(px(8.0 * scale))
                .py(px(4.0 * scale))
                .rounded(px(6.0 * scale))
                .on_press({
                    let c = controller.clone();
                    let key = key.clone();
                    move || c.remove_record(key.clone())
                })
                .child(
                    text("删除")
                        .text_size(px(12.0 * scale))
                        .text_color(move || theme().muted_foreground),
                ),
        )
}

const REPO_URL: &str = "https://github.com/Happier-X/MoonTVPlus";

/// The version comes from the app manifest, not a literal in this file.
fn app_version() -> String {
    serde_json::from_str::<serde_json::Value>(include_str!("../nsh.json"))
        .ok()
        .and_then(|value| value["version"].as_str().map(str::to_owned))
        .unwrap_or_else(|| "未知".to_owned())
}

pub fn me_page(
    controller: Controller,
    username: Signal<String>,
    server: Signal<String>,
    dark: ReadSignal<bool>,
    toggle_theme: impl Fn() + Clone + 'static,
    ask_logout: impl Fn() + 'static,
    back: impl Fn() + Clone + 'static,
    scale: f32,
) -> impl IntoElement {
    let favorites = controller.favorites.clone();
    let records = controller.records.clone();
    let open_favorites = controller.clone();
    let open_history = controller.clone();
    scroll().flex_1().child(
        view()
            .gap(px(10.0 * scale))
            .pb(px(24.0 * scale))
            .child(page_header("我的", back, scale))
            .child(
                view()
                    .items_center()
                    .gap(px(8.0 * scale))
                    .py(px(12.0 * scale))
                    .child(
                        view()
                            .w(px(64.0 * scale))
                            .h(px(64.0 * scale))
                            .rounded(px(32.0 * scale))
                            .bg(BRAND)
                            .items_center()
                            .justify_center()
                            .child(
                                text({
                                    let username = username.clone();
                                    move || {
                                        username
                                            .get()
                                            .chars()
                                            .next()
                                            .unwrap_or('M')
                                            .to_uppercase()
                                            .to_string()
                                    }
                                })
                                .text_size(px(24.0 * scale))
                                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                                .text_color(rgb(0xffffff)),
                            ),
                    )
                    .child(
                        text({
                            let username = username.clone();
                            move || username.get()
                        })
                        .text_size(px(16.0 * scale))
                        .font_weight(nsh_core::element::FontWeight::SEMIBOLD),
                    ),
            )
            .child(section_title("账号", scale))
            .child(info_row(
                "用户名",
                {
                    let username = username.clone();
                    move || username.get()
                },
                scale,
            ))
            .child(info_row(
                "服务器",
                {
                    let server = server.clone();
                    move || server.get()
                },
                scale,
            ))
            .child(section_title("内容", scale))
            .child(me_entry(
                ICON_LIST,
                "我的收藏",
                {
                    let favorites = favorites.clone();
                    move || format!("{} 部", favorites.get().len())
                },
                {
                    let c = open_favorites.clone();
                    move || c.open_favorites()
                },
                scale,
            ))
            .child(me_entry(
                ICON_FILM,
                "播放记录",
                {
                    let records = records.clone();
                    move || format!("{} 条", records.get().len())
                },
                {
                    let c = open_history.clone();
                    move || c.open_history()
                },
                scale,
            ))
            .child(section_title("外观", scale))
            .child(
                view()
                    .flex_row()
                    .items_center()
                    .gap(px(12.0 * scale))
                    .mx(px(16.0 * scale))
                    .px(px(14.0 * scale))
                    .py(px(12.0 * scale))
                    .rounded(px(10.0 * scale))
                    .bg(move || theme().muted)
                    .on_press({
                        let toggle = toggle_theme.clone();
                        move || toggle()
                    })
                    .child(
                        image(move || if dark.get() { ICON_SUN } else { ICON_MOON })
                            .w(px(20.0 * scale))
                            .h(px(20.0 * scale))
                            .tint_color(move || theme().foreground),
                    )
                    .child(
                        text("深色模式")
                            .flex_1()
                            .text_size(px(15.0 * scale))
                            .font_weight(nsh_core::element::FontWeight::SEMIBOLD),
                    )
                    .child(
                        when(
                            {
                                let dark = dark.clone();
                                move || dark.get()
                            },
                            move || switch_pill(true, scale),
                        )
                        .otherwise(move || switch_pill(false, scale)),
                    ),
            )
            .child(section_title("关于", scale))
            .child(info_row("版本", app_version, scale))
            .child(info_row("开源地址", || REPO_URL.to_owned(), scale))
            .child(
                view()
                    .mx(px(16.0 * scale))
                    .mt(px(12.0 * scale))
                    .py(px(12.0 * scale))
                    .rounded(px(10.0 * scale))
                    .items_center()
                    .bg(move || theme().muted)
                    .on_press(move || ask_logout())
                    .child(
                        text("退出登录")
                            .text_size(px(15.0 * scale))
                            .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                            .text_color(rgb(0xCC2222)),
                    ),
            ),
    )
}

fn switch_pill(on: bool, scale: f32) -> impl IntoElement {
    let pill = view()
        .w(px(44.0 * scale))
        .h(px(24.0 * scale))
        .rounded(px(12.0 * scale))
        .p(px(2.0 * scale))
        .flex_row()
        .items_center();
    let pill = if on {
        pill.bg(BRAND).justify_end()
    } else {
        pill.bg(move || theme().border).justify_start()
    };
    pill.child(
        view()
            .w(px(20.0 * scale))
            .h(px(20.0 * scale))
            .rounded(px(10.0 * scale))
            .bg(rgb(0xffffff)),
    )
}

fn section_title(title: &'static str, scale: f32) -> impl IntoElement {
    text(title)
        .px(px(16.0 * scale))
        .pt(px(8.0 * scale))
        .text_size(px(12.0 * scale))
        .text_color(move || theme().muted_foreground)
}

fn info_row(
    label: &'static str,
    value: impl Fn() -> String + 'static,
    scale: f32,
) -> impl IntoElement {
    view()
        .flex_row()
        .items_center()
        .gap(px(12.0 * scale))
        .mx(px(16.0 * scale))
        .px(px(14.0 * scale))
        .py(px(12.0 * scale))
        .rounded(px(10.0 * scale))
        .bg(move || theme().muted)
        .child(
            text(label)
                .text_size(px(14.0 * scale))
                .text_color(move || theme().muted_foreground),
        )
        .child(
            text(value)
                .flex_1()
                .text_size(px(14.0 * scale))
                .number_of_lines(1),
        )
}

fn me_entry(
    icon: ImageAsset,
    label: &'static str,
    count: impl Fn() -> String + 'static,
    open: impl Fn() + Clone + 'static,
    scale: f32,
) -> impl IntoElement {
    view()
        .flex_row()
        .items_center()
        .gap(px(12.0 * scale))
        .mx(px(16.0 * scale))
        .px(px(14.0 * scale))
        .py(px(12.0 * scale))
        .rounded(px(10.0 * scale))
        .bg(move || theme().muted)
        .on_press(move || open())
        .child(
            image(icon)
                .w(px(20.0 * scale))
                .h(px(20.0 * scale))
                .tint_color(move || theme().foreground),
        )
        .child(
            text(label)
                .flex_1()
                .text_size(px(15.0 * scale))
                .font_weight(nsh_core::element::FontWeight::SEMIBOLD),
        )
        .child(
            text(count)
                .text_size(px(12.0 * scale))
                .text_color(move || theme().muted_foreground),
        )
        .child(
            image(ICON_CHEVRON)
                .w(px(16.0 * scale))
                .h(px(16.0 * scale))
                .tint_color(move || theme().muted_foreground),
        )
}
