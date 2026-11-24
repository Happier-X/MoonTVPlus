//! MoonTVPlus pure-Rust spike: `moontv.video` JNI + MediaPlayer playback.

#[cfg(target_os = "android")]
mod video;

use nsh_base::theme;
use nsh_core::JsonValue;
use nsh_core::prelude::*;
use nsh_core::storage::Storage;
use serde_json::json;

const REDBULL: &str = "https://hn.bfvvs.com/play/dJ6PoBvd/index.m3u8";
const UA: &str = "MoonTVPlus Rust/0.1 (Android)";

#[native_module("moontvplus-rust/native")]
fn exports(registry: &mut Registry) {
    registry.register::<App>();
}

#[cfg(target_os = "android")]
#[native_components(android)]
fn components(components: &nsh::Components) {
    video::register(components);
}

fn direct_cache() -> Option<String> {
    Storage::get_item("token")
}

#[component]
pub fn App() -> impl IntoElement {
    let status = signal("选择一种源开始".to_owned());
    let position = signal(0.0_f64);
    let duration = signal(0.0_f64);
    let src = signal(String::new());
    let headers = signal(json!({}) as JsonValue);
    let video_ref = element_ref();

    let play_direct = {
        let src = src.clone();
        let headers = headers.clone();
        let status = status.clone();
        move || {
            src.set(REDBULL.to_owned());
            headers.set(json!({
                "User-Agent": UA,
                "Referer": "https://hn.bfvvs.com/",
            }));
            status.set("直连 RedBull 加载中…".to_owned());
        }
    };

    let play_site = {
        let src = src.clone();
        let headers = headers.clone();
        let status = status.clone();
        move || {
            let token = direct_cache().unwrap_or_default();
            if token.is_empty() {
                status.set("没有 token：先往 Storage 写入 token".to_owned());
                return;
            }
            let url = format!(
                "http://10.0.2.2:9000/api/proxy-m3u8?url={}&source=directplay",
                urlencoding::encode(REDBULL)
            );
            src.set(url);
            headers.set(json!({
                "User-Agent": UA,
                "Referer": "https://hn.bfvvs.com/",
                "Authorization": format!("Bearer {token}"),
            }));
            status.set("站点代理（带 Bearer）加载中…".to_owned());
        }
    };

    let seek = {
        let video_ref = video_ref.clone();
        move |seconds: f64| {
            video_ref.command("seek", json!(seconds));
        }
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
                .child(text("Moontv.video spike").text_size(px(22.0)))
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
                                .on_press(play_direct.clone())
                                .child(
                                    text("直连 RedBull").text_color(move || theme().primary_foreground),
                                ),
                        )
                        .child(
                            view()
                                .px_3()
                                .py_2()
                                .rounded(px(8.0))
                                .bg(move || theme().primary)
                                .on_press(play_site.clone())
                                .child(
                                    text("站点代理").text_color(move || theme().primary_foreground),
                                ),
                        ),
                )
                .child(
                    view()
                        .flex_1()
                        .rounded(px(8.0))
                        .overflow_hidden()
                        .bg(rgb(0x000000))
                        .child(
                            native("moontv.video")
                                .element_ref(&video_ref)
                                .flex_1()
                                .prop("src", {
                                    let src = src.clone();
                                    move || src.get()
                                })
                                .prop("headers", {
                                    let headers = headers.clone();
                                    move || headers.get()
                                })
                                .on_native_event({
                                    let status = status.clone();
                                    let position = position.clone();
                                    let duration = duration.clone();
                                    move |event: &NativeEvent| match &*event.name {
                                        "prepared" => {
                                            let seconds = event.data["duration"].as_f64().unwrap_or(0.0);
                                            duration.set(seconds);
                                            status.set(format!("已准备，时长 {seconds:.0}s"));
                                        }
                                        "progress" => {
                                            position.set(event.data["currentTime"].as_f64().unwrap_or(0.0));
                                            duration.set(event.data["duration"].as_f64().unwrap_or(0.0));
                                        }
                                        "error" => {
                                            status.set(event.data["message"].as_str().unwrap_or("播放失败").to_owned());
                                        }
                                        _ => {}
                                    }
                                }),
                        ),
                )
                .child(
                    text({
                        let position = position.clone();
                        let duration = duration.clone();
                        let status = status.clone();
                        move || {
                            format!(
                                "{:.0}/{:.0}s · {}",
                                position.get(),
                                duration.get(),
                                status.get()
                            )
                        }
                    })
                    .text_color(move || theme().muted_foreground)
                    .text_size(px(13.0)),
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
                                .bg(move || theme().muted)
                                .on_press({
                                    let seek = seek.clone();
                                    move || seek(5.0)
                                })
                                .child(text("+5s")),
                        )
                        .child(
                            view()
                                .px_3()
                                .py_2()
                                .rounded(px(8.0))
                                .bg(move || theme().muted)
                                .on_press({
                                    let seek = seek.clone();
                                    move || seek(30.0)
                                })
                                .child(text("+30s")),
                        ),
                ),
        )
}
