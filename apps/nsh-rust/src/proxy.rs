//! Local HTTP proxy for HLS playback: injects the site's Bearer token and UA,
//! passes Range through, and rewrites playlist URIs to 127.0.0.1.

use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::path::PathBuf;
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use url::Url;
use urlencoding::{decode, encode};

#[derive(Clone)]
pub struct ProxyConfig {
    pub site: String,
    pub token: String,
    pub ua: String,
}

static IMAGE_MEMORY: Mutex<Option<HashMap<String, (String, Vec<u8>)>>> = Mutex::new(None);
static IMG_HITS: AtomicBool = AtomicBool::new(false);
static IMG_MISS: AtomicBool = AtomicBool::new(false);
static IMG_FAIL: AtomicBool = AtomicBool::new(false);
static IMG_COUNT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static STORE: Mutex<Option<Arc<Mutex<Option<String>>>>> = Mutex::new(None);

/// Counters for the image endpoint, for on-device checks.
pub fn image_stats() -> (u32, bool, bool, bool) {
    (
        IMG_COUNT.load(Ordering::Relaxed),
        IMG_HITS.load(Ordering::Relaxed),
        IMG_MISS.load(Ordering::Relaxed),
        IMG_FAIL.load(Ordering::Relaxed),
    )
}

pub fn reset_image_stats() {
    IMG_COUNT.store(0, Ordering::Relaxed);
    IMG_HITS.store(false, Ordering::Relaxed);
    IMG_MISS.store(false, Ordering::Relaxed);
    IMG_FAIL.store(false, Ordering::Relaxed);
}

fn cache_dir() -> PathBuf {
    std::env::temp_dir().join("moontv-rust-images")
}

fn disk_key(url: &str) -> String {
    // FNV-1a, stable across runs and cheap.
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in url.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("{hash:016x}")
}

fn memory_get(url: &str) -> Option<(String, Vec<u8>)> {
    IMAGE_MEMORY
        .lock()
        .ok()
        .and_then(|guard| guard.as_ref().and_then(|map| map.get(url).cloned()))
}

fn memory_put(url: &str, content_type: String, body: Vec<u8>) {
    if let Ok(mut guard) = IMAGE_MEMORY.lock() {
        let map = guard.get_or_insert_with(HashMap::new);
        if map.len() > 200 {
            map.clear();
        }
        map.insert(url.to_owned(), (content_type, body));
    }
}

fn disk_get(url: &str) -> Option<(String, Vec<u8>)> {
    let dir = cache_dir();
    let type_path = dir.join(format!("{}.type", disk_key(url)));
    let body_path = dir.join(format!("{}.bin", disk_key(url)));
    let content_type = fs::read_to_string(type_path).ok()?;
    let body = fs::read(body_path).ok()?;
    Some((content_type, body))
}

fn disk_put(url: &str, content_type: &str, body: &[u8]) {
    let dir = cache_dir();
    let _ = fs::create_dir_all(&dir);
    let _ = fs::write(dir.join(format!("{}.type", disk_key(url))), content_type);
    let _ = fs::write(dir.join(format!("{}.bin", disk_key(url))), body);
}

struct Instance {
    site: String,
    token: String,
    port: u16,
    stop: Arc<AtomicBool>,
}

static INSTANCE: Mutex<Option<Instance>> = Mutex::new(None);

/// One proxy per app process: the same site and token reuse the running port.
pub fn ensure(site: String, token: String) -> std::io::Result<u16> {
    let site = site.trim_end_matches('/').to_owned();
    {
        let guard = INSTANCE.lock().unwrap();
        if let Some(instance) = guard.as_ref() {
            if instance.site == site && instance.token == token {
                return Ok(instance.port);
            }
        }
    }
    stop();
    let listener = TcpListener::bind("127.0.0.1:0")?;
    let port = listener.local_addr()?.port();
    listener.set_nonblocking(true)?;
    let config = Arc::new(ProxyConfig {
        site: site.clone(),
        token: token.clone(),
        ua: "MoonTVPlus Rust/0.1 (Android)".to_owned(),
    });
    let stop = Arc::new(AtomicBool::new(false));
    let stop_thread = stop.clone();
    thread::spawn(move || {
        while !stop_thread.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, _)) => {
                    let config = config.clone();
                    thread::spawn(move || {
                        let _ = handle(stream, config, port);
                    });
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(100));
                }
                Err(_) => break,
            }
        }
        // Dropping the listener here releases the port.
    });
    *INSTANCE.lock().unwrap() = Some(Instance {
        site,
        token,
        port,
        stop,
    });
    Ok(port)
}

