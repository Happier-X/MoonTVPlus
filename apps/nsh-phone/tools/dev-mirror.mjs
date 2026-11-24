import http from "node:http";
import fs from "node:fs";
import crypto from "node:crypto";
import os from "node:os";
import path from "node:path";

const UPSTREAM_HOST = "tv.370125.xyz";
const PORT = 9000;
const CACHE_DIR = path.join(os.tmpdir(), "moontv-mirror-cache");
const CACHE_TTL = 30 * 60 * 1000;
fs.mkdirSync(CACHE_DIR, { recursive: true });

function cachePath(url) {
  return path.join(CACHE_DIR, crypto.createHash("sha256").update(url).digest("hex") + ".json");
}

function readCache(url) {
  try {
    const file = cachePath(url);
    if (!fs.existsSync(file)) return null;
    const entry = JSON.parse(fs.readFileSync(file, "utf8"));
    if (Date.now() - entry.at > CACHE_TTL) return null;
    return entry;
  } catch {
    return null;
  }
}

function writeCache(url, status, headers, body) {
  try {
    fs.writeFileSync(cachePath(url), JSON.stringify({
      at: Date.now(),
      status,
      headers,
      body: body.toString("base64"),
    }));
  } catch {
    // cache is best-effort
  }
}
const UPDATE_APK = process.env.MOCK_UPDATE_APK || "";
const UPDATE_APK_NAME = "moontvplus-phone-1.9.20.apk";
let UPDATE_SIZE = 0;
let UPDATE_SHA = "";
if (process.env.MOCK_UPDATE === "1" && UPDATE_APK && fs.existsSync(UPDATE_APK)) {
  const data = fs.readFileSync(UPDATE_APK);
  UPDATE_SIZE = data.length;
  UPDATE_SHA = process.env.MOCK_UPDATE_BAD_SHA === "1" ? "0".repeat(64) : crypto.createHash("sha256").update(data).digest("hex");
}

