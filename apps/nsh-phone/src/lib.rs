//! Rust side of the MoonTVPlus Native Shell spike.
//!
//! \`Video\` in NSH has no request-header prop, so HLS playlists and segments
//! that need \`Authorization: Bearer <token>\` cannot be played directly.
//! \`Proxy.start\` runs a tiny HTTP proxy on 127.0.0.1 that adds the headers,
//! rewrites playlist URIs back to itself, and serves the media to \`Video\`.
//!
//! The proxy uses \`ureq\` on its own threads instead of NSH's \`fetch\`:
//! NSH's fetch future is not \`Send\`, and a long-lived engine task is
//! cancelled with the owner that spawned it.

use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};

use nsh_core::prelude::*;
use url::Url;

/// The crate's UI set (\`nsh.json\` \`"rust"\`).
#[native_module("moontvplusspike/native")]
fn native(registry: &mut Registry) {
    registry.module::<Proxy>().module::<Http>();
}

#[derive(Clone)]
struct ProxyConfig {
    server: String,
    token: String,
}

struct Upstream {
    status: u16,
    content_type: Option<String>,
    content_range: Option<String>,
    body: Vec<u8>,
}

static CONFIG: OnceLock<Arc<Mutex<ProxyConfig>>> = OnceLock::new();
static PORT: OnceLock<u16> = OnceLock::new();

const UA: &str = "MoonTVPlus App/1.9.16 (Android) moontvplus app";

/// A small authenticated HTTP client for the app's own API reads.
#[module]
pub struct Http;

#[module]
impl Http {
    /// GETs the URL with the proxy's headers (Bearer for the site); the body.
    pub async fn get(url: String) -> Result<String, String> {
        blocking(move || {
            let response = upstream(&url, None)?;
            if response.status >= 400 {
                return Err(format!("HTTP {}", response.status));
            }
            Ok(String::from_utf8_lossy(&response.body).into_owned())
        })
        .await
        .map_err(|error| error.to_string())?
    }
}

/// The local HTTP proxy for authenticated HLS and posters.
#[module]
pub struct Proxy;

#[module]
impl Proxy {
    /// Starts (or reconfigures) the proxy; returns the 127.0.0.1 port.
    pub fn start(server: String, token: String) -> Result<u16, String> {
        if let Some(port) = PORT.get() {
            if let Some(config) = CONFIG.get() {
                if let Ok(mut guard) = config.lock() {
                    *guard = ProxyConfig { server, token };
                }
            }
            return Ok(*port);
        }

        let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|e| e.to_string())?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let _ = CONFIG.set(Arc::new(Mutex::new(ProxyConfig { server, token })));
        let _ = PORT.set(port);

        std::thread::spawn(move || {
            log::info!("proxy: listening on 127.0.0.1:{port}");
            for stream in listener.incoming() {
                match stream {
                    Ok(stream) => {
                        std::thread::spawn(move || {
                            if let Err(error) = handle(stream) {
                                log::warn!("proxy: {error}");
                            }
                        });
                    }
                    Err(error) => log::warn!("proxy: accept: {error}"),
                }
            }
        });

        Ok(port)
    }
}

fn handle(mut stream: TcpStream) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .ok();
    stream
        .set_write_timeout(Some(Duration::from_secs(30)))
        .ok();

    let mut buffer = vec![0_u8; 16 * 1024];
    let mut filled = 0;
    let head_end;
    loop {
        if filled == buffer.len() {
            return Err("request head too large".into());
        }
        let read = stream
            .read(&mut buffer[filled..])
            .map_err(|e| e.to_string())?;
        if read == 0 {
            return Ok(());
        }
        filled += read;
        if let Some(index) = find_head_end(&buffer[..filled]) {
            head_end = index;
            break;
        }
    }

    let head = String::from_utf8_lossy(&buffer[..head_end]).into_owned();
    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let target = parts.next().unwrap_or("/").to_string();
    let mut range: Option<String> = None;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("range") {
                range = Some(value.trim().to_string());
            }
        }
    }

    let response = route(&method, &target, range.as_deref());
    stream
        .write_all(&response)
        .map_err(|e| e.to_string())?;
    let _ = stream.flush();
    Ok(())
}

