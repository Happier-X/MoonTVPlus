//! `moontv.video`: a native video component for Android, registered by the
//! app's Rust crate. Wraps `android.widget.VideoView`, which hands the URL and
//! headers to MediaPlayer's `setDataSource(Context, Uri, Map)` overload, so
//! authenticated HLS streams play without a local proxy.

use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use nsh::{
    ComponentContext, NativeComponent,
    jni::{
        JNIEnv,
        objects::{GlobalRef, JObject, JValue},
    },
};
use nsh_core::JsonValue;
use nsh_core::prelude::*;
use serde_json::json;

const NAME: &str = "moontv.video";

pub(crate) fn register(components: &nsh::Components) {
    components.register(
        NAME,
        Rc::new(|context: ComponentContext| -> Box<dyn NativeComponent> {
            Box::new(Video {
                view: create_view(&context),
                context,
                src: String::new(),
                headers: Vec::new(),
                prepared: Arc::new(AtomicBool::new(false)),
                ticks: Arc::new(AtomicU32::new(0)),
                timer: None,
            })
        }),
    );
}

fn create_view(context: &ComponentContext) -> GlobalRef {
    context
        .with_env(|env| {
            let view = env.new_object(
                "android/widget/VideoView",
                "(Landroid/content/Context;)V",
                &[JValue::Object(context.android_context().as_obj())],
            )?;
            env.new_global_ref(view)
        })
        .unwrap_or_else(|_| context.android_context().clone())
}

struct Video {
    view: GlobalRef,
    context: ComponentContext,
    src: String,
    headers: Vec<(String, String)>,
    prepared: Arc<AtomicBool>,
    ticks: Arc<AtomicU32>,
    timer: Option<Spawned<()>>,
}

impl Video {
    fn load(&mut self, env: &mut JNIEnv<'_>) {
        if self.src.is_empty() {
            return;
        }
        let url = match env.new_string(&self.src) {
            Ok(value) => value,
            Err(_) => return,
        };
        let uri = match env.call_static_method(
            "android/net/Uri",
            "parse",
            "(Ljava/lang/String;)Landroid/net/Uri;",
            &[JValue::Object(&url)],
        ) {
            Ok(value) => match value.l() {
                Ok(uri) => uri,
                Err(_) => return,
            },
            Err(_) => return,
        };
        let map = match env.new_object("java/util/HashMap", "()V", &[]) {
            Ok(value) => value,
            Err(_) => return,
        };
        for (key, value) in &self.headers {
            let Ok(key) = env.new_string(key) else { continue };
            let Ok(value) = env.new_string(value) else { continue };
            let _ = env.call_method(
                &map,
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                &[JValue::Object(&key), JValue::Object(&value)],
            );
        }
        let _ = env.call_method(
            self.view.as_obj(),
            "setVideoURI",
            "(Landroid/net/Uri;Ljava/util/Map;)V",
            &[JValue::Object(&uri), JValue::Object(&map)],
        );
        let _ = env.call_method(self.view.as_obj(), "start", "()V", &[]);
        self.start_timer();
    }

    fn start_timer(&mut self) {
        if self.timer.is_some() {
            return;
        }
        let view = self.view.clone();
        let context = self.context.clone();
        let prepared = self.prepared.clone();
        let ticks = self.ticks.clone();
        self.timer = Some(set_interval(Duration::from_millis(500), move || {
            let result = context.with_env(|env| {
                let duration = env
                    .call_method(view.as_obj(), "getDuration", "()I", &[])?
                    .i()? as f64;
                let position = env
                    .call_method(view.as_obj(), "getCurrentPosition", "()I", &[])?
                    .i()? as f64;
                Ok((duration, position))
            });
            if let Ok((duration, position)) = result {
                if duration > 0.0 {
                    if !prepared.swap(true, Ordering::Relaxed) {
                        context.emit("prepared", json!({ "duration": duration / 1000.0 }));
                    }
                    context.emit(
                        "progress",
                        json!({ "currentTime": position / 1000.0, "duration": duration / 1000.0 }),
                    );
                } else if ticks.fetch_add(1, Ordering::Relaxed) == 24 {
                    context.emit("error", json!({ "message": "MediaPlayer 未能在 12 秒内准备完成" }));
                }
            }
        }));
    }
}

impl NativeComponent for Video {
    fn view(&self) -> &GlobalRef {
        &self.view
    }

    fn set_prop(&mut self, env: &mut JNIEnv<'_>, name: &str, value: Option<&JsonValue>) {
        match name {
            "src" => {
                self.src = value.and_then(JsonValue::as_str).unwrap_or_default().to_owned();
                self.prepared.store(false, Ordering::Relaxed);
                self.ticks.store(0, Ordering::Relaxed);
                self.load(env);
            }
            "headers" => {
                self.headers = value
                    .and_then(JsonValue::as_object)
                    .map(|object| {
                        object
                            .iter()
                            .filter_map(|(key, value)| {
                                value.as_str().map(|value| (key.clone(), value.to_owned()))
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                self.load(env);
            }
            "paused" => {
                let paused = value.and_then(JsonValue::as_bool).unwrap_or(false);
                let method = if paused { "pause" } else { "start" };
                let _ = env.call_method(self.view.as_obj(), method, "()V", &[]);
            }
            _ => {}
        }
    }

    fn command(&mut self, env: &mut JNIEnv<'_>, name: &str, args: &JsonValue) {
        match name {
            "play" => {
                let _ = env.call_method(self.view.as_obj(), "start", "()V", &[]);
            }
            "pause" => {
                let _ = env.call_method(self.view.as_obj(), "pause", "()V", &[]);
            }
            "seek" => {
                let seconds = args
                    .as_f64()
                    .or_else(|| args.get("seconds").and_then(JsonValue::as_f64))
                    .unwrap_or(0.0);
                let _ = env.call_method(
                    self.view.as_obj(),
                    "seekTo",
                    "(I)V",
                    &[JValue::Int((seconds * 1000.0) as i32)],
                );
            }
            _ => {}
        }
    }

    fn destroy(&mut self, _env: &mut JNIEnv<'_>) {
        if let Some(timer) = self.timer.take() {
            timer.cancel();
        }
        let _ = self;
    }
}
