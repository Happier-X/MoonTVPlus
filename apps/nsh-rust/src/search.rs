//! Per-source streaming search: `/api/search/ws` is an SSE endpoint, and the
//! host `fetch` cannot read a body incrementally, so the stream is consumed on
//! a plain thread with `ureq` and drained into the UI through a shared queue.

use std::{
    io::{BufRead, BufReader},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

use serde_json::Value;

const CACHE_TTL: Duration = Duration::from_secs(5 * 60);

#[derive(Clone, PartialEq, Default, Debug)]
pub struct SearchItem {
    pub id: String,
    pub title: String,
    pub poster: String,
    pub year: String,
    pub type_name: String,
    pub remarks: String,
    pub source: String,
    pub source_name: String,
    pub episodes: usize,
}

#[derive(Clone, PartialEq, Default, Debug)]
pub struct SearchGroup {
    pub source: String,
    pub name: String,
    /// `done`, `error` or `timeout`.
    pub status: String,
    pub message: String,
    pub items: Vec<SearchItem>,
}

#[derive(Clone, PartialEq, Default, Debug)]
pub struct SearchSnapshot {
    pub query: String,
    pub total: usize,
    pub done: usize,
    pub complete: bool,
    pub error: String,
    pub groups: Vec<SearchGroup>,
    /// Time from starting the stream to the first source with results.
    pub first_ms: u64,
    /// Time from starting the stream to the complete event.
    pub total_ms: u64,
    /// True when the snapshot came from the five minute cache.
    pub cached: bool,
}

impl SearchSnapshot {
    pub fn result_count(&self) -> usize {
        self.groups.iter().map(|group| group.items.len()).sum()
    }
}

pub enum Msg {
    Start(usize),
    Group(SearchGroup),
    Complete,
    Failed(String),
}

pub struct Handle {
    queue: Arc<Mutex<Vec<Msg>>>,
    cancel: Arc<AtomicBool>,
}

impl Handle {
    pub fn drain(&self) -> Vec<Msg> {
        let mut queue = self.queue.lock().unwrap();
        std::mem::take(&mut *queue)
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        self.cancel();
    }
}

struct CacheEntry {
    key: String,
    at: Instant,
    snapshot: SearchSnapshot,
}

static CACHE: Mutex<Vec<CacheEntry>> = Mutex::new(Vec::new());

fn cache_key(query: &str) -> String {
    query.trim().to_lowercase()
}

pub fn cache_get(query: &str) -> Option<SearchSnapshot> {
    let key = cache_key(query);
    let cache = CACHE.lock().unwrap();
    cache
        .iter()
        .find(|entry| entry.key == key && entry.at.elapsed() < CACHE_TTL)
        .map(|entry| entry.snapshot.clone())
}

pub fn cache_put(query: &str, snapshot: &SearchSnapshot) {
    let key = cache_key(query);
    let mut cache = CACHE.lock().unwrap();
    cache.retain(|entry| entry.key != key && entry.at.elapsed() < CACHE_TTL);
    cache.insert(
        0,
        CacheEntry {
            key,
            at: Instant::now(),
            snapshot: snapshot.clone(),
        },
    );
    cache.truncate(8);
}

/// Source names carry emoji the Android font cannot draw; drop them so the
/// list never shows tofu boxes.
fn clean_name(name: &str) -> String {
    let filtered: String = name
        .chars()
        .filter(|value| {
            let code = *value as u32;
            !(0x1F000..=0x1FAFF).contains(&code)
                && !(0x2600..=0x27BF).contains(&code)
                && !(0x2B00..=0x2BFF).contains(&code)
                && !(0x2190..=0x21FF).contains(&code)
                && code != 0xFE0F
        })
        .collect();
    filtered.trim().to_owned()
}

pub fn parse_item(value: &Value) -> Option<SearchItem> {
    let title = value["title"].as_str().unwrap_or_default().trim().to_owned();
    if title.is_empty() {
        return None;
    }
    Some(SearchItem {
        id: value["id"]
            .as_str()
            .map(str::to_owned)
            .or_else(|| value["id"].as_u64().map(|id| id.to_string()))
            .unwrap_or_default(),
        title,
        poster: value["poster"].as_str().unwrap_or_default().to_owned(),
        year: value["year"]
            .as_str()
            .map(str::to_owned)
            .or_else(|| value["year"].as_u64().map(|year| year.to_string()))
            .unwrap_or_default(),
        type_name: value["type_name"].as_str().unwrap_or_default().to_owned(),
        remarks: value["vod_remarks"].as_str().unwrap_or_default().to_owned(),
        source: value["source"].as_str().unwrap_or_default().to_owned(),
        source_name: value["source_name"].as_str().unwrap_or_default().to_owned(),
        episodes: value["episodes"].as_array().map(Vec::len).unwrap_or(0),
    })
}

fn apply(queue: &Mutex<Vec<Msg>>, payload: &str) {
    let Ok(value) = serde_json::from_str::<Value>(payload) else {
        return;
    };
    let mut queue = queue.lock().unwrap();
    match value["type"].as_str().unwrap_or_default() {
        "start" => queue.push(Msg::Start(value["totalSources"].as_u64().unwrap_or(0) as usize)),
        "source_result" => {
            let items = value["results"]
                .as_array()
                .map(|list| list.iter().filter_map(parse_item).collect())
                .unwrap_or_default();
            queue.push(Msg::Group(SearchGroup {
                source: value["source"].as_str().unwrap_or_default().to_owned(),
                name: clean_name(value["sourceName"].as_str().unwrap_or_default()),
                status: "done".to_owned(),
                message: String::new(),
                items,
            }));
        }
        "source_error" => {
            let message = value["error"].as_str().unwrap_or("搜索失败").to_owned();
            let status = if message.to_lowercase().contains("timeout") {
                "timeout"
            } else {
                "error"
            };
            queue.push(Msg::Group(SearchGroup {
                source: value["source"].as_str().unwrap_or_default().to_owned(),
                name: clean_name(value["sourceName"].as_str().unwrap_or_default()),
                status: status.to_owned(),
                message,
                items: Vec::new(),
            }));
        }
        "complete" => queue.push(Msg::Complete),
        _ => {}
    }
}

/// Starts the SSE search and returns the queue the UI drains.
pub fn start(server: &str, token: &str, query: &str) -> Handle {
    let queue = Arc::new(Mutex::new(Vec::new()));
    let cancel = Arc::new(AtomicBool::new(false));
    let handle = Handle {
        queue: queue.clone(),
        cancel: cancel.clone(),
    };
    let url = format!(
        "{}/api/search/ws?q={}",
        server.trim_end_matches('/'),
        urlencoding::encode(query)
    );
    let token = token.to_owned();
    std::thread::spawn(move || {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(8))
            .timeout(Duration::from_secs(45))
            .build();
        let request = agent
            .get(&url)
            .set("Accept", "text/event-stream")
            .set("User-Agent", "moontvplus native");
        let request = if token.is_empty() {
            request
        } else {
            request.set("Authorization", &format!("Bearer {token}"))
        };
        let response = match request.call() {
            Ok(response) => response,
            Err(error) => {
                let message = match error {
                    ureq::Error::Status(status, _) => format!("搜索服务返回 {status}"),
                    other => format!("搜索失败：{other}"),
                };
                queue.lock().unwrap().push(Msg::Failed(message));
                return;
            }
        };
        let reader = BufReader::new(response.into_reader());
        for line in reader.lines() {
            if cancel.load(Ordering::Relaxed) {
                return;
            }
            let Ok(line) = line else { break };
            if let Some(payload) = line.strip_prefix("data:") {
                apply(&queue, payload.trim());
            }
        }
        if !cancel.load(Ordering::Relaxed) {
            queue.lock().unwrap().push(Msg::Complete);
        }
    });
    handle
}
