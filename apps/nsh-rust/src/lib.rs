//! MoonTVPlus pure-Rust app: batch 1 (home modules, images, continue watching).

mod browse;
mod detail;
mod proxy;
mod search;

use std::pin::pin;
use std::time::Duration;

use futures_util::future::{select, Either};
use nsh_base::theme;
use nsh_core::element::input;
use nsh_core::platform::Appearance;
use nsh_core::prelude::*;
use nsh_core::storage::Storage;
use serde_json::json;

const DEFAULT_SERVER: &str = "https://tv.370125.xyz";

const ICON_SEARCH: ImageAsset = asset!("icons/search.svg");
const ICON_HOME: ImageAsset = asset!("icons/home.svg");
const ICON_FILM: ImageAsset = asset!("icons/film.svg");
const ICON_TV: ImageAsset = asset!("icons/tv.svg");
const ICON_CAT: ImageAsset = asset!("icons/cat.svg");
const ICON_CLOVER: ImageAsset = asset!("icons/clover.svg");
const ICON_MOON: ImageAsset = asset!("icons/moon.svg");
const ICON_SUN: ImageAsset = asset!("icons/sun.svg");
const ICON_LINK: ImageAsset = asset!("icons/link.svg");
const ICON_LIST: ImageAsset = asset!("icons/list.svg");
const ICON_CHEVRON: ImageAsset = asset!("icons/chevron-right.svg");

/// The web layout is drawn for 390dp; each screen scales from that.
const BASE_WIDTH: f32 = 390.0;
/// The web brand green, used for the wordmark, the selected tab and the rating badge.
const BRAND: AdaptiveColor = AdaptiveColor::rgb(0x22C55E, 0x22C55E);
/// Rating tiers copied from the web `.mtv-rate` palette (hi / mid / lo).
const RATE_HI: AdaptiveColor = AdaptiveColor::rgb(0xFF8A3D, 0xFF8A3D);
const RATE_HI_INK: AdaptiveColor = AdaptiveColor::rgb(0x4A2600, 0x4A2600);
const RATE_MID: AdaptiveColor = AdaptiveColor::rgb(0xEC4899, 0xEC4899);
const RATE_LO: AdaptiveColor = AdaptiveColor::rgb(0x9FB0CC, 0x9FB0CC);
const RATE_LO_INK: AdaptiveColor = AdaptiveColor::rgb(0x20283A, 0x20283A);

#[native_module("moontvplus-rust/native")]
fn exports(registry: &mut Registry) {
    registry.assets(assets!());
    registry.register::<App>();
}

#[derive(Clone, PartialEq, Default)]
struct Movie {
    id: String,
    title: String,
    poster: String,
    rate: String,
    year: String,
    /// Watch progress 0..=1 for continue-watching cards (0 hides the bar).
    progress: f64,
    /// Source display name (`source_name`) for continue watching.
    source_name: String,
    /// Watched time rendered the way the web shows it (e.g. `2分47秒`).
    elapsed: String,
}

#[derive(Clone, PartialEq)]
struct Row {
    id: String,
    title: String,
    kind: String,
    items: Vec<Movie>,
}

#[derive(Clone, PartialEq, Default)]
struct History {
    key: String,
    title: String,
    cover: String,
    year: String,
    index: f64,
    play_time: f64,
    total_time: f64,
    save_time: f64,
    source_name: String,
}

enum ApiError {
    AuthExpired,
    Network(String),
}

async fn send_once(request: HttpRequest) -> Result<(u16, String), String> {
    let http = pin!(fetch(request));
    let wait = pin!(timer(Duration::from_secs(15)));
    match select(http, wait).await {
        Either::Left((result, _)) => result.map(|response| (response.status(), response.text())),
        Either::Right(_) => Err("请求超时 15s".to_owned()),
    }
}

async fn send(
    server: &str,
    token: &str,
    path: &str,
    method: &str,
    body: Option<String>,
    auth: bool,
) -> Result<(u16, String), String> {
    let url = format!("{}{}", server.trim_end_matches('/'), path);
    let mut last = String::new();
    for attempt in 0..2 {
        let mut request = match method {
            "POST" => {
                let request = HttpRequest::post(url.clone(), body.clone().unwrap_or_default());
                request.header("Content-Type", "application/json")
            }
            _ => HttpRequest::get(url.clone()),
        };
        request = request.header("Accept", "*/*");
        if auth && !token.is_empty() {
            request = request.header("Authorization", format!("Bearer {token}"));
        }
        match send_once(request).await {
            Ok(result) => return Ok(result),
            Err(error) => last = error,
        }
        if attempt == 0 {
            timer(Duration::from_millis(600)).await;
        }
    }
    Err(last)
}

async fn refresh(server: &str, token: &str) -> Result<Option<String>, ApiError> {
    let url = format!("{}/api/auth/refresh", server.trim_end_matches('/'));
    let request = HttpRequest::post(url, String::new())
        .header("Accept", "*/*")
        .header("Authorization", format!("Bearer {token}"));
    match send_once(request).await {
        Ok((200, text)) => {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                if json["ok"].as_bool() == Some(true) {
                    if let Some(value) = json["token"].as_str() {
                        return Ok(Some(value.to_owned()));
                    }
                }
            }
            Ok(None)
        }
        Ok((401, _)) => Err(ApiError::AuthExpired),
        Ok((status, body)) => Err(ApiError::Network(format!("refresh HTTP {status}: {body}"))),
        Err(error) => Err(ApiError::Network(error)),
    }
}

/// A request with one refresh-and-retry for authenticated 401s.
/// Public endpoints never clear the session; only a failed refresh logs out.
async fn api(
    server: &str,
    token: &str,
    path: &str,
    method: &str,
    body: Option<String>,
    auth: bool,
) -> Result<(u16, String, Option<String>), ApiError> {
    let (status, text) = send(server, token, path, method, body.clone(), auth)
        .await
        .map_err(ApiError::Network)?;
    if status != 401 || !auth {
        return Ok((status, text, None));
    }
    match refresh(server, token).await? {
        Some(next) => {
            let (status, text) = send(server, &next, path, method, body, true)
                .await
                .map_err(ApiError::Network)?;
            if status == 401 {
                Err(ApiError::AuthExpired)
            } else {
                Ok((status, text, Some(next)))
            }
        }
        None => Err(ApiError::Network("刷新未返回新 token".to_owned())),
    }
}

async fn login(server: &str, user: &str, password: &str) -> Result<String, String> {
    let body = json!({ "username": user, "password": password }).to_string();
    let (status, text) = send(server, "", "/api/login", "POST", Some(body), false).await?;
    if status == 401 {
        return Err("用户名或密码错误".to_owned());
    }
    let json: serde_json::Value =
        serde_json::from_str(&text).map_err(|_| format!("HTTP {status}"))?;
    if status == 200 && json["ok"].as_bool() == Some(true) {
        if let Some(token) = json["token"].as_str() {
            return Ok(token.to_owned());
        }
    }
    Err(json["message"]
        .as_str()
        .or_else(|| json["error"].as_str())
        .unwrap_or("登录失败")
        .to_owned())
}

fn save_session(server: &str, user: &str, token: &str) {
    let _ = Storage::set_item("server", server);
    let _ = Storage::set_item("username", user);
    let _ = Storage::set_item("token", token);
}

fn clear_session() {
    proxy::stop();
    let _ = Storage::remove_item("token");
    let _ = Storage::remove_item("username");
}

