//! Batch 8: the site's `/downloads/latest.json` update check; batch 10 adds
//! the download itself ([`install`]).
//!
//! The Cloudflare deploy workflow writes that file with `version`,
//! `versionCode`, `notes` and `apk { name, url, size, sha256 }`. `apk.url`
//! serves the signed APK itself, so it is both the download and the manual
//! fallback.
use super::*;

/// Fields of `/downloads/latest.json` the app uses.
#[derive(Clone, PartialEq, Default)]
pub struct Latest {
    pub version: String,
    pub version_code: Option<i64>,
    pub notes: String,
    /// `apk.url`: the signed APK, and the page to open when a download is
    /// refused. Empty when the manifest has none.
    pub page: String,
    /// `apk.size` and `apk.sha256`: what the downloaded file must be.
    pub apk_size: Option<u64>,
    pub apk_sha256: String,
}

/// The app's own version from `nsh.json`, read at compile time.
pub fn local_version() -> String {
    library::app_version()
}

/// The app's own build number (`nsh.json` `build`), for the "not newer"
/// check before a download starts.
pub fn local_build() -> i64 {
    serde_json::from_str::<serde_json::Value>(include_str!("../nsh.json"))
        .ok()
        .and_then(|value| value["build"].as_i64())
        .unwrap_or(0)
}

/// Splits a version at its first `-`/`+`: the numeric parts and the
/// prerelease/build suffix. `1.9.20-rc1` is `[1, 9, 20]` with `rc1`.
fn segments(version: &str) -> (Vec<u64>, String) {
    let version = version.trim();
    let cut = version.find(['-', '+']).unwrap_or(version.len());
    let numbers = version[..cut]
        .split('.')
        .map(|part| part.trim().parse::<u64>().unwrap_or(0))
        .collect();
    (numbers, version[cut..].trim_start_matches(['-', '+']).to_lowercase())
}

/// Whether `latest` is a newer release than `local`: numeric parts first
/// (missing ones count as 0), then a release beats its prereleases.
pub fn is_newer(latest: &str, local: &str) -> bool {
    let (mut next, next_suffix) = segments(latest);
    let (mut current, current_suffix) = segments(local);
    let width = next.len().max(current.len());
    next.resize(width, 0);
    current.resize(width, 0);
    match next.cmp(&current) {
        std::cmp::Ordering::Greater => true,
        std::cmp::Ordering::Less => false,
        // Same numbers: a release beats its prereleases, so 1.9.20-rc1 is
        // not an update over 1.9.20, but 1.9.20 is over 1.9.20-rc1.
        std::cmp::Ordering::Equal => match (current_suffix.is_empty(), next_suffix.is_empty()) {
            (true, _) => false,
            (false, true) => true,
            (false, false) => next_suffix > current_suffix,
        },
    }
}

/// Drops the Markdown markers the release notes use, so the dialog can
/// show them as plain lines.
fn plain_notes(notes: &str) -> String {
    notes
        .lines()
        .map(|line| {
            let line = line.trim();
            let line = line
                .trim_start_matches('#')
                .trim_start_matches(['-', '*'])
                .trim();
            line.replace("**", "").replace("`", "")
        })
        .collect::<Vec<_>>()
        .join("\n")
        .trim()
        .to_owned()
}

#[derive(Clone)]
pub struct Controller {
    server: Signal<String>,
    /// Inline status under 关于 (检查中… / 已是最新版本 / 未能打开链接).
    notice: Signal<String>,
    checking: Signal<bool>,
    show: Signal<bool>,
    latest: Signal<Latest>,
}

impl Controller {
    pub fn new(server: Signal<String>, notice: Signal<String>) -> Self {
        Self {
            server,
            notice,
            checking: signal(false),
            show: signal(false),
            latest: signal(Latest::default()),
        }
    }

    pub fn show(&self) -> Signal<bool> {
        self.show.clone()
    }

    pub fn latest(&self) -> Signal<Latest> {
        self.latest.clone()
    }

    pub fn checking(&self) -> ReadSignal<bool> {
        self.checking.read_only()
    }

    /// Checked once after the home screen appears; failures stay silent.
    pub fn check_silently(&self) {
        self.check(false);
    }

    /// The 检查更新 row: same check, with the inline status.
    pub fn check_now(&self) {
        self.check(true);
    }

    fn check(&self, manual: bool) {
        if self.checking.get() {
            return;
        }
        self.checking.set(true);
        if manual {
            self.notice.set("检查中…".to_owned());
        }
        let this = self.clone();
        let timeout = self.clone();
        bg_bounded(async move {
            let url = format!(
                "{}/downloads/latest.json",
                this.server.get().trim_end_matches('/')
            );
            let result = send_once(HttpRequest::get(url)).await;
            this.checking.set(false);
            let (status, body) = match result {
                Ok(answer) => answer,
                Err(_) => {
                    if manual {
                        this.notice.set("检查失败，请稍后再试".to_owned());
                    }
                    return;
                }
            };
            if status != 200 {
                if manual {
                    this.notice.set("检查失败，请稍后再试".to_owned());
                }
                return;
            }
            let Ok(value) = serde_json::from_str::<serde_json::Value>(&body) else {
                if manual {
                    this.notice.set("检查失败，请稍后再试".to_owned());
                }
                return;
            };
            let Some(version) = value["version"].as_str() else {
                if manual {
                    this.notice.set("检查失败，请稍后再试".to_owned());
                }
                return;
            };
            if !is_newer(version, &local_version()) {
                if manual {
                    this.notice.set("已是最新版本".to_owned());
                }
                return;
            }
            let notes = value["notes"].as_str().unwrap_or_default();
            this.latest.set(Latest {
                version: version.to_owned(),
                version_code: value["versionCode"].as_i64(),
                notes: plain_notes(notes),
                page: value["apk"]["url"].as_str().unwrap_or_default().to_owned(),
                apk_size: value["apk"]["size"].as_u64(),
                apk_sha256: value["apk"]["sha256"].as_str().unwrap_or_default().to_owned(),
            });
            this.notice.set(String::new());
            this.show.set(true);
        }, move || {
            timeout.checking.set(false);
            if manual {
                timeout.notice.set(format!("检查更新{TIMEOUT_ERROR}，再次点击检查更新"));
            }
        });
    }

    /// Opens a link outside the app; reports when the platform refused.
    pub fn open_link(&self, url: &str) {
        if url.is_empty() {
            return;
        }
        let this = self.clone();
        let url = url.to_owned();
        bg(async move {
            if !open_url(url).await {
                this.notice.set("未能打开链接".to_owned());
            }
        });
    }

    pub fn open_page(&self) {
        let page = self.latest.get().page;
        if page.is_empty() {
            self.open_link("https://github.com/Happier-X/MoonTVPlus/releases");
        } else {
            self.open_link(&page);
        }
    }

    pub fn close(&self) {
        self.show.set(false);
    }
}