fn find_head_end(bytes: &[u8]) -> Option<usize> {
    bytes.windows(4).position(|w| w == b"\r\n\r\n")
}

fn route(method: &str, target: &str, range: Option<&str>) -> Vec<u8> {
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    log::info!("proxy: {method} {path}");
    let params = parse_query(query);
    let get = |name: &str| {
        params
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.clone())
    };

    match path {
        "/health" => text_response(200, "text/plain", b"ok"),
        "/hls" => match get("u") {
            Some(url) => playlist_response(&url, method == "HEAD"),
            None => text_response(400, "text/plain", b"missing u"),
        },
        "/seg" => match get("u") {
            Some(url) => {
                let kind = segment_kind(&url);
                bytes_response(&url, range, method == "HEAD", Some(kind))
            }
            None => text_response(400, "text/plain", b"missing u"),
        },
        "/img" => match get("u") {
            Some(url) => bytes_response(&url, None, method == "HEAD", None),
            None => text_response(400, "text/plain", b"missing u"),
        },
        "/api" => match get("u") {
            Some(url) => bytes_response(&url, None, method == "HEAD", Some("application/json")),
            None => text_response(400, "text/plain", b"missing u"),
        },
        _ => text_response(404, "text/plain", b"not found"),
    }
}

fn playlist_response(url: &str, head_only: bool) -> Vec<u8> {
    let response = match upstream(url, None) {
        Ok(response) => response,
        Err(error) => return text_response(502, "text/plain", error.as_bytes()),
    };
    if response.status >= 400 {
        return text_response(
            response.status,
            "text/plain",
            format!("upstream {}", response.status).as_bytes(),
        );
    }

    let base = Url::parse(url).ok();
    let mut body = String::new();
    for line in String::from_utf8_lossy(&response.body).lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            body.push_str(line);
            body.push('\n');
        } else if trimmed.starts_with('#') {
            body.push_str(&rewrite_uri_attributes(line, base.as_ref()));
            body.push('\n');
        } else {
            let absolute = resolve_url(base.as_ref(), trimmed);
            body.push_str(&proxy_uri(&absolute));
            body.push('\n');
        }
    }

    let bytes = body.into_bytes();
    let mut out = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: {}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n",
        bytes.len()
    )
    .into_bytes();
    if !head_only {
        out.extend_from_slice(&bytes);
    }
    out
}

fn bytes_response(
    url: &str,
    range: Option<&str>,
    head_only: bool,
    content_type: Option<&str>,
) -> Vec<u8> {
    let response = match upstream(url, range) {
        Ok(response) => response,
        Err(error) => return text_response(502, "text/plain", error.as_bytes()),
    };
    let status = response.status;
    if status >= 400 {
        return text_response(status, "text/plain", format!("upstream {status}").as_bytes());
    }

    let content_type = content_type
        .map(|value| value.to_string())
        .or(response.content_type)
        .unwrap_or_else(|| "application/octet-stream".into());
    let content_range = response.content_range.unwrap_or_default();
    let body = &response.body;

    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n",
        if status == 206 { "Partial Content" } else { "OK" },
        body.len()
    );
    if !content_range.is_empty() {
        head.push_str(&format!("Content-Range: {content_range}\r\n"));
    }
    head.push_str("\r\n");
    let mut out = head.into_bytes();
    if !head_only {
        out.extend_from_slice(body);
    }
    out
}