pub fn stop() {
    if let Some(instance) = INSTANCE.lock().unwrap().take() {
        instance.stop.store(true, Ordering::Relaxed);
    }
}

struct Request {
    method: String,
    path: String,
    range: Option<String>,
}

fn read_request(stream: &mut TcpStream) -> std::io::Result<Request> {
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 1024];
    while !buffer.windows(4).any(|w| w == b"\r\n\r\n") {
        let read = stream.read(&mut chunk)?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.len() > 64 * 1024 {
            break;
        }
    }
    let text = String::from_utf8_lossy(&buffer);
    let mut lines = text.split("\r\n");
    let first = lines.next().unwrap_or_default();
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_owned();
    let path = parts.next().unwrap_or("/").to_owned();
    let mut range = None;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("range") {
                range = Some(value.trim().to_owned());
            }
        }
    }
    Ok(Request { method, path, range })
}

fn query_value(path: &str, key: &str) -> Option<String> {
    let query = path.split_once('?')?.1;
    for pair in query.split('&') {
        let (name, value) = pair.split_once('=')?;
        if name == key {
            return decode(value).ok().map(|v| v.into_owned());
        }
    }
    None
}

fn fetch_playlist(original: &str, config: &ProxyConfig) -> std::io::Result<(u16, String, Vec<u8>)> {
    let playlist_url = format!(
        "{}/api/proxy-m3u8?url={}&source=directplay",
        config.site,
        encode(original)
    );
    let (status, content_type, _range_hint, body) = fetch(&playlist_url, config, None)?;
    Ok((status, content_type, body))
}

#[derive(Clone)]
struct Variant {
    label: String,
    height: u32,
    url: String,
}

/// Reads the variants of a master playlist, resolving relative URIs against it.
fn variants(text: &str, original: &str) -> Vec<Variant> {
    let base = Url::parse(original).ok();
    let mut out = Vec::new();
    let mut pending: Option<(String, u32)> = None;
    for line in text.lines() {
        let line = line.trim();
        if let Some(info) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            let height = info.split(',').find_map(|part| part.strip_prefix("RESOLUTION="))
                .and_then(|value| value.split('x').nth(1))
                .and_then(|value| value.parse::<u32>().ok())
                .unwrap_or(0);
            let bandwidth = info.split(',').find_map(|part| part.strip_prefix("BANDWIDTH="))
                .and_then(|value| value.parse::<u32>().ok())
                .unwrap_or(0);
            let label = if height > 0 { format!("{height}p") }
                else if bandwidth > 0 { format!("{}kbps", bandwidth / 1000) } else { "自动".to_owned() };
            pending = Some((label, height));
        } else if !line.is_empty() && !line.starts_with('#') {
            if let Some((label, height)) = pending.take() {
                let url = match &base {
                    Some(base) => absolute(line, base),
                    None => line.to_owned(),
                };
                out.push(Variant { label, height, url });
            }
        }
    }
    out
}

fn default_variant(list: &[Variant]) -> usize {
    list.iter().enumerate().max_by_key(|(_, variant)| variant.height).map(|(index, _)| index).unwrap_or(0)
}