const server = http.createServer((req, res) => {
  if (req.url === "/test.srt") {
    res.writeHead(200, { "content-type": "application/x-subrip" });
    res.end("1\n00:00:00,000 --> 00:01:00,000\nNSH 外挂字幕测试\n\n");
    return;
  }

  if (process.env.MOCK_DANMAKU === "1" && req.url.startsWith("/api/danmaku/")) {
    const path = req.url.split("?")[0];
    const json = (value) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(value));
    };
    if (path.endsWith("/match")) {
      json({ errorCode: 0, success: true, errorMessage: "", isMatched: true,
        matches: [{ episodeId: 1, animeId: 1, animeTitle: "测试番剧", episodeTitle: "第1集", type: "", typeDescription: "", shift: 0 }] });
      return;
    }
    if (path.endsWith("/search")) {
      json({ errorCode: 0, success: true, errorMessage: "", animes: [{ animeId: 1, animeTitle: "测试番剧", type: "", typeDescription: "", source: "mock" }] });
      return;
    }
    if (path.endsWith("/episodes")) {
      json({ errorCode: 0, success: true, errorMessage: "", bangumi: { bangumiId: "1", animeTitle: "测试番剧", episodes: [{ episodeId: 1, episodeTitle: "第1集" }] } });
      return;
    }
    if (path.endsWith("/comment")) {
      json({ count: 6, comments: [
        { p: "100,1,25,16777215,0,0,0,1", m: "滚动弹幕 A", cid: 1 },
        { p: "105,5,25,16711680,0,0,0,2", m: "顶部弹幕", cid: 2 },
        { p: "110,4,25,65280,0,0,0,3", m: "底部弹幕", cid: 3 },
        { p: "115,1,25,16776960,0,0,0,4", m: "滚动弹幕 B", cid: 4 },
        { p: "120,1,25,16777215,0,0,0,5", m: "滚动弹幕 C", cid: 5 }
      ] });
      return;
    }
  }

  if (process.env.MOCK_LIVE === "1" && req.url.startsWith("/api/server-config")) {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ LiveEnabled: true, WebLiveEnabled: false, Version: "mock-live", SiteName: "MoonTVPlus Mock" }));
    return;
  }

  if (process.env.MOCK_LIVE === "1" && req.url.startsWith("/api/live/")) {
    const now = Date.now();
    const pad = (value) => String(value).padStart(2, "0");
    const xmltv = (ms) => {
      const d = new Date(ms);
      return String(d.getFullYear()) + pad(d.getMonth() + 1) + pad(d.getDate()) + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + " +0800";
    };
    const channels = [
      { id: "cctv1", tvgId: "cctv1", name: "CCTV-1 综合", logo: "https://www.douban.com/favicon.ico", group: "央视", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" },
      { id: "cctv2", tvgId: "cctv2", name: "CCTV-2 财经", logo: "https://www.douban.com/favicon.ico", group: "央视", url: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8" },
      { id: "hunan", tvgId: "hunan", name: "湖南卫视", logo: "https://www.douban.com/favicon.ico", group: "卫视", url: "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8" },
      { id: "dragon", tvgId: "dragon", name: "东方卫视", logo: "https://www.douban.com/favicon.ico", group: "卫视", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }
    ];
    const json = (value) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(value));
    };
    if (req.url.startsWith("/api/live/sources")) {
      json({ success: true, data: [{ key: "mock", name: "测试直播源", url: "", ua: "AptvPlayer/1.4.10", from: "config", channelNumber: channels.length, proxyMode: "direct" }] });
      return;
    }
    if (req.url.startsWith("/api/live/channels")) {
      json({ success: true, data: channels });
      return;
    }
    if (req.url.startsWith("/api/live/epg")) {
      const tvgId = new URL("http://mirror" + req.url).searchParams.get("tvgId") || "";
      json({ success: true, data: { tvgId, source: "mock", epgUrl: "", programs: [
        { start: xmltv(now - 3600000), end: xmltv(now - 600000), title: "整点新闻" },
        { start: xmltv(now - 600000), end: xmltv(now + 3000000), title: "测试节目 · " + tvgId },
        { start: xmltv(now + 3000000), end: xmltv(now + 6600000), title: "下一档节目" }
      ] } });
      return;
    }
    json({ success: false, error: "mock live endpoint not found" });
    return;
  }

  if (req.url === "/downloads/latest.json" && process.env.MOCK_UPDATE === "1" && UPDATE_SIZE > 0) {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({
      version: "1.9.20",
      notes: "# MoonTVPlus Android 1.9.20 测试版\n\n- versionCode 30\n- 仅用于更新链路验证，不要对外发布。",
      apk: {
        name: UPDATE_APK_NAME,
        url: "http://10.0.2.2:9000/downloads/" + UPDATE_APK_NAME,
        size: UPDATE_SIZE,
        sha256: UPDATE_SHA
      }
    }));
    return;
  }

  if (req.url === "/downloads/" + UPDATE_APK_NAME && process.env.MOCK_UPDATE === "1" && UPDATE_SIZE > 0) {
    res.writeHead(200, {
      "content-type": "application/vnd.android.package-archive",
      "content-length": UPDATE_SIZE
    });
    fs.createReadStream(UPDATE_APK).pipe(res);
    return;
  }

  if (req.url.startsWith("/downloads/latest.json")) {
    const options = {
      host: UPSTREAM_HOST,
      port: 80,
      path: req.url,
      method: "GET",
      headers: { "User-Agent": "MoonTVPlus App/1.9.16 (Android) moontvplus app", Accept: "*/*", "Accept-Encoding": "identity" }
    };
    const upstream = http.request(options, (upstreamResponse) => {
      const headers = { ...upstreamResponse.headers };
      delete headers["content-encoding"];
      res.writeHead(upstreamResponse.statusCode || 502, headers);
      upstreamResponse.pipe(res);
    });
    upstream.on("error", (error) => {
      res.writeHead(502, { "content-type": "text/plain" });
      res.end(String(error));
    });
    upstream.end();
    return;
  }

  const auth = req.headers.authorization;
  if (!auth || !auth.startsWith("Bearer ")) {
    res.writeHead(401, { "content-type": "text/plain" });
    res.end("missing bearer");
    return;
  }

  const cacheable = req.method === "GET" && !req.headers.range &&
    !req.url.startsWith("/api/proxy-m3u8") && !req.url.startsWith("/api/proxy/");
  if (cacheable) {
    const hit = readCache(req.url);
    if (hit) {
      res.writeHead(hit.status, hit.headers);
      res.end(Buffer.from(hit.body, "base64"));
      return;
    }
  }

  const options = {
    host: UPSTREAM_HOST,
    port: 80,
    path: req.url,
    method: req.method,
    headers: {
      Authorization: auth,
      "User-Agent": "MoonTVPlus App/1.9.16 (Android) moontvplus app",
      Accept: "*/*",
      "Accept-Encoding": "identity",
    },
  };

  const upstream = http.request(options, (upstreamResponse) => {
    const contentType = String(upstreamResponse.headers["content-type"] || "");
    if (contentType.includes("text/event-stream")) {
      const headers = { ...upstreamResponse.headers };
      delete headers["content-length"];
      delete headers["content-encoding"];
      res.writeHead(upstreamResponse.statusCode || 502, headers);
      upstreamResponse.pipe(res);
      return;
    }
    const chunks = [];
    upstreamResponse.on("data", (chunk) => chunks.push(chunk));
    upstreamResponse.on("end", () => {
      let body = Buffer.concat(chunks);
      if (contentType.includes("mpegurl") || contentType.includes("m3u8")) {
        let text = body
          .toString("utf8")
          .replaceAll("https://" + UPSTREAM_HOST, "http://10.0.2.2:" + PORT)
          .replaceAll("http://" + UPSTREAM_HOST, "http://10.0.2.2:" + PORT);
        const requestUrl = new URL("http://mirror" + req.url);
        const original = requestUrl.searchParams.get("url");
        if (original) {
          const baseDir = original.substring(0, original.lastIndexOf("/") + 1);
          text = text.replace(
            /#EXT-X-MEDIA:([^\n]*?)URI="([^"]+)"/g,
            (line, before, uri) => {
              if (/^https?:/.test(uri)) return line;
              const absolute = new URL(uri, baseDir).href;
              const proxied =
                "http://10.0.2.2:" + PORT + "/api/proxy-m3u8?url=" + encodeURIComponent(absolute) + "&source=directplay";
              return '#EXT-X-MEDIA:' + before + 'URI="' + proxied + '"';
            }
          );
        }
        body = Buffer.from(text);
      }
      const headers = { ...upstreamResponse.headers, "content-length": body.length };
      delete headers["content-encoding"];
      if (cacheable && upstreamResponse.statusCode === 200 && body.length < 5 * 1024 * 1024) {
        writeCache(req.url, 200, headers, body);
      }
      res.writeHead(upstreamResponse.statusCode || 502, headers);
      res.end(body);
    });
  });

  upstream.on("error", (error) => {
    res.writeHead(502, { "content-type": "text/plain" });
    res.end(String(error));
  });
  req.pipe(upstream);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("dev mirror on " + PORT);
});