fn upstream(url: &str, range: Option<&str>) -> Result<Upstream, String> {
    let parsed = Url::parse(url).map_err(|e| e.to_string())?;
    let config = CONFIG
        .get()
        .and_then(|config| config.lock().ok().map(|guard| guard.clone()))
        .ok_or_else(|| "proxy not configured".to_string())?;

    let agent: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(30)))
        .build()
        .into();
    let mut request = agent
        .get(url)
        .header("User-Agent", UA)
        .header("Accept", "*/*")
        .header("Accept-Encoding", "identity")
        .header(
            "Referer",
            format!(
                "{}://{}/",
                parsed.scheme(),
                parsed.host_str().unwrap_or_default()
            ),
        );
    if let Some(range) = range {
        request = request.header("Range", range);
    }

    let server_host = Url::parse(&config.server)
        .ok()
        .and_then(|server| server.host_str().map(|host| host.to_string()));
    if server_host.as_deref() == parsed.host_str() {
        request = request
            .header("Authorization", format!("Bearer {}", config.token))
            .header("Cookie", format!("auth={}", config.token));
    }

    let response = request.call().map_err(|e| e.to_string())?;
    let status = response.status().as_u16();
    let header = |name: &str| {
        response
            .headers()
            .get(name)
            .and_then(|value| value.to_str().ok())
            .map(|value| value.to_string())
    };
    let content_type = header("content-type");
    let content_range = header("content-range");
    let body = response
        .into_body()
        .read_to_vec()
        .map_err(|e| e.to_string())?;
    Ok(Upstream {
        status,
        content_type,
        content_range,
        body,
    })
}

fn parse_query(query: &str) -> Vec<(String, String)> {
    query
        .split('&')
        .filter(|part| !part.is_empty())
        .map(|part| {
            let (key, value) = part.split_once('=').unwrap_or((part, ""));
            (pct_decode(key), pct_decode(value))
        })
        .collect()
}

fn pct_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'%' if index + 2 < bytes.len() => {
                let hex = &value[index + 1..index + 3];
                match u8::from_str_radix(hex, 16) {
                    Ok(byte) => {
                        out.push(byte);
                        index += 3;
                    }
                    Err(_) => {
                        out.push(bytes[index]);
                        index += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                index += 1;
            }
            byte => {
                out.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn pct_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match *byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(*byte as char)
            }
            byte => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

fn is_playlist(url: &str) -> bool {
    let path = Url::parse(url)
        .map(|url| url.path().to_ascii_lowercase())
        .unwrap_or_else(|_| url.to_ascii_lowercase());
    path.ends_with(".m3u8") || path.ends_with(".m3u")
}

fn proxy_uri(url: &str) -> String {
    let kind = if is_playlist(url) { "hls" } else { "seg" };
    match PORT.get() {
        Some(port) => format!("http://127.0.0.1:{port}/{kind}?u={}", pct_encode(url)),
        None => format!("/{kind}?u={}", pct_encode(url)),
    }
}

fn resolve_url(base: Option<&Url>, value: &str) -> String {
    if value.starts_with("http://") || value.starts_with("https://") {
        value.to_string()
    } else if let Some(base) = base {
        base.join(value)
            .map(|url| url.to_string())
            .unwrap_or_else(|_| value.to_string())
    } else {
        value.to_string()
    }
}

fn rewrite_uri_attributes(line: &str, base: Option<&Url>) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    while let Some(start) = rest.find("URI=\"") {
        out.push_str(&rest[..start + 5]);
        rest = &rest[start + 5..];
        if let Some(end) = rest.find('"') {
            let value = &rest[..end];
            let absolute = resolve_url(base, value);
            out.push_str(&proxy_uri(&absolute));
            out.push('"');
            rest = &rest[end + 1..];
        } else {
            break;
        }
    }
    out.push_str(rest);
    out
}

fn segment_kind(url: &str) -> &'static str {
    let path = Url::parse(url)
        .map(|url| url.path().to_ascii_lowercase())
        .unwrap_or_else(|_| url.to_ascii_lowercase());
    if path.ends_with(".m4s") || path.ends_with(".mp4") || path.ends_with(".m4v") {
        "video/mp4"
    } else if path.ends_with(".aac") || path.ends_with(".m4a") {
        "audio/mp4"
    } else {
        "video/mp2t"
    }
}

fn text_response(status: u16, content_type: &str, body: &[u8]) -> Vec<u8> {
    let mut out = format!(
        "HTTP/1.1 {status} X\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .into_bytes();
    out.extend_from_slice(body);
    out
}