fn parse_movies(text: &str) -> Vec<Movie> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let Some(list) = json["list"].as_array() else {
        return Vec::new();
    };
    list.iter()
        .map(|item| Movie {
            id: item["id"].as_str().unwrap_or_default().to_owned(),
            title: item["title"].as_str().unwrap_or_default().to_owned(),
            poster: item["poster"].as_str().unwrap_or_default().to_owned(),
            rate: item["rate"].as_str().unwrap_or_default().to_owned(),
            year: item["year"].as_str().unwrap_or_default().to_owned(),
            progress: 0.0,
            ..Movie::default()
        })
        .filter(|movie| !movie.title.is_empty())
        .collect()
}

fn parse_bangumi(text: &str) -> Vec<Movie> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let Some(days) = json.as_array() else {
        return Vec::new();
    };
    let mut movies = Vec::new();
    for day in days {
        let Some(items) = day["items"].as_array() else {
            continue;
        };
        for item in items {
            let images = &item["images"];
            let poster = ["large", "common", "medium", "small"]
                .iter()
                .find_map(|key| images[*key].as_str())
                .unwrap_or_default()
                .to_owned();
            if poster.is_empty() {
                continue;
            }
            let title = item["name_cn"]
                .as_str()
                .filter(|value| !value.is_empty())
                .or_else(|| item["name"].as_str())
                .unwrap_or_default()
                .to_owned();
            if title.is_empty() {
                continue;
            }
            let air_date = item["air_date"].as_str().unwrap_or_default();
            movies.push(Movie {
                id: item["id"].as_u64().map(|value| value.to_string()).unwrap_or_default(),
                title,
                poster,
                rate: item["rating"]["score"]
                    .as_f64()
                    .filter(|score| *score > 0.0)
                    .map(|score| format!("{score:.1}"))
                    .unwrap_or_default(),
                year: air_date.get(0..4).unwrap_or_default().to_owned(),
                progress: 0.0,
                ..Movie::default()
            });
        }
    }
    movies
}

fn parse_duanju(text: &str) -> Vec<Movie> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let Some(list) = json["data"].as_array() else {
        return Vec::new();
    };
    list.iter()
        .map(|item| Movie {
            id: item["id"].as_str().unwrap_or_default().to_owned(),
            title: item["title"].as_str().unwrap_or_default().to_owned(),
            poster: item["poster"].as_str().unwrap_or_default().to_owned(),
            rate: String::new(),
            year: item["year"].as_str().unwrap_or_default().to_owned(),
            progress: 0.0,
            ..Movie::default()
        })
        .filter(|movie| !movie.title.is_empty() && !movie.poster.is_empty())
        .collect()
}

fn parse_upcoming(text: &str) -> Vec<Movie> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let Some(list) = json["data"].as_array() else {
        return Vec::new();
    };
    let mut movies: Vec<Movie> = list
        .iter()
        .filter_map(|item| {
            let poster = item["poster_path"].as_str()?;
            Some(Movie {
                id: item["id"].as_u64().map(|value| value.to_string()).unwrap_or_default(),
                title: item["title"]
                    .as_str()
                    .or_else(|| item["name"].as_str())
                    .unwrap_or_default()
                    .to_owned(),
                poster: format!("https://image.tmdb.org/t/p/w500{poster}"),
                rate: item["vote_average"]
                    .as_f64()
                    .filter(|score| *score > 0.0)
                    .map(|score| format!("{score:.1}"))
                    .unwrap_or_default(),
                year: item["release_date"]
                    .as_str()
                    .unwrap_or_default()
                    .get(0..4)
                    .unwrap_or_default()
                    .to_owned(),
                progress: 0.0,
                ..Movie::default()
            })
        })
        .collect();
    movies.sort_by(|a, b| a.year.cmp(&b.year));
    movies
}

/// Renders a duration the way the web's continue-watching cards do.
fn human_time(seconds: f64) -> String {
    let total = seconds.max(0.0) as i64;
    if total >= 3600 {
        format!("{}小时{}分", total / 3600, (total % 3600) / 60)
    } else if total >= 60 {
        format!("{}分{}秒", total / 60, total % 60)
    } else {
        format!("{total}秒")
    }
}

fn rating_style(rate: &str) -> (AdaptiveColor, AdaptiveColor) {
    match rate.parse::<f32>() {
        Ok(value) if value >= 8.0 => (RATE_HI, RATE_HI_INK),
        Ok(value) if value >= 6.5 => (RATE_MID, AdaptiveColor::rgb(0xFFFFFF, 0xFFFFFF)),
        _ => (RATE_LO, RATE_LO_INK),
    }
}

fn parse_history(text: &str) -> Vec<History> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let Some(map) = json.as_object() else {
        return Vec::new();
    };
    let mut list: Vec<History> = map
        .iter()
        .map(|(key, value)| History {
            key: key.clone(),
            title: value["title"].as_str().unwrap_or_default().to_owned(),
            cover: value["cover"].as_str().unwrap_or_default().to_owned(),
            year: value["year"].as_str().unwrap_or_default().to_owned(),
            index: value["index"].as_f64().unwrap_or(0.0),
            play_time: value["play_time"].as_f64().unwrap_or(0.0),
            total_time: value["total_time"].as_f64().unwrap_or(0.0),
            save_time: value["save_time"].as_f64().unwrap_or(0.0),
            source_name: value["source_name"].as_str().unwrap_or_default().to_owned(),
        })
        .filter(|item| !item.title.is_empty())
        .collect();
    list.sort_by(|a, b| b.save_time.partial_cmp(&a.save_time).unwrap_or(std::cmp::Ordering::Equal));
    list
}

/// Image URL through the local proxy, so the host can attach Authorization.
fn image_url(port: u16, site: &str, url: &str) -> String {
    if url.is_empty() || url.starts_with("asset:") {
        return url.to_owned();
    }
    // Douban and Bangumi reject hotlinks, so the site's image proxy (which sets
    // the right Referer) fetches them; the local proxy only adds the Bearer.
    let target = if url.contains("doubanio.com") || url.contains("bgm.tv") {
        format!(
            "{}/api/image-proxy?url={}",
            site.trim_end_matches('/'),
            urlencoding::encode(url)
        )
    } else {
        url.to_owned()
    };
    if port == 0 {
        return target;
    }
    format!(
        "http://127.0.0.1:{}/img?u={}",
        port,
        urlencoding::encode(&target)
    )
}

fn module_visible(id: &str, enabled: &[(String, bool)]) -> bool {
    enabled
        .iter()
        .find(|(key, _)| key == id)
        .map(|(_, value)| *value)
        .unwrap_or(true)
}

fn module_order(enabled: &[(String, bool)]) -> Vec<String> {
    match enabled.len() {
        0 => vec![
            "hotMovies".to_owned(),
            "hotDuanju".to_owned(),
            "bangumiCalendar".to_owned(),
            "hotTvShows".to_owned(),
            "hotVarietyShows".to_owned(),
            "upcomingContent".to_owned(),
        ],
        _ => enabled.iter().map(|(id, _)| id.clone()).collect(),
    }
}