fn handle(mut stream: TcpStream, config: Arc<ProxyConfig>, port: u16) -> std::io::Result<()> {
    let request = read_request(&mut stream)?;
    if request.method != "GET" {
        return respond(&mut stream, 405, "text/plain", &[], b"method not allowed");
    }

    if request.path.starts_with("/img") {
        let Some(target) = query_value(&request.path, "u") else {
            return respond(&mut stream, 400, "text/plain", &[], b"missing url");
        };
        IMG_COUNT.fetch_add(1, Ordering::Relaxed);
        if let Some((content_type, body)) = memory_get(&target) {
            IMG_HITS.store(true, Ordering::Relaxed);
            eprintln!("img hit memory {} {}", body.len(), target);
            return respond(&mut stream, 200, &content_type, &[("X-Cache", "memory".to_owned())], &body);
        }
        if let Some((content_type, body)) = disk_get(&target) {
            IMG_HITS.store(true, Ordering::Relaxed);
            eprintln!("img hit disk {} {}", body.len(), target);
            memory_put(&target, content_type.clone(), body.clone());
            return respond(&mut stream, 200, &content_type, &[("X-Cache", "disk".to_owned())], &body);
        }
        return match fetch_image(&target, &config) {
            Some((content_type, body)) => {
                IMG_MISS.store(true, Ordering::Relaxed);
                eprintln!("img miss {} {}", body.len(), target);
                memory_put(&target, content_type.clone(), body.clone());
                disk_put(&target, &content_type, &body);
                respond(&mut stream, 200, &content_type, &[("X-Cache", "miss".to_owned())], &body)
            }
            None => {
                IMG_FAIL.store(true, Ordering::Relaxed);
                eprintln!("img fail {target}");
                respond(&mut stream, 502, "text/plain", &[], b"image fetch failed")
            }
        };
    }

    if request.path.starts_with("/qualities") {
        let Some(original) = query_value(&request.path, "u").or_else(|| query_value(&request.path, "url")) else {
            return respond(&mut stream, 400, "text/plain", &[], b"missing url");
        };
        let (status, _content_type, body) = fetch_playlist(&original, &config)?;
        if status != 200 {
            return respond(&mut stream, status, "text/plain", &[], &body);
        }
        let text = String::from_utf8_lossy(&body);
        let list = variants(&text, &original);
        let default = default_variant(&list);
        let json = serde_json::json!({
            "default": default,
            "variants": list.iter().map(|variant| serde_json::json!({
                "label": variant.label,
                "height": variant.height,
            })).collect::<Vec<_>>(),
        });
        return respond(&mut stream, 200, "application/json", &[], json.to_string().as_bytes());
    }

    if request.path.starts_with("/a.m3u8") {
        let Some(original) = query_value(&request.path, "u").or_else(|| query_value(&request.path, "url")) else {
            return respond(&mut stream, 400, "text/plain", &[], b"missing url");
        };
        let (status, content_type, body) = fetch_playlist(&original, &config)?;
        if status != 200 {
            eprintln!("proxy GET {} -> {} {}", request.path, status, body.len());
            return respond(&mut stream, status, &content_type, &[], &body);
        }
        let mut text = String::from_utf8_lossy(&body).into_owned();
        let mut base = original.clone();
        let list = variants(&text, &original);
        if !list.is_empty() {
            let picked = query_value(&request.path, "q")
                .and_then(|value| value.parse::<usize>().ok())
                .filter(|index| *index < list.len())
                .unwrap_or_else(|| default_variant(&list));
            base = list[picked].url.clone();
            let (status, content_type, body) = fetch_playlist(&base, &config)?;
            if status != 200 {
                eprintln!("proxy GET {} variant {} -> {} {}", request.path, picked, status, body.len());
                return respond(&mut stream, status, &content_type, &[], &body);
            }
            text = String::from_utf8_lossy(&body).into_owned();
        }
        let rewritten = rewrite_playlist(&text, &base, &config.site, port);
        eprintln!("proxy GET {} -> 200 {}", request.path, rewritten.len());
        return respond(
            &mut stream,
            200,
            "application/vnd.apple.mpegurl",
            &[],
            rewritten.as_bytes(),
        );
    }

    if request.path.starts_with("/b.ts") {
        let Some(target) = query_value(&request.path, "u").or_else(|| query_value(&request.path, "url")) else {
            return respond(&mut stream, 400, "text/plain", &[], b"missing url");
        };
        let (mut status, content_type, mut content_range, mut body) =
            fetch(&target, &config, request.range.as_deref())?;
        // Some sources wrap every segment in a JPEG header ("image masking"):
        // the payload is `FFD8 … FFD9` and then plain MPEG-TS packets, which a
        // strict platform parser rejects. Strip the wrapper before answering.
        if is_image_masked(&body) {
            let (whole_status, _, _, whole) = fetch(&target, &config, None)?;
            let stripped = strip_image_prefix(whole);
            let asked = request
                .range
                .as_deref()
                .or(if status == 206 { content_range.as_deref() } else { None });
            let (part, range_header) = slice_range(stripped, asked);
            body = part;
            content_range = range_header;
            status = if status == 206 || content_range.is_some() { 206 } else { whole_status };
        }
        let segment_type = if content_type.contains("mp4") { "video/mp4" } else { "video/mp2t" };
        let mut extra: Vec<(&str, String)> = vec![("Accept-Ranges", "bytes".to_owned())];
        if let Some(value) = content_range {
            extra.push(("Content-Range", value));
        }
        eprintln!("proxy GET {} range={:?} -> {} {} {}", request.path, request.range, status, segment_type, body.len());
        return respond(&mut stream, status, segment_type, &extra, &body);
    }

    respond(&mut stream, 404, "text/plain", &[], b"not found")
}

