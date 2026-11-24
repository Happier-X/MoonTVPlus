//! MoonTVPlus pure-Rust spike: login, local HLS proxy, Video playback.

mod proxy;

use nsh_base::theme;
use nsh_core::element::input;
use nsh_core::prelude::*;
use nsh_core::storage::Storage;

const DEFAULT_SERVER: &str = "https://tv.370125.xyz";
const TEST_STREAM: &str = "https://hn.bfvvs.com/play/dJ6PoBvd/index.m3u8";

#[native_module("moontvplus-rust/native")]
fn native(registry: &mut Registry) {
    registry.register::<App>();
}

fn login_request(server: &str, user: &str, password: &str) -> HttpRequest {
    let body = format!(
        "{{\"username\":{},\"password\":{}}}",
        serde_json::to_string(user).unwrap_or_default(),
        serde_json::to_string(password).unwrap_or_default()
    );
    HttpRequest::post(format!("{}/api/login", server.trim_end_matches('/')), body)
        .header("Content-Type", "application/json")
        .header("Accept", "*/*")
}

#[component]
pub fn App() -> impl IntoElement {
    let server = signal(Storage::get_item("server").unwrap_or_else(|| DEFAULT_SERVER.to_owned()));
    let user = signal(Storage::get_item("username").unwrap_or_default());
    let password = signal(String::new());
    let token = signal(Storage::get_item("token").unwrap_or_default());
    let page = signal(if Storage::get_item("token").is_some() { "play" } else { "login" });
    let status = signal(String::new());
    let busy = signal(false);
    let url = signal(String::new());
    let position = signal(0.0_f32);
    let duration = signal(0.0_f32);
    let buffering = signal(false);

    let do_login = {
        let server = server.clone();
        let user = user.clone();
        let password = password.clone();
        let token = token.clone();
        let page = page.clone();
        let status = status.clone();
        let busy = busy.clone();
        move || {
            if busy.get() {
                return;
            }
            busy.set(true);
            status.set("登录中…".to_owned());
            let request = login_request(&server.get(), &user.get(), &password.get());
            let token = token.clone();
            let page = page.clone();
            let status = status.clone();
            let busy = busy.clone();
            let server_value = server.get();
            spawn(async move {
                match fetch(request).await {
                    Ok(response) if response.ok() => {
                        match serde_json::from_str::<serde_json::Value>(&response.text()) {
                            Ok(json) => {
                                if let Some(value) = json["token"].as_str() {
                                    token.set(value.to_owned());
                                    let _ = Storage::set_item("token", value);
                                    let _ = Storage::set_item("server", &server_value);
                                    page.set("play");
                                    status.set("已登录".to_owned());
                                } else {
                                    status.set(json["message"].as_str().unwrap_or("登录失败").to_owned());
                                }
                            }
                            Err(error) => status.set(format!("解析失败: {error}")),
                        }
                    }
                    Ok(response) => status.set(format!("HTTP {}", response.status())),
                    Err(error) => status.set(format!("网络错误: {error}")),
                }
                busy.set(false);
            });
        }
    };

    let start_play = {
        let server = server.clone();
        let token = token.clone();
        let url = url.clone();
        let status = status.clone();
        move || {
            match proxy::start(server.get(), token.get()) {
                Ok(port) => {
                    url.set(format!(
                        "http://127.0.0.1:{}/hls?url={}",
                        port,
                        urlencoding::encode(TEST_STREAM)
                    ));
                    status.set(format!("代理端口 {port}"));
                }
                Err(error) => status.set(format!("代理启动失败: {error}")),
            }
        }
    };

    let login_page = {
        let page = page.clone();
        move || page.get() == "login"
    };
    let play_page = {
        let page = page.clone();
        move || page.get() == "play"
    };

    let server_input = server.clone();
    let user_input = user.clone();
    let password_input = password.clone();
    let login_action = do_login.clone();
    let busy_text = busy.clone();

    let play_action = start_play.clone();
    let play_status = status.clone();
    let play_position = position.clone();
    let play_duration = duration.clone();
    let play_url = url.clone();
    let play_buffering = buffering.clone();

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
                .child(text("MoonTVPlus Rust").text_size(px(24.0)))
                .child(when(login_page, move || {
                    view()
                        .gap_2()
                        .child(
                            input()
                                .value({
                                    let signal = server_input.clone();
                                    move || signal.get()
                                })
                                .on_change({
                                    let signal = server_input.clone();
                                    move |value: &str| signal.set(value.to_owned())
                                })
                                .placeholder("服务器地址"),
                        )
                        .child(
                            input()
                                .value({
                                    let signal = user_input.clone();
                                    move || signal.get()
                                })
                                .on_change({
                                    let signal = user_input.clone();
                                    move |value: &str| signal.set(value.to_owned())
                                })
                                .placeholder("用户名"),
                        )
                        .child(
                            input()
                                .value({
                                    let signal = password_input.clone();
                                    move || signal.get()
                                })
                                .masked(true)
                                .on_change({
                                    let signal = password_input.clone();
                                    move |value: &str| signal.set(value.to_owned())
                                })
                                .placeholder("密码"),
                        )
                        .child(
                            view()
                                .px_4()
                                .py_3()
                                .rounded(px(8.0))
                                .bg(move || theme().primary)
                                .active(|style| style.opacity(0.7))
                                .on_press(login_action.clone())
                                .child(
                                    text({
                                        let signal = busy_text.clone();
                                        move || {
                                            if signal.get() {
                                                "登录中…".to_owned()
                                            } else {
                                                "登录".to_owned()
                                            }
                                        }
                                    })
                                    .text_color(move || theme().primary_foreground),
                                ),
                        )
                }))
                .child(when(play_page, move || {
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
                                        .bg(move || theme().primary)
                                        .on_press(play_action.clone())
                                        .child(
                                            text("开始播放")
                                                .text_color(move || theme().primary_foreground),
                                        ),
                                )
                                .child(
                                    text({
                                        let position = play_position.clone();
                                        let duration = play_duration.clone();
                                        move || {
                                            if duration.get() > 0.0 {
                                                format!("{:.0}/{:.0}s", position.get(), duration.get())
                                            } else {
                                                "未加载".to_owned()
                                            }
                                        }
                                    })
                                    .text_color(move || theme().muted_foreground),
                                ),
                        )
                        .child(when({
                            let url = play_url.clone();
                            move || url.get().is_empty()
                        }, || {
                            text("登录后点“开始播放”，经本地代理加载真实 HLS。")
                                .text_color(move || theme().muted_foreground)
                        }))
                        .child(when({
                            let url = play_url.clone();
                            move || !url.get().is_empty()
                        }, move || {
                            view()
                                .flex_1()
                                .rounded(px(8.0))
                                .overflow_hidden()
                                .bg(rgb(0x000000))
                                .child(
                                    video({
                                        let url = play_url.clone();
                                        move || url.get()
                                    })
                                    .flex_1()
                                    .controls(true)
                                    .on_load({
                                        let duration = play_duration.clone();
                                        move |info: &VideoInfo| duration.set(info.duration)
                                    })
                                    .on_progress({
                                        let position = play_position.clone();
                                        let duration = play_duration.clone();
                                        move |progress: &VideoProgress| {
                                            position.set(progress.current_time);
                                            duration.set(progress.duration);
                                            let _ = Storage::set_item(
                                                "resume",
                                                format!(
                                                    "{{\"url\":\"{}\",\"position\":{:.1}}}",
                                                    TEST_STREAM, progress.current_time
                                                ),
                                            );
                                        }
                                    })
                                    .on_buffer({
                                        let buffering = play_buffering.clone();
                                        move |value: &bool| buffering.set(*value)
                                    }),
                                )
                        }))
                        .child(
                            text({
                                let buffering = play_buffering.clone();
                                let status = play_status.clone();
                                move || {
                                    let mut parts = Vec::new();
                                    if buffering.get() {
                                        parts.push("缓冲中".to_owned());
                                    }
                                    if !status.get().is_empty() {
                                        parts.push(status.get());
                                    }
                                    parts.join(" · ")
                                }
                            })
                            .text_color(move || theme().muted_foreground)
                            .text_size(px(13.0)),
                        )
                }))
                .child(
                    text({
                        let status = status.clone();
                        move || status.get()
                    })
                    .text_color(move || theme().muted_foreground)
                    .text_size(px(12.0)),
                ),
        )
}