#[component]
pub fn App() -> impl IntoElement {
    let saved_server = Storage::get_item("server").unwrap_or_else(|| DEFAULT_SERVER.to_owned());
    let saved_token = Storage::get_item("token").unwrap_or_default();
    let saved_user = Storage::get_item("username").unwrap_or_default();
    let saved_theme = Storage::get_item("theme").unwrap_or_else(|| "light".to_owned());

    let page = signal(if saved_token.is_empty() { "login" } else { "home" });
    let server = signal(saved_server);
    let username = signal(saved_user);
    let password = signal(String::new());
    let token = signal(saved_token);
    let busy = signal(false);
    let message = signal(String::new());
    let error = signal(String::new());
    let dark = signal(saved_theme == "dark");
    let proxy_port = signal(0_u16);

    let movies = signal(Vec::<Movie>::new());
    let duanju = signal(Vec::<Movie>::new());
    let bangumi = signal(Vec::<Movie>::new());
    let tv_shows = signal(Vec::<Movie>::new());
    let variety = signal(Vec::<Movie>::new());
    let upcoming = signal(Vec::<Movie>::new());
    let history = signal(Vec::<History>::new());
    let search_query = signal(Storage::get_item("last_search").unwrap_or_default());
    let search_state = signal(search::SearchSnapshot::default());
    let search_running = signal(false);
    let modules = signal(Vec::<(String, bool)>::new());
    let home_loading = signal(false);
    let details = detail::Controller::new(server.clone(), token.clone(), username.clone(), page.clone(), proxy_port.clone());
    let detail_origin = signal("home");
    let browse = browse::Controller::new(server.clone(), token.clone(), page.clone());
    let stage = signal("idle".to_owned());
    let refreshing = signal(false);
    let rows = signal(Vec::<Row>::new());
    let scale = signal(1.0_f32);
    effect(move || {
        let width = window().width.0;
        if width > 0.0 {
            scale.set((width / BASE_WIDTH).clamp(0.85, 1.6));
        }
    });

    let theme_watch = dark.clone();
    effect(move || {
        set_color_scheme(if theme_watch.get() { Appearance::Dark } else { Appearance::Light });
    });

    let toggle_theme = {
        let dark = dark.clone();
        move || {
            let next = !dark.get();
            dark.set(next);
            let _ = Storage::set_item("theme", if next { "dark" } else { "light" });
        }
    };

    // One proxy for both images and playback.
    let proxy_watch_server = server.clone();
    let proxy_watch_token = token.clone();
    let proxy_watch_page = page.clone();
    let proxy_watch_port = proxy_port.clone();
    effect(move || {
        if page_get(&proxy_watch_page) == "login" {
            return;
        }
        if proxy_watch_port.get() > 0 {
            return;
        }
        if let Ok(port) = proxy::ensure(proxy_watch_server.get(), proxy_watch_token.get()) {
            proxy_watch_port.set(port);
        }
    });

    let load_home = {
        let server = server.clone();
        let token = token.clone();
        let movies = movies.clone();
        let duanju = duanju.clone();
        let bangumi = bangumi.clone();
        let tv_shows = tv_shows.clone();
        let variety = variety.clone();
        let upcoming = upcoming.clone();
        let history = history.clone();
        let modules = modules.clone();
        let loading = home_loading.clone();
        let error = error.clone();
        let page = page.clone();
        let proxy_port = proxy_port.clone();
        let username = username.clone();
        let message = message.clone();
        let stage = stage.clone();
        let rows = rows.clone();
        move || {
            if loading.get() {
                return;
            }
            loading.set(true);
            error.set(String::new());
            let server_value = server.get();
            let token_value = token.get();
            let token = token.clone();
            let movies = movies.clone();
            let duanju = duanju.clone();
            let bangumi = bangumi.clone();
            let tv_shows = tv_shows.clone();
            let variety = variety.clone();
            let upcoming = upcoming.clone();
            let history = history.clone();
            let modules = modules.clone();
            let loading = loading.clone();
            let error = error.clone();
            let page = page.clone();
            let proxy_port = proxy_port.clone();
            let username = username.clone();
            let message = message.clone();
            let stage_inner = stage.clone();
            let rows_inner = rows.clone();
            // Keep the task: dropping `Spawned` cancels the load.
            std::mem::forget(spawn(async move {
                stage_inner.set("start".to_owned());
                // Layout settings are optional: a 401 or a closed sync keeps defaults.
                if let Ok((200, text, _)) =
                    api(&server_value, &token_value, "/api/local-settings-sync", "GET", None, true).await
                {
                    if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                        if let Some(items) = json["payload"]["data"]["homeModules"].as_str() {
                            if let Ok(list) = serde_json::from_str::<serde_json::Value>(items) {
                                if let Some(array) = list.as_array() {
                                    let mut ordered: Vec<(f64, String, bool)> = array
                                        .iter()
                                        .filter_map(|item| {
                                            let id = item["id"].as_str()?.to_owned();
                                            Some((
                                                item["order"].as_f64().unwrap_or(0.0),
                                                id,
                                                item["enabled"].as_bool().unwrap_or(true),
                                            ))
                                        })
                                        .collect();
                                    ordered.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
                                    modules.set(
                                        ordered
                                            .into_iter()
                                            .map(|(_, id, enabled)| (id, enabled))
                                            .collect(),
                                    );
                                }
                            }
                        }
                    }
                }

                let get = |path: &'static str| {
                    let server = server_value.clone();
                    let token = token_value.clone();
                    async move {
                        api(&server, &token, path, "GET", None, true)
                            .await
                            .map(|(_, text, _)| text)
                            .unwrap_or_default()
                    }
                };

                stage_inner.set("settings done".to_owned());
                let movie_text = get(
                    "/api/douban/categories?kind=movie&category=%E7%83%AD%E9%97%A8&type=%E5%85%A8%E9%83%A8&limit=24",
                )
                .await;
                movies.set(parse_movies(&movie_text));

                stage_inner.set("movies done".to_owned());
                let duanju_text = get("/api/duanju/recommends").await;
                duanju.set(parse_duanju(&duanju_text));

                stage_inner.set("duanju done".to_owned());
                let bangumi_text = get("/api/bangumi/calendar").await;
                bangumi.set(parse_bangumi(&bangumi_text));

                stage_inner.set("bangumi done".to_owned());
                let tv_text =
                    get("/api/douban/categories?kind=tv&category=tv&type=tv&limit=24").await;
                tv_shows.set(parse_movies(&tv_text));

                let show_text =
                    get("/api/douban/categories?kind=tv&category=show&type=show&limit=24").await;
                variety.set(parse_movies(&show_text));

                stage_inner.set("shows done".to_owned());
                let upcoming_text = get("/api/tmdb/upcoming").await;
                upcoming.set(parse_upcoming(&upcoming_text));

                stage_inner.set("upcoming done".to_owned());
                match api(&server_value, &token_value, "/api/playrecords", "GET", None, true).await {
                    Ok((status, text, next)) => {
                        if status == 401 {
                            error.set("登录已过期，请重新登录".to_owned());
                        } else {
                            history.set(parse_history(&text));
                        }
                        if let Some(next) = next {
                            token.set(next.clone());
                            let _ = Storage::set_item("token", &next);
                        }
                    }
                    Err(ApiError::AuthExpired) => {
                        clear_session();
                        token.set(String::new());
                        proxy_port.set(0);
                        page.set("login");
                        error.set("登录已过期，请重新登录".to_owned());
                    }
                    Err(ApiError::Network(reason)) => {
                        error.set(format!("网络错误：{reason}"));
                    }
                }

                if proxy_port.get() == 0 {
                    if let Ok(port) = proxy::ensure(server_value.clone(), token.get()) {
                        proxy_port.set(port);
                    }
                }
                if username.get().is_empty() {
                    let _ = message;
                }
                let mut list: Vec<Row> = Vec::new();
                let mut seen_titles: Vec<String> = Vec::new();
                let history_items: Vec<Movie> = history
                    .get()
                    .into_iter()
                    .filter(|item| {
                        if seen_titles.iter().any(|title| title == &item.title) {
                            false
                        } else {
                            seen_titles.push(item.title.clone());
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
                        ..Movie::default()
                    })
                    .collect();
                if !history_items.is_empty() {
                    list.push(Row {
                        id: "history".to_owned(),
                        title: "继续观看".to_owned(),
                        kind: "history".to_owned(),
                        items: history_items,
                    });
                }
                let configured = module_order(&modules.get());
                let mut candidates = vec![
                    ("hotMovies", "热门电影", movies.get(), "movie"),
                    ("hotDuanju", "热播短剧", duanju.get(), "duanju"),
                    ("bangumiCalendar", "新番放送", bangumi.get(), "anime"),
                    ("hotTvShows", "热门剧集", tv_shows.get(), "tv"),
                    ("hotVarietyShows", "热门综艺", variety.get(), "show"),
                    ("upcomingContent", "即将上映", upcoming.get(), "upcoming"),
                ];
                candidates.sort_by_key(|(id, _, _, _)| {
                    configured.iter().position(|key| key == *id).unwrap_or(99)
                });
                for (id, title, items, kind) in candidates {
                    if items.is_empty() || !module_visible(id, &modules.get()) {
                        continue;
                    }
                    list.push(Row {
                        id: id.to_owned(),
                        title: title.to_owned(),
                        kind: kind.to_owned(),
                        items,
                    });
                }
                rows_inner.set(list);
                stage_inner.set(format!(
                    "done m={} d={} b={} t={} v={} u={} h={}",
                    movies.get().len(), duanju.get().len(), bangumi.get().len(),
                    tv_shows.get().len(), variety.get().len(), upcoming.get().len(), history.get().len()
                ));
                loading.set(false);
            }));
        }
    };

    // Started here, before any image URL is built: the port is stable for the
    // session and images cannot wait for a later update.
    if page.get() == "home" && proxy_port.get() == 0 && !token.get().is_empty() {
        if let Ok(port) = proxy::ensure(server.get(), token.get()) {
            proxy_port.set(port);
        }
    }

    // Spawned from the component body: here the task's owner is the component,
    // so the load is not cancelled (an effect's owner ends with the effect).
    if page.get() == "home" && !token.get().is_empty() {
        (load_home)();
    }

    let open_detail = {
        let details = details.clone();
        let page = page.clone();
        let origin = detail_origin.clone();
        move |movie: Movie| {
            origin.set(page.get());
            details.open(movie, None, Vec::new());
        }
    };
    let open_search_detail = {
        let details = details.clone();
        let state = search_state.clone();
        let origin = detail_origin.clone();
        move |item: search::SearchItem| {
            origin.set("search");
            // Preserve all providers behind this aggregated poster without
            // storing a partial stream under a different cache key.
            let extra = state.get().groups.into_iter().flat_map(|group| group.items)
                .filter(|candidate| candidate.title.trim() == item.title.trim()
                    && (item.year.is_empty() || candidate.year.is_empty() || candidate.year == item.year))
                .collect();
            details.open(Movie { title: item.title.clone(), poster: item.poster.clone(),
                year: item.year.clone(), ..Default::default() }, Some(item), extra);
        }
    };
    let detail_back = {
        let page = page.clone();
        let origin = detail_origin.clone();
        move || page.set(origin.get())
    };
    let load_browse = {
        let browse = browse.clone();
        move |kind: String| browse.open(kind)
    };
    let open_browse = load_browse.clone();
    let back_home = {
        let page = page.clone();
        move || page.set("home")
    };

    let clear_records = {
        let server = server.clone();
        let token = token.clone();
        let history_signal = history.clone();
        let rows_signal = rows.clone();
        move || {
            let keys: Vec<String> = history_signal
                .get()
                .iter()
                .map(|item| item.key.clone())
                .collect();
            if keys.is_empty() {
                return;
            }
            history_signal.set(Vec::new());
            rows_signal.set(
                rows_signal
                    .get()
                    .into_iter()
                    .filter(|row| row.kind != "history")
                    .collect(),
            );
            let server_value = server.get();
            let token_value = token.get();
            spawn(async move {
                let body = serde_json::json!({ "keys": keys }).to_string();
                let _ = api(
                    &server_value,
                    &token_value,
                    "/api/playrecords",
                    "DELETE",
                    Some(body),
                    true,
                )
                .await;
            });
        }
    };

    let run_search = {
        let server = server.clone();
        let token = token.clone();
        let query = search_query.clone();
        let state = search_state.clone();
        let running = search_running.clone();
        move || {
            let value = query.get().trim().to_owned();
            if value.is_empty() {
                return;
            }
            let _ = Storage::set_item("last_search", &value);
            if let Some(mut snapshot) = search::cache_get(&value) {
                snapshot.cached = true;
                state.set(snapshot);
                running.set(false);
                return;
            }
            state.set(search::SearchSnapshot {
                query: value.clone(),
                ..Default::default()
            });
            running.set(true);
            let started = std::time::Instant::now();
            let handle = search::start(&server.get(), &token.get(), &value);
            let state = state.clone();
            let running = running.clone();
            let cache_key = value.clone();
            spawn(async move {
                loop {
                    timer(Duration::from_millis(120)).await;
                    let messages = handle.drain();
                    let mut snapshot = state.get();
                    if snapshot.query != cache_key {
                        break;
                    }
                    let mut finished = false;
                    for message in messages {
                        match message {
                            search::Msg::Start(total) => snapshot.total = total,
                            search::Msg::Group(group) => {
                                snapshot.done += 1;
                                if snapshot.first_ms == 0 && !group.items.is_empty() {
                                    snapshot.first_ms = started.elapsed().as_millis() as u64;
                                }
                                snapshot.groups.push(group);
                            }
                            search::Msg::Complete => {
                                snapshot.complete = true;
                                snapshot.done = snapshot.total.max(snapshot.done);
                                snapshot.total_ms = started.elapsed().as_millis() as u64;
                                finished = true;
                            }
                            search::Msg::Failed(reason) => {
                                snapshot.error = reason;
                                snapshot.complete = true;
                                finished = true;
                            }
                        }
                    }
                    state.set(snapshot.clone());
                    if finished {
                        running.set(false);
                        search::cache_put(&cache_key, &snapshot);
                        break;
                    }
                }
            });
        }
    };

    let server_input = server.clone();
    let user_input = username.clone();
    let password_input = password.clone();
    let login_page = page.clone();
    let busy_login = busy.clone();
    let login_message = message.clone();
    let login_error = error.clone();

    let login_action = {
        let server = server.clone();
        let username = username.clone();
        let password = password.clone();
        let token = token.clone();
        let page = page.clone();
        let busy = busy.clone();
        let message = message.clone();
        let error = error.clone();
        move || {
            if busy.get() {
                return;
            }
            busy.set(true);
            message.set("登录中…".to_owned());
            error.set(String::new());
            let server_value = server.get();
            let user_value = username.get();
            let password_value = password.get();
            let token = token.clone();
            let page = page.clone();
            let busy = busy.clone();
            let message = message.clone();
            let error = error.clone();
            spawn(async move {
                match login(&server_value, &user_value, &password_value).await {
                    Ok(value) => {
                        save_session(&server_value, &user_value, &value);
                        token.set(value);
                        page.set("home");
                        message.set("登录成功".to_owned());
                    }
                    Err(reason) => {
                        error.set(reason);
                        message.set(String::new());
                    }
                }
                busy.set(false);
            });
        }
    };

    let self_check = {
        let rows = rows.clone();
        let proxy_port = proxy_port.clone();
        move || {
            let (count, hit, miss, fail) = proxy::image_stats();
            let _ = Storage::set_item(
                "imgstats",
                format!(
                    "count={count} hit={hit} miss={miss} fail={fail} rows={} port={}",
                    rows.get().len(),
                    proxy_port.get()
                ),
            );
        }
    };
    let logout = {
        let token = token.clone();
        let page = page.clone();
        let message = message.clone();
        let proxy_port = proxy_port.clone();
        let modules = modules.clone();
        move || {
            clear_session();
            token.set(String::new());
            proxy_port.set(0);
            modules.set(Vec::new());
            page.set("login");
            message.set("已退出登录".to_owned());
        }
    };
    let refresh = load_home.clone();
    let home_page = page.clone();
    let detail_page = page.clone();
    let browse_page = page.clone();
    let play_page = page.clone();

    let dark_for_ui = dark.clone();
    let port_for_ui = proxy_port.clone();

    view()
        .flex_1()
        .bg(move || theme().background)
        .text_color(move || theme().foreground)
        .p_safe_area()
        .child(
            view()
                .flex_1()
                .child(when(
                    {
                        let page = page.clone();
                        move || page.get() != "search"
                    },
                    {
                        let scale = scale.clone();
                        let dark = dark_for_ui.clone();
                        let toggle = toggle_theme.clone();
                        let page = page.clone();
                        let username = username.clone();
                        let self_check = self_check.clone();
                        move || {
                            top_bar(
                                scale.read_only(),
                                dark.read_only(),
                                toggle.clone(),
                                page.clone(),
                                username.clone(),
                                self_check.clone(),
                            )
                        }
                    },
                ))
                .child(when(
                    {
                        let page = login_page.clone();
                        move || page.get() == "login"
                    },
                    move || {
                        view()
                            .p_4()
                            .gap_2()
                            .child(
                                input()
                                    .value({
                                        let server = server_input.clone();
                                        move || server.get()
                                    })
                                    .on_change({
                                        let server = server_input.clone();
                                        move |value: &str| server.set(value.to_owned())
                                    })
                                    .placeholder("服务器地址"),
                            )
                            .child(
                                input()
                                    .value({
                                        let user = user_input.clone();
                                        move || user.get()
                                    })
                                    .on_change({
                                        let user = user_input.clone();
                                        move |value: &str| user.set(value.to_owned())
                                    })
                                    .placeholder("用户名"),
                            )
                            .child(
                                input()
                                    .value({
                                        let password = password_input.clone();
                                        move || password.get()
                                    })
                                    .masked(true)
                                    .on_change({
                                        let password = password_input.clone();
                                        move |value: &str| password.set(value.to_owned())
                                    })
                                    .placeholder("密码"),
                            )
                            .child(
                                view()
                                    .px_4()
                                    .py_3()
                                    .rounded(px(8.0))
                                    .bg(move || theme().primary)
                                    .on_press({
                                        let login = login_action.clone();
                                        move || login()
                                    })
                                    .child(
                                        text({
                                            let busy = busy_login.clone();
                                            move || {
                                                if busy.get() {
                                                    "登录中…".to_owned()
                                                } else {
                                                    "登录".to_owned()
                                                }
                                            }
                                        })
                                        .text_color(move || theme().primary_foreground),
                                    ),
                            )
                            .child(
                                text({
                                    let message = login_message.clone();
                                    move || message.get()
                                })
                                .text_color(move || theme().muted_foreground),
                            )
                            .child(
                                text({
                                    let error = login_error.clone();
                                    move || error.get()
                                })
                                .text_color(rgb(0xcc2222)),
                            )
                    },
                ))
                .child(when(
                    {
                        let page = home_page.clone();
                        move || page.get() == "home"
                    },
                    {
                        let loading = home_loading.clone();
                        let error = error.clone();
                        let rows = rows.clone();
                        let scale = scale.clone();
                        let port = port_for_ui.clone();
                        let server_value = server.clone();
                        let refreshing = refreshing.clone();
                        let refresh = refresh.clone();
                        let open = open_detail.clone();
                        let browse = open_browse.clone();
                        move || {
                            let sc = scale.get();
                            view()
                                .flex_1()
                                .child(
                                    scroll()
                                        .flex_1()
                                        .refreshing({
                                            let refreshing = refreshing.clone();
                                            move || refreshing.get()
                                        })
                                        .on_refresh({
                                            let refresh = refresh.clone();
                                            let refreshing = refreshing.clone();
                                            move || {
                                                refreshing.set(true);
                                                refresh();
                                                refreshing.set(false);
                                            }
                                        })
                                        .child(
                                            view()
                                                .gap_5()
                                                .pb_8()
                                                .child(
                                                    view()
                                                        .flex_row()
                                                        .items_center()
                                                        .justify_end()
                                                        .gap(px(8.0 * sc))
                                                        .px(px(12.0 * sc))
                                                        .child(quick_icon(ICON_LINK, sc, {
                                                            let page = home_page.clone();
                                                            move || page.set("play")
                                                        }))
                                                        .child(quick_icon(ICON_LIST, sc, {
                                                            let page = home_page.clone();
                                                            move || page.set("search")
                                                        })),
                                                )
                                                .child(when(
                                                    {
                                                        let loading = loading.clone();
                                                        move || loading.get()
                                                    },
                                                    || {
                                                        text("加载中…")
                                                            .px_4()
                                                            .text_color(move || theme().muted_foreground)
                                                    },
                                                ))
                                                .child(when(
                                                    {
                                                        let error = error.clone();
                                                        move || !error.get().is_empty()
                                                    },
                                                    {
                                                        let error = error.clone();
                                                        move || {
                                                            text({
                                                                let error = error.clone();
                                                                move || error.get()
                                                            })
                                                            .px_4()
                                                            .text_color(rgb(0xcc2222))
                                                        }
                                                    },
                                                ))
                                                .child(each(
                                                    {
                                                        let rows = rows.clone();
                                                        move || rows.get()
                                                    },
                                                    |row: &Row| row.id.clone(),
                                                    {
                                                        let port = port.clone();
                                                        let scale = scale.clone();
                                                        let open = open.clone();
                                                        let browse = browse.clone();
                                                        let site_signal = server_value.clone();
                                                        let clear_records = clear_records.clone();
                                                        move |row: ReadSignal<Row>, _index| {
                                                            let kind = row.get().title.clone();
                                                            let browse = browse.clone();
                                                            row_view(
                                                                row,
                                                                port.get(),
                                                                scale.get(),
                                                                site_signal.get(),
                                                                open.clone(),
                                                                move || browse(kind.clone()),
                                                                clear_records.clone(),
                                                            )
                                                        }
                                                    },
                                                )),
                                        ),
                                )
                        }
                    },
                ))
                .child(when(
                    {
                        let page = detail_page.clone();
                        move || page.get() == "detail"
                    },
                    {
                        let details = details.clone();
                        let port = port_for_ui.clone();
                        let site = server.clone();
                        let scale = scale.clone();
                        let back = detail_back.clone();
                        move || detail::view_detail(details.clone(), back.clone(), port.get(), site.get(), scale.get())
                    },
                ))
                .child(when(
                    {
                        let page = page.clone();
                        move || page.get() == "search"
                    },
                    {
                        let back = back_home.clone();
                        let query = search_query.clone();
                        let state = search_state.clone();
                        let running = search_running.clone();
                        let run = run_search.clone();
                        let open = open_search_detail.clone();
                        let port = port_for_ui.clone();
                        let site_signal = server.clone();
                        let scale = scale.clone();
                        move || {
                            let sc = scale.get();
                            search_view(
                                query.clone(),
                                state.clone(),
                                running.clone(),
                                run.clone(),
                                back.clone(),
                                port.get(),
                                site_signal.get(),
                                sc,
                                open.clone(),
                            )
                        }
                    },
                ))
                .child(when(
                    {
                        let page = browse_page.clone();
                        move || page.get() == "browse"
                    },
                    {
                        let browse = browse.clone();
                        let open = open_detail.clone();
                        let port = proxy_port.clone();
                        let site = server.clone();
                        let scale = scale.clone();
                        move || browse::page(browse.clone(), open.clone(), {
                            let back = back_home.clone(); move || back()
                        }, port.get(), site.get(), scale.get())
                    },
                ))
                .child(when(
                    {
                        let page = play_page.clone();
                        move || page.get() == "play"
                    },
                    {
                        let port = port_for_ui.clone();
                        let selection = details.selection.clone();
                        let page = page.clone();
                        let back = move || page.set("detail");
                        move || {
                            view()
                                .flex_1()
                                .gap_2()
                                .child(
                                    view()
                                        .flex_row()
                                        .gap_2()
                                        .child(
                                            view()
                                                .px_3()
                                                .py_2()
                                                .rounded(px(8.0))
                                                .bg(move || theme().muted)
                                                .on_press({
                                                    let back = back.clone();
                                                    move || back()
                                                })
                                                .child(text("返回")),
                                        )
                                        .child(
                                            text({
                                                let selection = selection.clone();
                                                move || { let selected = selection.get(); format!("{} · {} · 第 {} 集", selected.info.title, selected.info.source_name, selected.index + 1) }
                                            })
                                            .text_color(move || theme().muted_foreground),
                                        ),
                                )
                                .child(
                                    video({
                                        let port = port.clone();
                                        let selection = selection.clone();
                                        move || {
                                            if port.get() == 0 || selection.get().url.is_empty() {
                                                String::new()
                                            } else {
                                                format!(
                                                    "http://127.0.0.1:{}/a.m3u8?u={}",
                                                    port.get(),
                                                    urlencoding::encode(&selection.get().url)
                                                )
                                            }
                                        }
                                    })
                                    .flex_1()
                                    .controls(true),
                                )
                        }
                    },
                )),
        )
        .child(when(
            {
                let page = page.clone();
                move || page.get() != "login"
            },
            {
                let scale = scale.clone();
                let page = page.clone();
                let load = load_browse.clone();
                let title = browse.filters.clone();
                move || {
                    let active = match if page.get() == "browse" { title.get().kind } else { page.get().to_owned() }.as_str() {
                        "movie" => "movie",
                        "tv" => "tv",
                        "anime" => "anime",
                        "show" => "show",
                        _ => "home",
                    };
                    bottom_bar(scale.read_only(), active, {
                        let page = page.clone();
                        let load = load.clone();
                        move |key| {
                            if key == "home" { page.set("home"); }
                            else { load(key.to_owned()); }
                        }
                    })
                }
            },
        ))
}

