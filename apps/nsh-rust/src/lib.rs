//! MoonTVPlus pure-Rust app: batch 1 (home modules, images, continue watching).

mod proxy;

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
const REDBULL: &str = "https://hn.bfvvs.com/play/dJ6PoBvd/index.m3u8";

const ICON_SEARCH: ImageAsset = asset!("icons/search.svg");
const ICON_HOME: ImageAsset = asset!("icons/home.svg");
const ICON_FILM: ImageAsset = asset!("icons/film.svg");
const ICON_TV: ImageAsset = asset!("icons/tv.svg");
const ICON_CAT: ImageAsset = asset!("icons/cat.svg");
const ICON_CLOVER: ImageAsset = asset!("icons/clover.svg");
const ICON_MOON: ImageAsset = asset!("icons/moon.svg");
const ICON_SUN: ImageAsset = asset!("icons/sun.svg");

/// The web layout is drawn for 390dp; each screen scales from that.
const BASE_WIDTH: f32 = 390.0;
/// The web brand green, used for the wordmark, the selected tab and the rating badge.
const BRAND: AdaptiveColor = AdaptiveColor::rgb(0x22C55E, 0x22C55E);

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
            })
        })
        .collect();
    movies.sort_by(|a, b| a.year.cmp(&b.year));
    movies
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
    let modules = signal(Vec::<(String, bool)>::new());
    let home_loading = signal(false);
    let current = signal(Movie::default());
    let browse_title = signal(String::new());
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
        let current = current.clone();
        let page = page.clone();
        move |movie: Movie| {
            current.set(movie);
            page.set("detail");
        }
    };
    let open_browse = {
        let page = page.clone();
        let browse_title = browse_title.clone();
        move |title: String| {
            browse_title.set(title);
            page.set("browse");
        }
    };
    let back_home = {
        let page = page.clone();
        move || page.set("home")
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
                .child(top_bar(
                    scale.read_only(),
                    dark_for_ui.read_only(),
                    toggle_theme.clone(),
                    page.clone(),
                    username.clone(),
                    self_check.clone(),
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
                        let current = current.clone();
                        let port = port_for_ui.clone();
                        let back = back_home.clone();
                        move || {
                            let movie = current.get();
                            view()
                                .flex_1()
                                .p_4()
                                .gap_3()
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
                                        .child(text("详情").text_size(px(18.0))),
                                )
                                .child(
                                    view()
                                        .flex_row()
                                        .gap_3()
                                        .child(
                                            image(image_url(port.get(), "", &movie.poster))
                                                .w(px(96.0))
                                                .aspect_ratio(2.0 / 3.0)
                                                .rounded(px(8.0))
                                                .bg(move || theme().muted),
                                        )
                                        .child(
                                            view()
                                                .flex_1()
                                                .gap_1()
                                                .child(text(movie.title.clone()).text_size(px(18.0)))
                                                .child(
                                                    text(format!("{} {}", movie.year, movie.rate))
                                                        .text_size(px(12.0))
                                                        .text_color(move || theme().muted_foreground),
                                                ),
                                        ),
                                )
                                .child(
                                    text("详情页与选源将在批 4 完成")
                                        .text_color(move || theme().muted_foreground),
                                )
                        }
                    },
                ))
                .child(when(
                    {
                        let page = page.clone();
                        move || page.get() == "search"
                    },
                    {
                        let back = back_home.clone();
                        move || {
                            view().flex_1().p_4().gap_3().child(
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
                                    .child(text("搜索").text_size(px(18.0))),
                            )
                            .child(
                                text("搜索页将在批 2 完成")
                                    .text_color(move || theme().muted_foreground),
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
                        let title = browse_title.clone();
                        let back = back_home.clone();
                        move || {
                            view()
                                .flex_1()
                                .p_4()
                                .gap_3()
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
                                        .child(text({
                                            let title = title.clone();
                                            move || title.get()
                                        }).text_size(px(18.0))),
                                )
                                .child(
                                    text("分类页将在批 3 完成")
                                        .text_color(move || theme().muted_foreground),
                                )
                        }
                    },
                ))
                .child(when(
                    {
                        let page = play_page.clone();
                        move || page.get() == "play"
                    },
                    {
                        let port = port_for_ui.clone();
                        let back = back_home.clone();
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
                                                let port = port.clone();
                                                move || format!("代理 {}", port.get())
                                            })
                                            .text_color(move || theme().muted_foreground),
                                        ),
                                )
                                .child(
                                    video({
                                        let port = port.clone();
                                        move || {
                                            if port.get() == 0 {
                                                String::new()
                                            } else {
                                                format!(
                                                    "http://127.0.0.1:{}/a.m3u8?u={}",
                                                    port.get(),
                                                    urlencoding::encode(REDBULL)
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
                move || {
                    let active = match page.get() {
                        "movie" => "movie",
                        "tv" => "tv",
                        "anime" => "anime",
                        "show" => "show",
                        _ => "home",
                    };
                    bottom_bar(scale.read_only(), active, {
                        let page = page.clone();
                        move |key| {
                            if key == "home" {
                                page.set("home");
                            } else {
                                page.set("browse");
                            }
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
    more: impl Fn() + 'static,
) -> impl IntoElement {
    let pad = px(16.0 * scale);
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
                .child(
                    view()
                        .flex_row()
                        .items_center()
                        .on_press(more)
                        .child(
                            text("查看更多")
                                .text_size(px(14.0 * scale))
                                .text_color(move || theme().muted_foreground),
                        )
                ),
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
                            move |movie: ReadSignal<Movie>, _index| {
                                let open = open.clone();
                                card(
                                    movie.get(),
                                    port,
                                    scale,
                                    site.clone(),
                                    move |value| open(value),
                                )
                            }
                        },
                    )),
            ),
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
                                view()
                                    .absolute()
                                    .top(px(6.0 * scale))
                                    .left(px(6.0 * scale))
                                    .px(px(5.0 * scale))
                                    .py(px(2.0 * scale))
                                    .bg(BRAND)
                                    .rounded(px(4.0 * scale))
                                    .child(
                                        text(rate.clone())
                                            .text_size(px(11.0 * scale))
                                            .text_color(rgb(0xffffff)),
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