fn fetch(
    target: &str,
    config: &ProxyConfig,
    range: Option<&str>,
) -> std::io::Result<(u16, String, Option<String>, Vec<u8>)> {
    let mut request = ureq::get(target)
        .set("User-Agent", &config.ua)
        .set("Accept", "*/*");
    if target.starts_with(&config.site) {
        request = request.set("Authorization", &format!("Bearer {}", config.token));
    }
    if let Some(value) = range {
        request = request.set("Range", value);
    }
    let result = request.call();
    if let Err(ref error) = result { eprintln!("moontv proxy fetch error {target}: {error}"); }
    let response = match result {
        Ok(response) => response,
        Err(ureq::Error::Status(_, response)) => response,
        Err(error) => {
            return Ok((502, "text/plain".to_owned(), None, error.to_string().into_bytes()));
        }
    };
    let status = response.status();
    let content_type = response
        .header("content-type")
        .unwrap_or("application/octet-stream")
        .to_owned();
    let content_range = response.header("content-range").map(|value| value.to_owned());
    let mut body = Vec::new();
    response.into_reader().read_to_end(&mut body)?;
    Ok((status, content_type, content_range, body))
}

fn fetch_image(target: &str, config: &ProxyConfig) -> Option<(String, Vec<u8>)> {
    let mut request = ureq::get(target)
        .set("User-Agent", &config.ua)
        .set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
    if target.starts_with(&config.site) {
        request = request.set("Authorization", &format!("Bearer {}", config.token));
    }
    let referer = referer_for_image(target);
    if !referer.is_empty() {
        request = request.set("Referer", &referer);
    }

    let response = match request.call() {
        Ok(response) => response,
        Err(ureq::Error::Status(_, response)) => response,
        Err(_) => return None,
    };
    if response.status() != 200 {
        return None;
    }
    let content_type = response
        .header("content-type")
        .unwrap_or("image/jpeg")
        .to_owned();
    let mut body = Vec::new();
    response.into_reader().read_to_end(&mut body).ok()?;
    if body.is_empty() {
        return None;
    }
    Some((content_type, body))
}

/// A segment that starts with a JPEG SOI marker is image-masked.
fn is_image_masked(body: &[u8]) -> bool {
    body.len() > 4 && body[0] == 0xFF && body[1] == 0xD8
}

/// Drops the JPEG wrapper and returns the MPEG-TS payload that follows it.
fn strip_image_prefix(body: Vec<u8>) -> Vec<u8> {
    const PACKET: usize = 188;
    if !is_image_masked(&body) {
        return body;
    }
    let limit = body.len().saturating_sub(PACKET * 2).min(4096);
    for index in 0..limit {
        if body[index] == 0x47
            && body[index + PACKET] == 0x47
            && body[index + PACKET * 2] == 0x47
        {
            return body[index..].to_vec();
        }
    }
    body
}

/// Applies a `bytes=start-end` range to an already stripped segment.
fn slice_range(body: Vec<u8>, range: Option<&str>) -> (Vec<u8>, Option<String>) {
    let Some(header) = range.and_then(|value| value.trim().strip_prefix("bytes=")) else {
        return (body, None);
    };
    let Some((start, end)) = header.split_once('-') else {
        return (body, None);
    };
    let Ok(start) = start.trim().parse::<usize>() else {
        return (body, None);
    };
    let end = end
        .trim()
        .parse::<usize>()
        .map(|value| value.min(body.len().saturating_sub(1)))
        .unwrap_or_else(|_| body.len().saturating_sub(1));
    if start >= body.len() || start > end {
        return (body, None);
    }
    let total = body.len();
    let part = body[start..=end].to_vec();
    (part, Some(format!("bytes {start}-{end}/{total}")))
}

fn respond(
    stream: &mut TcpStream,
    status: u16,
    content_type: &str,
    extra: &[(&str, String)],
    body: &[u8],
) -> std::io::Result<()> {
    let reason = match status {
        200 => "OK",
        206 => "Partial Content",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        _ => "Error",
    };
    let mut head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n",
        body.len()
    );
    for (name, value) in extra {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes())?;
    stream.write_all(body)?;
    stream.flush()
}