fn row_view(
    row: ReadSignal<Row>,
    port: u16,
    scale: f32,
    site: String,
    open: impl Fn(Movie) + Clone + 'static,
    more: impl Fn() + Clone + 'static,
    clear: impl Fn() + Clone + 'static,
) -> impl IntoElement {
    let pad = px(16.0 * scale);
    let kind_row = row.clone();
    view()
        .gap(px(16.0 * scale))
        .child(
            view()
                .flex_row()
                .items_center()
                .justify_between()
                .px(pad)
                .child(
                    text(move || row.get().title.clone())
                        .text_size(px(20.0 * scale))
                        .font_weight(nsh_core::element::FontWeight::BOLD),
                )
                .child(when(
                    move || kind_row.get().kind == "history",
                    {
                        let clear_outer = clear.clone();
                        move || {
                            let clear = clear_outer.clone();
                            view()
                                .flex_row()
                                .items_center()
                                .gap(px(4.0 * scale))
                                .on_press(move || clear())
                                .child(
                                    text("清空")
                                        .text_size(px(14.0 * scale))
                                        .text_color(move || theme().muted_foreground),
                                )
                                .child(
                                    image(ICON_CHEVRON)
                                        .w(px(16.0 * scale))
                                        .h(px(16.0 * scale))
                                        .tint_color(move || theme().muted_foreground),
                                )
                        }
                    },
                ))
                .child(when(
                    move || kind_row.get().kind != "history",
                    {
                        let more_outer = more.clone();
                        move || {
                            let more = more_outer.clone();
                            view().flex_row().items_center().on_press(move || more()).child(
                                text("查看更多")
                                    .text_size(px(14.0 * scale))
                                    .text_color(move || theme().muted_foreground),
                            )
                        }
                    },
                )),
        )
        .child(
            scroll().horizontal().child(
                view()
                    .flex_row()
                    .gap(px(8.0 * scale))
                    .px(pad)
                    .child(each(
                        move || row.get().items.clone(),
                        |movie: &Movie| movie.id.clone(),
                        {
                            let site = site.clone();
                            let kind_row = kind_row.clone();
                            move |movie: ReadSignal<Movie>, _index| {
                                let open = open.clone();
                                let watch = open.clone();
                                let movie = movie.get();
                                let kind_row = kind_row.clone();
                                view().child(
                                    when(
                                        move || kind_row.get().kind == "history",
                                        {
                                            let movie = movie.clone();
                                            let site = site.clone();
                                            let watch_outer = watch.clone();
                                            move || {
                                                let watch = watch_outer.clone();
                                                history_card(
                                                    movie.clone(),
                                                    port,
                                                    scale,
                                                    site.clone(),
                                                    move |value| watch(value),
                                                )
                                            }
                                        },
                                    )
                                    .otherwise({
                                        let movie = movie.clone();
                                        let site = site.clone();
                                        let open_outer = open.clone();
                                        move || {
                                            let open = open_outer.clone();
                                            card(
                                                movie.clone(),
                                                port,
                                                scale,
                                                site.clone(),
                                                move |value| open(value),
                                            )
                                        }
                                    }),
                                )
                            }
                        },
                    )),
            ),
        )
}

