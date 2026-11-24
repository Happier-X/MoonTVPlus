//! Local HTTP proxy for HLS playback: injects the site's Bearer token and UA,
//! passes Range through, and rewrites playlist URIs to 127.0.0.1.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;

use url::Url;
use urlencoding::{decode, encode};

#[derive(Clone)]
pub struct ProxyConfig {
    pub site: String,
    pub token: String,
    pub ua: String,
}

pub fn start(site: String, token: String) -> std::io::Result<u16> {
    let listener = TcpListener::bind("127.0.0.1:0")?;
    let port = listener.local_addr()?.port();
    let config = Arc::new(ProxyConfig {
        site: site.trim_end_matches('/').to_owned(),
        token,
        ua: "MoonTVPlus Rust/0.1 (Android)".to_owned(),
    });
    thread::spawn(move || {
        eprintln!("moontv proxy listening on {port}");
        for stream in listener.incoming().flatten() {
            let config = config.clone();
            thread::spawn(move || {
                let _ = handle(stream, config, port);
            });
        }
    });
    Ok(port)
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

fn handle(mut stream: TcpStream, config: Arc<ProxyConfig>, port: u16) -> std::io::Result<()> {
    let request = read_request(&mut stream)?;
    if request.method != "GET" {
        return respond(&mut stream, 405, "text/plain", &[], b"method not allowed");
    }

    if request.path.starts_with("/a.m3u8") {
        let Some(original) = query_value(&request.path, "u").or_else(|| query_value(&request.path, "url")) else {
            return respond(&mut stream, 400, "text/plain", &[], b"missing url");
        };
        let playlist_url = format!(
            "{}/api/proxy-m3u8?url={}&source=directplay",
            config.site,
            encode(&original)
        );
        let (status, content_type, _range_hint, body) = fetch(&playlist_url, &config, None)?;
        if status != 200 {
            eprintln!("proxy GET {} -> {} {}", request.path, status, body.len());
            return respond(&mut stream, status, &content_type, &[], &body);
        }
        let text = String::from_utf8_lossy(&body).into_owned();
        let rewritten = rewrite_playlist(&text, &original, &config.site, port);
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
        let (status, content_type, content_range, body) = fetch(&target, &config, request.range.as_deref())?;
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

#[cfg(test)]
mod tests {
    use super::rewrite_playlist;

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
