//! MoonTVPlus pure-Rust app: batch 0 (shell, session, proxy lifecycle).

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

#[native_module("moontvplus-rust/native")]
fn exports(registry: &mut Registry) {
    registry.register::<App>();
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

    let server_input = server.clone();
    let user_input = username.clone();
    let password_input = password.clone();
    let login_page = page.clone();
    let home_page = page.clone();
    let play_page = page.clone();
    let busy_login = busy.clone();
    let login_message = message.clone();
    let login_error = error.clone();
    let login_token = token.clone();

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

    let home_logout = {
        let token = token.clone();
        let page = page.clone();
        let message = message.clone();
        let proxy_port = proxy_port.clone();
        move || {
            clear_session();
            token.set(String::new());
            proxy_port.set(0);
            page.set("login");
            message.set("已退出登录".to_owned());
        }
    };
    let home_probe = {
        let server = server.clone();
        let token = token.clone();
        let message = message.clone();
        let error = error.clone();
        let page = page.clone();
        let proxy_port = proxy_port.clone();
        move || {
            let server_value = server.get();
            let token_value = token.get();
            let token = token.clone();
            let message = message.clone();
            let error = error.clone();
            let page = page.clone();
            let proxy_port = proxy_port.clone();
            spawn(async move {
                match api(&server_value, &token_value, "/api/favorites", "GET", None, true).await {
                    Ok((status, _body, next)) => {
                        if let Some(next) = next {
                            token.set(next.clone());
                            let _ = Storage::set_item("token", &next);
                        }
                        message.set(format!("鉴权自检 HTTP {status}"));
                    }
                    Err(ApiError::AuthExpired) => {
                        clear_session();
                        token.set(String::new());
                        proxy_port.set(0);
                        page.set("login");
                        error.set("登录已过期，请重新登录".to_owned());
                    }
                    Err(ApiError::Network(reason)) => {
                        error.set(format!("网络错误：{reason}（未清除登录）"));
                    }
                }
            });
        }
    };
    let open_play = {
        let server = server.clone();
        let token = token.clone();
        let page = page.clone();
        let error = error.clone();
        let proxy_port = proxy_port.clone();
        move || {
            match proxy::ensure(server.get(), token.get()) {
                Ok(port) => {
                    proxy_port.set(port);
                    page.set("play");
                    error.set(String::new());
                }
                Err(reason) => error.set(format!("代理启动失败：{reason}")),
            }
        }
    };
    let back_home = {
        let page = page.clone();
        move || page.set("home")
    };

    view()
        .flex_1()
        .bg(move || theme().background)
        .text_color(move || theme().foreground)
        .p_safe_area()
        .child(
            view()
                .flex_1()
                .p_4()
                .gap_3()
                .child(
                    view()
                        .flex_row()
                        .items_center()
                        .justify_between()
                        .child(text("MoonTVPlus Rust").text_size(px(22.0)))
                        .child(
                            view()
                                .px_3()
                                .py_1()
                                .rounded(px(8.0))
                                .bg(move || theme().muted)
                                .on_press({
                                    let toggle = toggle_theme.clone();
                                    move || toggle()
                                })
                                .child(text(move || if dark.get() { "深色" } else { "浅色" })),
                        ),
                )
                .child(when(
                    {
                        let page = login_page.clone();
                        move || page.get() == "login"
                    },
                    move || {
                    view()
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
                }))
                .child(when(
                    {
                        let page = home_page.clone();
                        move || page.get() == "home"
                    },
                    move || {
                    view()
                        .gap_3()
                        .child(
                            text({
                                let user = username.clone();
                                let server = server.clone();
                                move || format!("{} @ {}", user.get(), server.get())
                            })
                            .text_color(move || theme().muted_foreground),
                        )
                        .child(
                            view()
                                .flex_row()
                                .gap_2()
                                .child(
                                    view()
                                        .px_3()
                                        .py_2()
                                        .rounded(px(8.0))
                                        .bg(move || theme().primary)
                                        .on_press({
                                            let open = open_play.clone();
                                            move || open()
                                        })
                                        .child(
                                            text("播放测试")
                                                .text_color(move || theme().primary_foreground),
                                        ),
                                )
                                .child(
                                    view()
                                        .px_3()
                                        .py_2()
                                        .rounded(px(8.0))
                                        .bg(move || theme().muted)
                                        .on_press({
                                            let probe = home_probe.clone();
                                            move || probe()
                                        })
                                        .child(text("鉴权自检")),
                                )
                                .child(
                                    view()
                                        .px_3()
                                        .py_2()
                                        .rounded(px(8.0))
                                        .bg(move || theme().muted)
                                        .on_press({
                                            let logout = home_logout.clone();
                                            move || logout()
                                        })
                                        .child(text("退出登录")),
                                ),
                        )
                }))
                .child(when(
                    {
                        let page = play_page.clone();
                        move || page.get() == "play"
                    },
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
                                            let back = back_home.clone();
                                            move || back()
                                        })
                                        .child(text("返回")),
                                )
                                .child(
                                    text({
                                        let port = proxy_port.clone();
                                        move || {
                                            if port.get() > 0 {
                                                format!("代理端口 {}", port.get())
                                            } else {
                                                "无代理".to_owned()
                                            }
                                        }
                                    })
                                    .text_color(move || theme().muted_foreground),
                                ),
                        )
                        .child(
                            view()
                                .flex_1()
                                .rounded(px(8.0))
                                .overflow_hidden()
                                .bg(rgb(0x000000))
                                .child(
                                    video({
                                        let port = proxy_port.clone();
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
                                ),
                        )
                }))
                .child(when(
                    {
                        let page = page.clone();
                        move || page.get() != "login"
                    },
                    move || {
                        view()
                            .gap_1()
                            .child(
                                text({
                                    let message = message.clone();
                                    move || message.get()
                                })
                                .text_color(move || theme().muted_foreground)
                                .text_size(px(13.0)),
                            )
                            .child(
                                text({
                                    let error = error.clone();
                                    move || error.get()
                                })
                                .text_color(rgb(0xcc2222))
                                .text_size(px(13.0)),
                            )
                    },
                ))
        )
}
