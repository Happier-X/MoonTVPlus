//! Batch 10: in-app download of a new release, verified, handed to the
//! system installer.
//!
//! `latest.json` pins the APK by size and SHA-256, both taken from the
//! release asset our build workflow signed and checked, so a file that
//! matches them is byte for byte that artifact. The download streams into
//! the app's data directory (`<filesDir>/nsh/<app>` on Android, served by
//! the runtime's `FilesProvider`), which is what lets `open_path` hand it to
//! the installer as a `content:` URI.
use super::*;
use sha2::{Digest, Sha256};
use std::io::{BufWriter, Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone, PartialEq)]
pub enum State {
    Idle,
    Running { done: u64, total: u64 },
    Verifying,
    /// The downloaded file, ready for the installer.
    Ready(String),
    Failed(String),
    Cancelled,
}

#[derive(Clone)]
pub struct Download {
    state: Signal<State>,
    cancel: Arc<AtomicBool>,
}

impl Download {
    pub fn new() -> Self {
        Self {
            state: signal(State::Idle),
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn state(&self) -> Signal<State> {
        self.state.clone()
    }

    pub fn is_busy(&self) -> bool {
        matches!(self.state.get(), State::Running { .. } | State::Verifying)
    }

    pub fn reset(&self) {
        self.cancel.store(false, Ordering::Relaxed);
        self.state.set(State::Idle);
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    pub fn start(
        &self,
        version: String,
        url: String,
        size: Option<u64>,
        sha256: String,
        version_code: Option<i64>,
    ) {
        if self.is_busy() {
            return;
        }
        if url.is_empty() {
            self.state.set(State::Failed("这个版本没有下载地址".to_owned()));
            return;
        }
        if let Some(code) = version_code {
            if code <= update::local_build() {
                self.state
                    .set(State::Failed(format!("版本号未递增（{code}），已中止")));
                return;
            }
        }
        self.cancel.store(false, Ordering::Relaxed);
        self.state.set(State::Running {
            done: 0,
            total: size.unwrap_or(0),
        });
        let this = self.clone();
        thread::spawn(move || this.fetch(version, url, size, sha256));
    }

    fn fetch(&self, version: String, url: String, size: Option<u64>, sha256: String) {
        let failed = |message: String| self.state.set(State::Failed(message));
        let Some(root) = data_dir() else {
            return failed("这个平台没有可写的应用目录".to_owned());
        };
        let dir = root.join("updates");
        if let Err(error) = std::fs::create_dir_all(&dir) {
            return failed(format!("无法创建下载目录：{error}"));
        }
        let path = dir.join(format!("moontvplus-{version}.apk"));
        let response = match ureq::get(&url).call() {
            Ok(response) => response,
            Err(ureq::Error::Status(code, _)) => {
                return failed(format!("下载失败（HTTP {code}）"));
            }
            Err(error) => return failed(format!("下载失败：{error}")),
        };
        let total = size.unwrap_or_else(|| {
            response
                .header("Content-Length")
                .and_then(|value| value.parse().ok())
                .unwrap_or(0)
        });
        let mut writer = match std::fs::File::create(&path) {
            Ok(file) => BufWriter::with_capacity(64 * 1024, file),
            Err(error) => return failed(format!("无法写入下载文件：{error}")),
        };
        let mut reader = response.into_reader();
        let mut hasher = Sha256::new();
        let mut buffer = vec![0u8; 64 * 1024];
        let mut done = 0u64;
        let mut tick = Instant::now();
        loop {
            if self.cancel.load(Ordering::Relaxed) {
                drop(writer);
                let _ = std::fs::remove_file(&path);
                self.state.set(State::Cancelled);
                return;
            }
            let read = match reader.read(&mut buffer) {
                Ok(read) => read,
                Err(error) => {
                    drop(writer);
                    let _ = std::fs::remove_file(&path);
                    return failed(format!("下载中断：{error}"));
                }
            };
            if read == 0 {
                break;
            }
            if let Err(error) = writer.write_all(&buffer[..read]) {
                drop(writer);
                let _ = std::fs::remove_file(&path);
                return failed(format!("写入失败：{error}"));
            }
            hasher.update(&buffer[..read]);
            done += read as u64;
            if tick.elapsed() >= Duration::from_millis(150) {
                self.state.set(State::Running { done, total });
                tick = Instant::now();
            }
        }
        if let Err(error) = writer.flush() {
            drop(writer);
            let _ = std::fs::remove_file(&path);
            return failed(format!("写入失败：{error}"));
        }
        drop(writer);
        self.state.set(State::Verifying);
        if self.cancel.load(Ordering::Relaxed) {
            let _ = std::fs::remove_file(&path);
            self.state.set(State::Cancelled);
            return;
        }
        if let Some(size) = size.filter(|size| *size > 0) {
            if done != size {
                let _ = std::fs::remove_file(&path);
                return failed(format!("大小不符（{done} / {size} 字节），已中止"));
            }
        }
        if !sha256.trim().is_empty() {
            let digest: String = hasher
                .finalize()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect();
            if !digest.eq_ignore_ascii_case(sha256.trim()) {
                let _ = std::fs::remove_file(&path);
                return failed("校验失败（SHA-256 不一致），已中止".to_owned());
            }
        }
        self.state
            .set(State::Ready(path.to_string_lossy().into_owned()));
    }

    /// Hands the checked file to the system installer; reports a refusal.
    pub fn install(&self, path: String) {
        let this = self.clone();
        std::mem::forget(spawn(async move {
            if open_path(path).await.is_err() {
                this.state.set(State::Failed(
                    "系统没有接住安装请求，请到下载页手动安装".to_owned(),
                ));
            }
        }));
    }
}

/// `12.3 MB`, for the progress line.
pub fn human(bytes: u64) -> String {
    format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
}