#[allow(clippy::too_many_arguments)]
fn search_view(
    query: Signal<String>,
    state: Signal<search::SearchSnapshot>,
    running: Signal<bool>,
    run: impl Fn() + Clone + 'static,
    back: impl Fn() + Clone + 'static,
    port: u16,
    site: String,
    scale: f32,
    open: impl Fn(search::SearchItem) + Clone + 'static,
) -> impl IntoElement {
    let sc = scale;
    // 390dp wide pages: 16dp side padding, three 2:3 posters with 8dp gaps.
    let content = 390.0 * sc - 32.0 * sc;
    let card_w = (content - 16.0 * sc) / 3.0;
    let rows_state = state.clone();
    let rows_site = site.clone();
    view()
        .flex_1()
        .child(
            view()
                .flex_row()
                .items_center()
                .gap(px(8.0 * sc))
                .px(px(16.0 * sc))
                .pt(px(8.0 * sc))
                .child(
                    view()
                        .w(px(36.0 * sc))
                        .h(px(36.0 * sc))
                        .rounded(px(8.0 * sc))
                        .items_center()
                        .justify_center()
                        .on_press(move || back())
                        .child(
                            image(ICON_CHEVRON)
                                .w(px(20.0 * sc))
                                .h(px(20.0 * sc))
                                .rotate(deg(180.0))
                                .tint_color(move || theme().foreground),
                        ),
                )
                .child(
                    view()
                        .flex_1()
                        .h(px(48.0 * sc))
                        .flex_row()
                        .items_center()
                        .gap(px(8.0 * sc))
                        .px(px(12.0 * sc))
                        .rounded(px(8.0 * sc))
                        .bg(move || theme().muted)
                        .child(
                            image(ICON_SEARCH)
                                .w(px(20.0 * sc))
                                .h(px(20.0 * sc))
                                .tint_color(move || theme().muted_foreground),
                        )
                        .child(
                            input()
                                .flex_1()
                                .h(px(48.0 * sc))
                                .value({
                                    let query = query.clone();
                                    move || query.get()
                                })
                                .on_change({
                                    let query = query.clone();
                                    move |value: &str| query.set(value.to_owned())
                                })
                                .on_submit({
                                    let run = run.clone();
                                    move |_value: &str| run()
                                })
                                .placeholder("搜索电影、剧集、动漫…"),
                        ),
                )
                .child(
                    view()
                        .h(px(48.0 * sc))
                        .px(px(14.0 * sc))
                        .rounded(px(8.0 * sc))
                        .items_center()
                        .justify_center()
                        .bg(BRAND)
                        .on_press({
                            let run = run.clone();
                            move || run()
                        })
                        .child(
                            text("搜索")
                                .text_size(px(14.0 * sc))
                                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                                .text_color(rgb(0xffffff)),
                        ),
                ),
        )
        // Progress is the only line that shows; once the stream ends it is gone.
        .child(when(
            {
                let state = state.clone();
                let running = running.clone();
                move || running.get() && state.get().error.is_empty()
            },
            {
                let state = state.clone();
                move || {
                    let snapshot = state.get();
                    let label = if snapshot.total == 0 {
                        "搜索中…".to_owned()
                    } else {
                        format!("搜索中… {}/{} 个源", snapshot.done, snapshot.total)
                    };
                    text(label)
                        .px(px(16.0 * sc))
                        .pt(px(8.0 * sc))
                        .text_size(px(12.0 * sc))
                        .text_color(move || theme().muted_foreground)
                }
            },
        ))
        .child(
            view().flex_1().child(
                scroll().flex_1().child(
                    view()
                        .gap(px(12.0 * sc))
                        .py(px(12.0 * sc))
                        .pb_8()
                        .child(when(
                            {
                                let state = state.clone();
                                move || !state.get().error.is_empty()
                            },
                            {
                                let state = state.clone();
                                let run = run.clone();
                                move || {
                                    let message = state.get().error;
                                    let retry = run.clone();
                                    view()
                                        .items_center()
                                        .gap(px(12.0 * sc))
                                        .py(px(48.0 * sc))
                                        .child(
                                            text(message)
                                                .text_size(px(14.0 * sc))
                                                .text_color(move || theme().muted_foreground),
                                        )
                                        .child(
                                            view()
                                                .px(px(16.0 * sc))
                                                .py(px(8.0 * sc))
                                                .rounded(px(8.0 * sc))
                                                .bg(BRAND)
                                                .on_press(move || retry())
                                                .child(
                                                    text("重试")
                                                        .text_size(px(14.0 * sc))
                                                        .text_color(rgb(0xffffff)),
                                                ),
                                        )
                                }
                            },
                        ))
                        .child(when(
                            {
                                let state = state.clone();
                                let running = running.clone();
                                move || {
                                    let snapshot = state.get();
                                    !running.get()
                                        && snapshot.error.is_empty()
                                        && snapshot.groups.is_empty()
                                }
                            },
                            {
                                let query = query.clone();
                                let state = state.clone();
                                move || {
                                    let snapshot = state.get();
                                    let hint = if !snapshot.complete && snapshot.groups.is_empty() {
                                        if query.get().trim().is_empty() {
                                            "输入关键词开始搜索"
                                        } else {
                                            "按「搜索」开始查找"
                                        }
                                    } else {
                                        "没有找到相关内容，换个关键词试试"
                                    };
                                    view()
                                        .items_center()
                                        .gap(px(8.0 * sc))
                                        .py(px(64.0 * sc))
                                        .child(
                                            image(ICON_SEARCH)
                                                .w(px(40.0 * sc))
                                                .h(px(40.0 * sc))
                                                .tint_color(move || theme().border),
                                        )
                                        .child(
                                            text(hint)
                                                .text_size(px(14.0 * sc))
                                                .text_color(move || theme().muted_foreground),
                                        )
                                }
                            },
                        ))
                        .child(each(
                            move || search_grid_rows(&rows_state.get()),
                            |row: &Vec<search::SearchItem>| {
                                row.first()
                                    .map(|item| format!("{}:{}", item.source, item.id))
                                    .unwrap_or_default()
                            },
                            {
                                let site = rows_site.clone();
                                let open = open.clone();
                                move |row: ReadSignal<Vec<search::SearchItem>>, _index| {
                                    view()
                                        .flex_row()
                                        .gap(px(8.0 * sc))
                                        .px(px(16.0 * sc))
                                        .child(each(
                                            move || row.get(),
                                            |item: &search::SearchItem| {
                                                format!("{}:{}", item.source, item.id)
                                            },
                                            {
                                                let site = site.clone();
                                                let open = open.clone();
                                                move |item: ReadSignal<search::SearchItem>, _index| {
                                                    search_grid_card(
                                                        item,
                                                        port,
                                                        sc,
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
                ),
            ),
        )
}

/// Flattens the per-source groups into poster rows, one entry per title.
fn search_grid_rows(snapshot: &search::SearchSnapshot) -> Vec<Vec<search::SearchItem>> {
    let mut seen: Vec<String> = Vec::new();
    let mut items: Vec<search::SearchItem> = Vec::new();
    for group in &snapshot.groups {
        for item in &group.items {
            let key = format!("{}|{}", item.title.trim(), item.year.trim());
            if seen.contains(&key) {
                continue;
            }
            seen.push(key);
            items.push(item.clone());
        }
    }
    items.chunks(3).map(|chunk| chunk.to_vec()).collect()
}

fn search_grid_card(
    item: ReadSignal<search::SearchItem>,
    port: u16,
    scale: f32,
    card_w: f32,
    site: String,
    open: impl Fn(search::SearchItem) + 'static,
) -> impl IntoElement {
    let poster_site = site;
    let selected = item.clone();
    view()
        .on_press(move || open(selected.get()))
        .w(px(card_w))
        .gap(px(8.0 * scale))
        .child(
            view()
                .w(px(card_w))
                .aspect_ratio(2.0 / 3.0)
                .rounded(px(8.0 * scale))
                .overflow_hidden()
                .bg(move || theme().muted)
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
                        let poster_site = poster_site.clone();
                        move || image_url(port, &poster_site, &item.get().poster)
                    })
                    .absolute()
                    .inset_0()
                    .w_full()
                    .h_full(),
                ),
        )
        .child(
            text(move || item.get().title.clone())
                .text_size(px(14.0 * scale))
                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                .number_of_lines(1),
        )
}

fn quick_icon(
    icon: ImageAsset,
    scale: f32,
    on_press: impl Fn() + 'static,
) -> impl IntoElement {
    view()
        .w(px(36.0 * scale))
        .h(px(36.0 * scale))
        .rounded(px(8.0 * scale))
        .items_center()
        .justify_center()
        .on_press(on_press)
        .child(
            image(icon)
                .w(px(20.0 * scale))
                .h(px(20.0 * scale))
                .tint_color(move || theme().foreground),
        )
}

fn page_get(page: &Signal<&'static str>) -> &'static str {
    page.get()
}

fn card(
    movie: Movie,
    port: u16,
    scale: f32,
    site: String,
    on_press: impl Fn(Movie) + 'static,
) -> impl IntoElement {
    let poster = movie.poster.clone();
    let rate = movie.rate.clone();
    let progress = movie.progress;
    let width = px(96.0 * scale);
    view()
        .w(width)
        .gap(px(8.0 * scale))
        .on_press({
            let movie = movie.clone();
            move || on_press(movie.clone())
        })
        .child(
            view()
                .w(width)
                .aspect_ratio(2.0 / 3.0)
                .rounded(px(8.0 * scale))
                .overflow_hidden()
                .bg(move || theme().muted)
                .child(
                    image(image_url(port, &site, &poster))
                        .absolute()
                        .inset_0()
                        .w_full()
                        .h_full(),
                )
                .child(
                    when(
                        {
                            let rate = rate.clone();
                            move || !rate.is_empty()
                        },
                        {
                            let rate = rate.clone();
                            move || {
                                let value = rate.clone();
                                let (background, ink) = rating_style(&value);
                                view()
                                    .absolute()
                                    .top(px(6.0 * scale))
                                    .left(px(6.0 * scale))
                                    .px(px(5.0 * scale))
                                    .py(px(2.0 * scale))
                                    .bg(background)
                                    .rounded(px(4.0 * scale))
                                    .child(
                                        text(value)
                                            .text_size(px(11.0 * scale))
                                            .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                                            .text_color(ink),
                                    )
                            }
                        },
                    ),
                )
                .child(when(
                    move || progress > 0.0,
                    move || {
                        view()
                            .absolute()
                            .bottom_0()
                            .left_0()
                            .w(px(96.0 * scale * progress as f32))
                            .h(px(3.0 * scale))
                            .bg(BRAND)
                    },
                )),
        )
        .child(
            text(movie.title.clone())
                .text_size(px(14.0 * scale))
                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                .line_height(px(20.0 * scale))
                .number_of_lines(1),
        )
}

/// The web's continue-watching card: a 180x120 still with an information bar.
fn history_card(
    movie: Movie,
    port: u16,
    scale: f32,
    site: String,
    on_press: impl Fn(Movie) + 'static,
) -> impl IntoElement {
    let poster = movie.poster.clone();
    let progress = movie.progress as f32;
    let width = px(180.0 * scale);
    view()
        .w(width)
        .on_press({
            let movie = movie.clone();
            move || on_press(movie.clone())
        })
        .child(
            view()
                .w(width)
                .aspect_ratio(3.0 / 2.0)
                .rounded(px(8.0 * scale))
                .overflow_hidden()
                .bg(move || theme().muted)
                .child(
                    image(image_url(port, &site, &poster))
                        .absolute()
                        .inset_0()
                        .w_full()
                        .h_full(),
                )
                .child(
                    view()
                        .absolute()
                        .bottom_0()
                        .left_0()
                        .w_full()
                        .bg(rgba(0x000000A6))
                        .px(px(6.0 * scale))
                        .pt(px(14.0 * scale))
                        .pb(px(5.0 * scale))
                        .gap(px(2.0 * scale))
                        .child(
                            text(movie.title.clone())
                                .text_size(px(12.0 * scale))
                                .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                                .text_color(rgb(0xffffff))
                                .number_of_lines(1),
                        )
                        .child(
                            view()
                                .flex_row()
                                .items_center()
                                .justify_between()
                                .child(
                                    text(movie.elapsed.clone())
                                        .text_size(px(10.0 * scale))
                                        .text_color(rgba(0xFFFFFFB3)),
                                )
                                .child(
                                    view()
                                        .px(px(3.0 * scale))
                                        .rounded(px(2.0 * scale))
                                        .border(px(1.0))
                                        .border_color(rgba(0xFFFFFF80))
                                        .child(
                                            text(movie.source_name.clone())
                                                .text_size(px(10.0 * scale))
                                                .text_color(rgba(0xFFFFFFD9)),
                                        ),
                                ),
                        ),
                )
                .child(when(
                    move || progress > 0.0,
                    move || {
                        view()
                            .absolute()
                            .bottom_0()
                            .left_0()
                            .w(px(180.0 * scale * progress))
                            .h(px(2.0 * scale))
                            .bg(BRAND)
                    },
                )),
        )
}

#[allow(clippy::too_many_arguments)]
fn top_bar(
    scale: ReadSignal<f32>,
    dark: ReadSignal<bool>,
    toggle: impl Fn() + 'static,
    page: Signal<&'static str>,
    username: Signal<String>,
    self_check: impl Fn() + 'static,
) -> impl IntoElement {
    view()
        .h(move || px(48.0 * scale.get()))
        .flex_row()
        .items_center()
        .px(move || px(16.0 * scale.get()))
        .gap(move || px(4.0 * scale.get()))
        .bg(move || theme().background)
        .border_b(px(1.0))
        .border_color(move || theme().border)
        .child(
            view()
                .w(move || px(40.0 * scale.get()))
                .h(move || px(40.0 * scale.get()))
                .items_center()
                .justify_center()
                .on_press({
                    let page = page.clone();
                    move || page.set("search")
                })
                .child(
                    image(ICON_SEARCH)
                        .w(move || px(24.0 * scale.get()))
                        .h(move || px(24.0 * scale.get()))
                        .tint_color(move || theme().foreground),
                ),
        )
        .child(
            view()
                .flex_1()
                .items_center()
                .on_long_press(self_check)
                .child(
                    text("MoonTVPlus")
                        .text_size(move || px(24.0 * scale.get()))
                        .font_weight(nsh_core::element::FontWeight::BOLD)
                        .text_color(BRAND),
                ),
        )
        .child(
            view()
                .w(move || px(40.0 * scale.get()))
                .h(move || px(40.0 * scale.get()))
                .items_center()
                .justify_center()
                .on_press(toggle)
                .child(
                    image(move || if dark.get() { ICON_SUN } else { ICON_MOON })
                        .w(move || px(24.0 * scale.get()))
                        .h(move || px(24.0 * scale.get()))
                        .tint_color(move || theme().foreground),
                ),
        )
        .child(
            view()
                .w(move || px(40.0 * scale.get()))
                .h(move || px(40.0 * scale.get()))
                .rounded(move || px(20.0 * scale.get()))
                .items_center()
                .justify_center()
                .bg(move || theme().muted)
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
                    .text_size(move || px(14.0 * scale.get()))
                    .font_weight(nsh_core::element::FontWeight::SEMIBOLD)
                    .text_color(move || theme().foreground),
                ),
        )
}

fn bottom_bar(
    scale: ReadSignal<f32>,
    active: &'static str,
    select: impl Fn(&'static str) + Clone + 'static,
) -> impl IntoElement {
    let items: [(&'static str, &'static str, ImageAsset); 5] = [
        ("home", "首页", ICON_HOME),
        ("movie", "电影", ICON_FILM),
        ("tv", "剧集", ICON_TV),
        ("anime", "动漫", ICON_CAT),
        ("show", "综艺", ICON_CLOVER),
    ];
    let mut bar = view()
        .h(move || px(56.0 * scale.get()))
        .flex_row()
        .bg(move || theme().background)
        .border_t(px(1.0))
        .border_color(move || theme().border);
    for (key, label, icon) in items {
        let select = select.clone();
        let is_active = key == active;
        bar = bar.child(
            view()
                .flex_1()
                .items_center()
                .justify_center()
                .gap(move || px(2.0 * scale.get()))
                .on_press(move || select(key))
                .child(
                    image(icon)
                        .w(move || px(24.0 * scale.get()))
                        .h(move || px(24.0 * scale.get()))
                        .tint_color(move || {
                            if is_active {
                                BRAND
                            } else {
                                theme().muted_foreground
                            }
                        }),
                )
                .child(
                    text(label)
                        .text_size(move || px(12.0 * scale.get()))
                        .text_color(move || {
                            if is_active {
                                BRAND
                            } else {
                                theme().muted_foreground
                            }
                        }),
                ),
        );
    }
    bar
}
