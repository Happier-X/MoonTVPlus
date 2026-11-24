//! Video capability probe for Android: built-in `video()` with network grants.

mod proxy;

use nsh_base::theme;
use nsh_core::prelude::*;
use nsh_core::storage::Storage;

const MUX: &str = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
const MP4: &str = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4";
const REDBULL: &str = "https://hn.bfvvs.com/play/dJ6PoBvd/index.m3u8";
const SITE_PROXY: &str = "https://tv.370125.xyz/api/proxy-m3u8?url=https%3A%2F%2Fhn.bfvvs.com%2Fplay%2FdJ6PoBvd%2Findex.m3u8&source=directplay";

#[native_module("moontvplus-rust/native")]
fn exports(registry: &mut Registry) {
    registry.register::<App>();
}

#[component]
pub fn App() -> impl IntoElement {
    let src = signal(String::new());
    let status = signal("选一组".to_owned());
    let error = signal(String::new());
    let position = signal(0.0_f32);
    let duration = signal(0.0_f32);
    let proxy_port = signal(0_u16);

    view()
        .flex_1()
        .bg(move || theme().background)
        .text_color(move || theme().foreground)
        .p_safe_area()
        .child(
            view()
                .flex_1()
                .p_4()
                .gap_2()
                .child(text("Video probe").text_size(px(20.0)))
                .child(
                    view()
                        .flex_row()
                        .flex_wrap()
                        .gap_2()
                        .child(button("mux HLS", {
                            let src = src.clone();
                            let status = status.clone();
                            let error = error.clone();
                            move || {
                                src.set(MUX.to_owned());
                                status.set("mux HLS".to_owned());
                                error.set(String::new());
                            }
                        }))
                        .child(button("mp4", {
                            let src = src.clone();
                            let status = status.clone();
                            let error = error.clone();
                            move || {
                                src.set(MP4.to_owned());
                                status.set("mp4".to_owned());
                                error.set(String::new());
                            }
                        }))
                        .child(button("site proxy", {
                            let src = src.clone();
                            let status = status.clone();
                            let error = error.clone();
                            move || {
                                src.set(SITE_PROXY.to_owned());
                                status.set("site proxy (no auth header)".to_owned());
                                error.set(String::new());
                            }
                        }))
                        .child(button("local proxy", {
                            let src = src.clone();
                            let status = status.clone();
                            let error = error.clone();
                            move || {
                                let token = Storage::get_item("token").unwrap_or_default();
                                if token.is_empty() {
                                    status.set("no token".to_owned());
                                    return;
                                }
                                match proxy::start("http://10.0.2.2:9000".to_owned(), token) {
                                    Ok(port) => {
                                        proxy_port.set(port);
                                        src.set(format!(
                                            "http://127.0.0.1:{}/a.m3u8?u={}",
                                            port,
                                            urlencoding::encode(REDBULL)
                                        ));
                                        status.set(format!("local proxy {port}"));
                                        error.set(String::new());
                                    }
                                    Err(value) => status.set(format!("proxy error: {value}")),
                                }
                            }
                        })),
                )
                .child(
                    view()
                        .flex_1()
                        .rounded(px(8.0))
                        .overflow_hidden()
                        .bg(rgb(0x000000))
                        .child(
                            video({
                                let src = src.clone();
                                move || src.get()
                            })
                            .flex_1()
                            .controls(true)
                            .on_load({
                                let duration = duration.clone();
                                let status = status.clone();
                                move |info: &VideoInfo| {
                                    duration.set(info.duration);
                                    status.set(format!("loaded {:.0}s", info.duration));
                                }
                            })
                            .on_progress({
                                let position = position.clone();
                                let duration = duration.clone();
                                move |progress: &VideoProgress| {
                                    position.set(progress.current_time);
                                    duration.set(progress.duration);
                                }
                            })
                            .on_error({
                                let error = error.clone();
                                let status = status.clone();
                                move |message: &str| {
                                    error.set(message.to_owned());
                                    status.set("error".to_owned());
                                }
                            }),
                        ),
                )
                .child(
                    text({
                        let position = position.clone();
                        let duration = duration.clone();
                        move || format!("{:.1}/{:.1}s", position.get(), duration.get())
                    })
                    .text_size(px(13.0)),
                )
                .child(
                    text({
                        let port = proxy_port.clone();
                        move || if port.get() > 0 { format!("proxy port {}", port.get()) } else { String::new() }
                    })
                    .text_size(px(13.0)),
                )
                .child(
                    text({
                        let status = status.clone();
                        move || status.get()
                    })
                    .text_size(px(13.0)),
                )
                .child(
                    text({
                        let error = error.clone();
                        move || error.get()
                    })
                    .text_color(rgb(0xcc2222))
                    .text_size(px(12.0)),
                ),
        )
}

fn button(label: &'static str, action: impl Fn() + 'static) -> impl IntoElement {
    view()
        .px_3()
        .py_2()
        .rounded(px(8.0))
        .bg(move || theme().primary)
        .on_press(action)
        .child(text(label).text_color(move || theme().primary_foreground))
}
