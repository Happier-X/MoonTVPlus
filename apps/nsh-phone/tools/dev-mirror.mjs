import http from "node:http";

const UPSTREAM_HOST = "tv.370125.xyz";
const PORT = 9000;

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

  const auth = req.headers.authorization;
  if (!auth || !auth.startsWith("Bearer ")) {
    res.writeHead(401, { "content-type": "text/plain" });
    res.end("missing bearer");
    return;
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