fn absolute(uri: &str, base: &Url) -> String {
    if uri.starts_with("http://") || uri.starts_with("https://") {
        return uri.to_owned();
    }
    base.join(uri)
        .map(|url| url.to_string())
        .unwrap_or_else(|_| uri.to_owned())
}

/// Rewrites every URI in a site-rewritten playlist to the local proxy.
/// Handles plain URIs, EXT-X-MEDIA/KEY/MAP URI attributes and byte ranges
/// (the player turns EXT-X-BYTERANGE into a Range request, which we pass on).
fn rewrite_playlist(text: &str, original: &str, _site: &str, port: u16) -> String {
    let base = Url::parse(original).ok();
    let local = |target: &str| {
        let path = if target.contains(".m3u8") { "a.m3u8" } else { "b.ts" };
        format!(
            "http://127.0.0.1:{}/{}?u={}",
            port,
            path,
            encode(target)
        )
    };
    let mut output = Vec::new();
    for line in text.split('\n') {
        let trimmed = line.trim_end_matches('\r');
        if trimmed.starts_with("#EXT-X-MEDIA:")
            || trimmed.starts_with("#EXT-X-KEY:")
            || trimmed.starts_with("#EXT-X-MAP:")
        {
            if let Some(start) = trimmed.find("URI=\"") {
                let rest = &trimmed[start + 5..];
                if let Some(end) = rest.find('"') {
                    let uri = &rest[..end];
                    let target = match &base {
                        Some(base) => absolute(uri, base),
                        None => uri.to_owned(),
                    };
                    let replaced = format!(
                        "{}{}\"{}",
                        &trimmed[..start + 5],
                        local(&target),
                        &rest[end + 1..]
                    );
                    output.push(replaced);
                    continue;
                }
            }
            output.push(trimmed.to_owned());
            continue;
        }
        if trimmed.starts_with('#') || trimmed.trim().is_empty() {
            output.push(trimmed.to_owned());
            continue;
        }
        let target = match &base {
            Some(base) => absolute(trimmed.trim(), base),
            None => trimmed.trim().to_owned(),
        };
        output.push(local(&target));
    }
    output.join("\n")
}

/// Picks the Referer an image host expects: Douban CDNs want
/// movie.douban.com, Bangumi wants bgm.tv, everything else its own origin.
fn referer_for_image(target: &str) -> String {
    let url = match Url::parse(target) {
        Ok(url) => url,
        Err(_) => return String::new(),
    };
    let host = url.host_str().unwrap_or("");
    if host.contains("douban") {
        return "https://movie.douban.com/".to_owned();
    }
    if host.contains("bgm.tv") || host.contains("bangumi") {
        return "https://bgm.tv/".to_owned();
    }
    let origin = url.origin().ascii_serialization();
    if origin == "null" {
        String::new()
    } else {
        format!("{}/", origin)
    }
}

#[cfg(test)]
mod tests {
    use super::{referer_for_image, rewrite_playlist};

    #[test]
    fn referer_matches_source_host() {
        assert_eq!(
            referer_for_image("https://img9.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"),
            "https://movie.douban.com/"
        );
        assert_eq!(
            referer_for_image("https://lain.bgm.tv/pic/cover/l/1.jpg"),
            "https://bgm.tv/"
        );
        assert_eq!(
            referer_for_image("https://cdn.example.com/poster/x.jpg"),
            "https://cdn.example.com/"
        );
    }

    #[test]
    fn rewrites_plain_and_tag_uris() {
        let text = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/a.m3u8\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-BYTERANGE:1000@0\nseg-1.ts\nhttps://cdn.example.com/seg-2.ts\n";
        let out = rewrite_playlist(
            text,
            "https://cdn.example.com/play/index.m3u8",
            "https://tv.example.com",
            4567,
        );
        assert!(out.contains("URI=\"http://127.0.0.1:4567/a.m3u8?u=https%3A%2F%2Fcdn.example.com%2Fplay%2Faudio%2Fa.m3u8\""));
        assert!(out.contains("URI=\"http://127.0.0.1:4567/b.ts?u=https%3A%2F%2Fcdn.example.com%2Fplay%2Finit.mp4\""));
        assert!(out.contains("#EXT-X-BYTERANGE:1000@0"));
        assert!(out.contains("http://127.0.0.1:4567/b.ts?u=https%3A%2F%2Fcdn.example.com%2Fplay%2Fseg-1.ts"));
        assert!(out.contains("http://127.0.0.1:4567/b.ts?u=https%3A%2F%2Fcdn.example.com%2Fseg-2.ts"));
    }
}
