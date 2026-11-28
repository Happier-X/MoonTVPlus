//! Batch 5 playback UI: native Video, seeking, site play records and HLS qualities.
use super::*;
use detail::Selection;

pub fn page(port: Signal<u16>, server: Signal<String>, token: Signal<String>, selection: Signal<Selection>, back: impl Fn() + Clone + 'static) -> impl IntoElement {
    let paused = signal(false);
    let initial = selection.get();
    let carried = initial.resume.as_ref().filter(|record| {
        record.key == format!("{}+{}", initial.info.source, initial.info.id)
            && record.index == (initial.index + 1) as f64
            && record.total_time.is_finite() && record.total_time > 0.0
            && record.play_time.is_finite() && record.play_time >= 0.0
    });
    let initial_total = carried.map(|record| record.total_time).unwrap_or(0.0);
    let initial_position = carried.map(|record| record.play_time.clamp(0.0, record.total_time)).unwrap_or(0.0);
    let position = signal(initial_position);
    let duration = signal(initial_total);
    let loaded = signal(false);
    let error = signal(String::new());
    let qualities = signal(Vec::<String>::new());
    let quality = signal(0_usize);
    let bar_width = signal(0.0_f32);
    let drag_start = signal(0.0_f32);
    let seek_target = signal(if initial_position > 0.0 { Some(initial_position) } else { None::<f64> });
    // Target remains pending until native progress confirms the seek.
    let seek_issued = signal(false);
    let manual_seek = signal(false);
    let restore_pending = signal(initial.resume.is_none());
    let ref_handle = nsh_core::element::element_ref();

    // Restore the active episode from the authenticated site's exact record key.
    let resume_selection = selection.clone();
    let resume_server = server.clone();
    let resume_token = token.clone();
    let resume_duration = duration.clone();
    let resume_position = position.clone();
    let resume_target = seek_target.clone();
    let resume_loaded = loaded.clone();
    let resume_handle = ref_handle.clone();
    let restart_attempt = signal(0_u64);
    effect(move || {
        let selected = resume_selection.get();
        // A carried record initializes state before Video mounts: no late GET
        // may overwrite the user's subsequent seek or newly saved position.
        if selected.resume.is_some() { return; }
        let key = format!("{}+{}", selected.info.source, selected.info.id);
        if key.is_empty() { return; }
        let server = resume_server.get();
        let token = resume_token.get();
        let resume_duration = resume_duration.clone();
        let resume_position = resume_position.clone();
        let resume_target = resume_target.clone();
        let resume_loaded = resume_loaded.clone();
        let resume_handle = resume_handle.clone();
        let resume_index = selected.index + 1;
        let manual_seek = manual_seek.clone();
        let restore_pending = restore_pending.clone();
        let seek_issued = seek_issued.clone();
        let task = spawn(async move {
            match api(&server, &token, "/api/playrecords", "GET", None, true).await {
                Ok((200, text, _)) => {
                    if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                        let record = &json[&key];
                        let watched = record["play_time"].as_f64().unwrap_or(0.0);
                        let total = record["total_time"].as_f64().unwrap_or(0.0);
                        let saved_index = record["index"].as_u64().unwrap_or(0) as usize;
                        if !manual_seek.get() && saved_index == resume_index && watched.is_finite() && watched > 0.0
                            && total.is_finite() && total > watched + 15.0 {
                            // Retain the last known total while the native player prepares.
                            resume_duration.set(total);
                            resume_position.set(watched);
                            resume_target.set(Some(watched));
                            if resume_loaded.get() { resume_handle.seek(watched); seek_issued.set(true); }
                        }
                    }
                }
                Ok((status, _, _)) => eprintln!("resume record HTTP {status}"),
                Err(ApiError::AuthExpired) => eprintln!("resume record: session expired"),
                Err(ApiError::Network(error)) => eprintln!("resume record failed: {error}"),
            }
            restore_pending.set(false);
        });
        std::mem::forget(task);
    });

    // Fetch the exact master playlist's variant list via the local proxy.
    let quality_selected = selection.clone();
    let quality_port = port.clone();
    let quality_state = qualities.clone();
    let selected_quality = quality.clone();
    let switch_target = seek_target.clone();
    let switch_position = position.clone();
    let switch_loaded = loaded.clone();
    let switch_handle = ref_handle.clone();
    let switch_restart = restart_attempt.clone();
    effect(move || {
        let selected = quality_selected.get();
        let quality_key = format!("{}+{}", selected.info.source, selected.info.id);
        if quality_key.is_empty() { return; }
        let quality_request_port = quality_port.get();
        if quality_request_port == 0 { return; }
        let port = quality_request_port;
        if selected.url.is_empty() { return; }
        let url = format!("http://127.0.0.1:{port}/qualities?u={}", urlencoding::encode(&selected.url));
        let state = quality_state.clone();
        let selected_quality = selected_quality.clone();
        let switch_target = switch_target.clone();
        let switch_position = switch_position.clone();
        let switch_loaded = switch_loaded.clone();
        let switch_handle = switch_handle.clone();
        let switch_restart = switch_restart.clone();
        let task = spawn(async move {
            if let Ok(response) = fetch(HttpRequest::get(url)).await {
                if response.status() == 200 {
                    if let Ok(json) = serde_json::from_str::<serde_json::Value>(&response.text()) {
                        if let Some(items) = json["variants"].as_array() {
                            let labels = items.iter().map(|item| item["label"].as_str().unwrap_or("Auto").to_owned()).collect::<Vec<_>>();
                            let default = json["default"].as_u64().unwrap_or(0) as usize;
                            if labels.len() > 1 {
                                selected_quality.set(default.min(labels.len() - 1));
                                state.set(labels);
                                let position = switch_position.get();
                                switch_target.set(Some(position));
                                switch_loaded.set(false);
                                switch_restart.set(switch_restart.get() + 1);
                                let _ = switch_handle;
                            }
                        }
                    }
                }
            }
        });
        std::mem::forget(task);
    });

    let save_position = position.clone();
    let save_duration = duration.clone();
    let save_selection = selection.clone();
    let save_server = server.clone();
    let save_token = token.clone();
    let save_loaded = loaded.clone();
    let save_target = seek_target.clone();
    let last_saved = std::sync::Arc::new(std::sync::Mutex::new(0.0_f64));
    let save_progress = {
        let last_saved = last_saved.clone();
        move |force: bool| {
        let selected = save_selection.get();
        if selected.url.is_empty() { return; }
        if restore_pending.get() || save_target.get().is_some() || !save_loaded.get() || save_position.get() <= 0.5 { return; }
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
                .map(|value| value.as_secs_f64()).unwrap_or(0.0);
            let mut last = last_saved.lock().unwrap_or_else(|error| error.into_inner());
            if !force && now - *last < 5.0 { return; }
            *last = now;
            save_play_record(&save_server.get(), &save_token.get(), &save_selection.get(), save_position.get(), save_duration.get());
        }
    };

    let keep_position = position.clone();
    let video_restart = restart_attempt.clone();
    let video_quality = quality.clone();
    let make_source_port = port.clone();
    let make_source_selection = selection.clone();
    view().flex_1().p_4().gap_3()
        .child(view().flex_row().gap_2()
            .child(view().px_3().py_2().rounded(px(8.0)).bg(move || theme().muted)
                .on_press({ let save = save_progress.clone(); let back = back.clone(); move || { save(true); back(); } })
                .child(text("返回")))
            .child(text({ let selection = selection.clone(); move || {
                let item = selection.get(); format!("{} · {} · 第 {} 集", item.info.title, item.info.source_name, item.index + 1)
            }}).text_color(move || theme().muted_foreground)))
        .child(video(move || {
            let selected = make_source_selection.get();
            let attempt = video_restart.get();
            format!("http://127.0.0.1:{}/a.m3u8?u={}&q={}&r={attempt}", make_source_port.get(), urlencoding::encode(&selected.url), video_quality.get())
        }).flex_1().controls(false).paused(paused.clone())
            .element_ref(&ref_handle)
            .on_load({ let seek_issued = seek_issued.clone(); let duration = duration.clone(); let loaded = loaded.clone(); let target = seek_target.clone(); let handle = ref_handle.clone();
                move |info: &nsh_core::element::VideoInfo| {
                    let total = info.duration as f64;
                    if total.is_finite() && total > 0.0 { duration.set(total); }
                    loaded.set(true);
                    if let Some(seconds) = target.get() { handle.seek(seconds); seek_issued.set(true); }
                }
            })
            .on_progress({ let target = seek_target.clone(); let issued = seek_issued.clone(); let position = position.clone(); let duration = duration.clone(); let save = save_progress.clone();
                move |info: &nsh_core::element::VideoProgress| {
                    let total = info.duration as f64;
                    let current = info.current_time as f64;
                    // Native preparation/seek events may report an unknown total (0).
                    if total.is_finite() && total > 0.0 { duration.set(total); }
                    if let Some(expected) = target.get() {
                        if !issued.get() || !current.is_finite() || (current - expected).abs() > 2.0 { return; }
                        target.set(None);
                        issued.set(false);
                    }
                    if current.is_finite() && current >= 0.0 { position.set(current); }
                    save(false);
                }
            })
            .on_end({ let position = position.clone(); let duration = duration.clone(); let save = save_progress.clone();
                move || { position.set(duration.get()); save(true); }
            })
            .on_error({ let error = error.clone(); move |message: &str| error.set(if message.is_empty() { "播放失败".to_owned() } else { message.to_owned() }) }))
        .child(view().flex_row().items_center().gap_3()
            .child(view().px_4().py_2().rounded(px(8.0)).bg(move || theme().muted)
                .on_press({ let paused = paused.clone(); let save = save_progress.clone(); move || {
                    let next = !paused.get();
                    if next { save(true); }
                    paused.set(next);
                }}).child(text(move || if paused.get() { "播放" } else { "暂停" })))
            .child(text({ let position = position.clone(); let duration = duration.clone(); move || format!("{} / {}", time(position.get()), time(duration.get())) })
                .text_color(move || theme().muted_foreground)))
        .child(view().relative().h(px(28.0))
            .child(view().absolute().inset_0().h(px(28.0)).rounded(px(4.0)).bg(move || theme().muted)
                .on_layout(move |bounds: &nsh_core::Bounds<nsh_core::Points>| bar_width.set(bounds.size.width.0)))
            .child(view().absolute().left_0().top_0().h(px(28.0)).rounded(px(4.0)).bg(BRAND)
                .w(move || px(bar_width.get() * ratio(position.get(), duration.get()))))
            .child(view().absolute().top_0().h(px(28.0)).w(px(14.0)).rounded(px(4.0)).bg(move || theme().foreground)
                .left(move || px((bar_width.get() * ratio(position.get(), duration.get()) - 7.0).max(0.0))))
            .on_press_down({ let drag_start = drag_start.clone(); move |event: &nsh_core::PressEvent| drag_start.set(event.position.x.0) })
            .on_press({ let width = bar_width.clone(); let duration = duration.clone();
                let handle = ref_handle.clone(); let position = position.clone(); let save = save_progress.clone();
                move |event: &nsh_core::PressEvent| {
                    if !duration.get().is_finite() || duration.get() <= 0.0 { return; }
                    let seconds = (event.position.x.0 / width.get().max(1.0)).clamp(0.0, 1.0) as f64 * duration.get();
                    manual_seek.set(true); seek_target.set(Some(seconds)); seek_issued.set(true);
                    handle.seek(seconds); position.set(seconds); save(true);
                }
            })
            .on_pan_end({ let width = bar_width.clone(); let duration = duration.clone(); let start = drag_start.clone();
                let handle = ref_handle.clone(); let position = position.clone(); let save = save_progress.clone();
                move |event: &nsh_core::element::PanEvent| {
                    if event.cancelled || !duration.get().is_finite() || duration.get() <= 0.0 { return; }
                    let x = start.get() + event.translation.x.0;
                    let seconds = (x / width.get().max(1.0)).clamp(0.0, 1.0) as f64 * duration.get();
                    manual_seek.set(true); seek_target.set(Some(seconds)); seek_issued.set(true);
                    handle.seek(seconds); position.set(seconds); save(true);
                }
            }))
        .child(when({ let error = error.clone(); move || !error.get().is_empty() }, move || {
            let position = keep_position.clone(); let target = seek_target.clone(); let paused = paused.clone();
            view().gap_2().child(text({ let error = error.clone(); move || error.get() }).text_color(move || theme().muted_foreground))
                .child(view().px_3().py_2().rounded(px(8.0)).bg(move || theme().muted)
                    .on_press({ let error = error.clone(); let restart = restart_attempt.clone();
                        let handle = ref_handle.clone(); let position = position.clone(); let target = target.clone();
                        let paused = paused.clone();
                        move || {
                            let now = position.get();
                            error.set(String::new());
                            paused.set(false);
                            target.set(Some(now));
                            handle.seek(now);
                            restart.set(restart.get() + 1);
                        }
                    }).child(text("重试")))
        }))
        .child(when({ let qualities = qualities.clone(); move || qualities.get().len() > 1 }, move || {
            let labels = qualities.clone(); let current = quality.clone(); let position = keep_position.clone();
            let target = seek_target.clone(); let restart = video_restart.clone();
            view().flex_row().gap_2().items_center().child(text("清晰度")).child(each(
                move || labels.get().into_iter().enumerate().collect::<Vec<_>>(),
                |item: &(usize, String)| item.0,
                move |item: ReadSignal<(usize, String)>, _| {
                    let current = current.clone(); let target = target.clone(); let restart = restart.clone();
                    let position = position.clone();
                    let index = item.get().0;
                    view().px_3().py_2().rounded(px(8.0)).bg(move || theme().muted)
                        .on_press(move || {
                            if current.get() == index { return; }
                            let now = position.get();
                            current.set(index);
                            target.set(Some(now));
                            restart.set(restart.get() + 1);
                        }).child(text(move || item.get().1))
                })
            )
        }))
}

fn time(seconds: f64) -> String {
    let seconds = seconds.max(0.0) as u64;
    format!("{:02}:{:02}", seconds / 60, seconds % 60)
}

fn ratio(position: f64, duration: f64) -> f32 {
    if duration <= 0.0 { 0.0 } else { (position / duration).clamp(0.0, 1.0) as f32 }
}
