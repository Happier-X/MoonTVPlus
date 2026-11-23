import {
  effect,
  Image,
  mount,
  Scroll,
  signal,
  Storage,
  Text,
  Video,
  View,
  type VideoRef,
} from "nsh";
import { Input } from "nsh/base";
import { Http, Proxy } from "moontvplusspike/native";

const DEFAULT_SERVER = "https://tv.370125.xyz";

type DoubanItem = {
  id: string;
  title: string;
  poster: string;
  rate?: string;
  year?: string;
};

type SearchResult = {
  id: string;
  title: string;
  poster: string;
  episodes?: string[];
  episodes_titles?: string[];
  source: string;
  source_name?: string;
  proxyMode?: boolean;
};

type Route = { name: "home" } | { name: "play" };

const FALLBACK_POSTER =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="300" height="450"><rect width="300" height="450" fill="#1c1c22"/><text x="150" y="225" fill="#777" font-size="18" text-anchor="middle">No poster</text></svg>'
  );

const server = signal(DEFAULT_SERVER);
const token = signal("");
const proxyPort = signal(0);
const route = signal<Route>({ name: "home" });
const settingsOpen = signal(false);
const booted = signal(false);
const homeLoading = signal(false);
const homeError = signal("");
const movies = signal<DoubanItem[]>([]);
const playLoading = signal(false);
const playError = signal("");
const playTitle = signal("");
const playSource = signal("");
const episodes = signal<string[]>([]);
const episodeTitles = signal<string[]>([]);
const episodeIndex = signal(0);
const playUrl = signal("");
const useLocalProxy = signal(true);
const paused = signal(false);
const rate = signal(1);
const currentTime = signal(0);
const duration = signal(0);
const buffering = signal(false);
const firstFrameMs = signal(-1);
const loadMs = signal(-1);
const videoError = signal("");
const requestLog = signal<string[]>([]);

let videoRef: VideoRef | null = null;
let playStartedAt = 0;

function base(): string {
  return server().replace(/\/+$/, "");
}

function note(message: string) {
  console.log("nsh spike: " + message);
  requestLog.update((lines) => [message, ...lines].slice(0, 8));
}

function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("请求超时 " + ms + "ms")), ms);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

async function api(path: string): Promise<any> {
  const url = base() + path;
  note("GET " + path);
  const text = await withTimeout(Http.get(url), 30000);
  note("HTTP " + path + " -> " + text.length + " chars");
  return JSON.parse(text);
}

function posterUrl(url: string): string {
  if (!url) return FALLBACK_POSTER;
  if (proxyPort() > 0) {
    return "http://127.0.0.1:" + proxyPort() + "/img?u=" + encodeURIComponent(url);
  }
  return url;
}

function startProxy(showError: boolean) {
  try {
    if (!server() || !token()) return;
    const port = Proxy.start(base(), token());
    proxyPort.set(port);
    note("本地代理 127.0.0.1:" + port);
  } catch (error) {
    proxyPort.set(0);
    if (showError) playError.set("本地代理启动失败：" + String(error));
    console.error("nsh spike: proxy failed", error);
  }
}

async function loadHome() {
  homeLoading.set(true);
  homeError.set("");
  try {
    const path =
      "/api/douban/categories?kind=movie&category=" +
      encodeURIComponent("热门") +
      "&type=" +
      encodeURIComponent("全部") +
      "&limit=24";
    const data = await api(path);
    movies.set(Array.isArray(data.list) ? data.list : []);
    note("首页 " + movies().length + " 条");
  } catch (error) {
    homeError.set(String(error));
    note("首页失败 " + String(error));
  } finally {
    homeLoading.set(false);
  }
}

function playableUrl(url: string): string {
  const absolute = url.startsWith("http")
    ? url
    : base() + (url.startsWith("/") ? "" : "/") + url;
  if (
    absolute.includes("/api/proxy-m3u8") ||
    absolute.includes("/api/proxy/vod/m3u8")
  ) {
    return absolute;
  }
  return (
    base() +
    "/api/proxy-m3u8?url=" +
    encodeURIComponent(absolute) +
    "&source=directplay"
  );
}

function localMedia(url: string): string {
  if (!useLocalProxy() || proxyPort() <= 0) return url;
  return (
    "http://127.0.0.1:" + proxyPort() + "/hls?u=" + encodeURIComponent(url)
  );
}

function selectEpisode(index: number) {
  const list = episodes();
  if (index < 0 || index >= list.length) return;
  episodeIndex.set(index);
  videoError.set("");
  playError.set("");
  firstFrameMs.set(-1);
  loadMs.set(-1);
  currentTime.set(0);
  duration.set(0);
  paused.set(false);
  playStartedAt = Date.now();
  const src = playSource() === "direct-hls" ? list[index] : playableUrl(list[index]);
  playUrl.set(localMedia(src));
  note("播放 第 " + (index + 1) + " 集 · " + (useLocalProxy() ? "本地代理" : "直连"));
}

async function openTitle(item: DoubanItem) {
  route.set({ name: "play" });
  playTitle.set(item.title);
  playLoading.set(true);
  playError.set("");
  episodes.set([]);
  episodeTitles.set([]);
  playUrl.set("");
  videoError.set("");

  try {
    const search = await api(
      "/api/search?q=" + encodeURIComponent(item.title) + "&fresh=1"
    );
    const results: SearchResult[] = Array.isArray(search.results) ? search.results : [];
    const candidates = results.filter(
      (entry) => Array.isArray(entry.episodes) && entry.episodes.length > 0
    );
    if (candidates.length === 0) throw new Error("搜索没有返回可播放的源");
    const best = candidates[0];
    note("选中源 " + best.source + " · " + (best.episodes?.length ?? 0) + " 集");

    let detail: SearchResult = best;
    try {
      detail = await api(
        "/api/source-detail?source=" +
          encodeURIComponent(best.source) +
          "&id=" +
          encodeURIComponent(best.id) +
          "&fresh=1"
      );
    } catch (error) {
      note("source-detail 失败，改用搜索结果");
      console.warn("source-detail failed, using search result", error);
    }

    const list = detail.episodes?.length ? detail.episodes : best.episodes ?? [];
    const titles = detail.episodes_titles?.length
      ? detail.episodes_titles
      : best.episodes_titles ?? [];
    if (list.length === 0) throw new Error("详情里没有可播放的剧集");
    playSource.set(detail.source || best.source);
    episodes.set(list);
    episodeTitles.set(titles);
    selectEpisode(0);
  } catch (error) {
    playError.set(String(error));
    note("打开失败 " + String(error));
  } finally {
    playLoading.set(false);
  }
}

function openDirectHls(title: string, url: string) {
  route.set({ name: "play" });
  playTitle.set(title);
  playSource.set("direct-hls");
  playLoading.set(false);
  playError.set("");
  episodes.set([url]);
  episodeTitles.set(["直连"]);
  useLocalProxy.set(true);
  selectEpisode(0);
}

async function openKnown(source: string, id: string, title: string) {
  route.set({ name: "play" });
  playTitle.set(title);
  playLoading.set(true);
  playError.set("");
  episodes.set([]);
  episodeTitles.set([]);
  playUrl.set("");
  videoError.set("");
  note("直接打开 " + source + "/" + id);
  try {
    const detail: SearchResult = await api(
      "/api/source-detail?source=" +
        encodeURIComponent(source) +
        "&id=" +
        encodeURIComponent(id) +
        "&fresh=1"
    );
    const list = detail.episodes ?? [];
    if (list.length === 0) throw new Error("详情里没有可播放的剧集");
    playSource.set(detail.source || source);
    episodes.set(list);
    episodeTitles.set(detail.episodes_titles ?? []);
    selectEpisode(0);
  } catch (error) {
    playError.set(String(error));
    note("直接打开失败 " + String(error));
  } finally {
    playLoading.set(false);
  }
}

function saveSettings() {
  startProxy(true);
  Storage.setItem("server", base());
  Storage.setItem("token", token());
  settingsOpen.set(false);
  route.set({ name: "home" });
  void loadHome();
}

function formatMs(value: number): string {
  return value < 0 ? "—" : value + " ms";
}

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return "0:00";
  const total = Math.floor(seconds);
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return minutes + ":" + String(rest).padStart(2, "0");
}

function Header(props: { title: string; onBack?: () => void }) {
  return (
    <View
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: 8,
        paddingHorizontal: 12,
        paddingTop: "env(safe-area-inset-top)",
        paddingBottom: 10,
        backgroundColor: "#101014",
        borderBottomWidth: 1,
        borderBottomColor: "#26262e",
      }}
    >
      {props.onBack ? (
        <Text
          onPress={props.onBack}
          style={{ color: "#8ab4ff", fontSize: 15, paddingRight: 8 }}
        >
          ‹ 返回
        </Text>
      ) : null}
      <Text
        style={{ color: "#f2f2f5", fontSize: 16, fontWeight: "600", flex: 1, numberOfLines: 1 }}
      >
        {props.title}
      </Text>
      <Text onPress={() => settingsOpen.set(true)} style={{ color: "#8ab4ff", fontSize: 13 }}>
        设置
      </Text>
    </View>
  );
}

function HomeScreen() {
  return (
    <View style={{ flex: 1 }}>
      <Header title={"MoonTVPlus · Native Shell"} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ padding: 12, gap: 10 }}>
          {homeLoading() ? (
            <Text style={{ color: "#8b8b98" }}>正在读取站点 /api…</Text>
          ) : null}
          {homeError() ? <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{homeError()}</Text> : null}
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10 }}>
            {movies().map((item) => (
              <View
                onPress={() => void openTitle(item)}
                style={{ width: "47.8%", gap: 4 }}
              >
                <Image
                  src={posterUrl(item.poster)}
                  style={{
                    width: "100%",
                    aspectRatio: 2 / 3,
                    borderRadius: 8,
                    backgroundColor: "#1c1c22",
                  }}
                />
                <Text style={{ color: "#e8e8ee", fontSize: 13, numberOfLines: 1 }}>
                  {item.title}
                </Text>
                <Text style={{ color: "#7f7f8c", fontSize: 11, numberOfLines: 1 }}>
                  {(item.rate ? "★ " + item.rate + "  " : "") + (item.year ?? "")}
                </Text>
              </View>
            ))}
          </View>
          <Text
            onPress={() => void loadHome()}
            style={{ color: "#8ab4ff", fontSize: 13, paddingVertical: 12 }}
          >
            刷新列表
          </Text>
          <Text
            onPress={() => void openKnown("iqiyizyapi.com", "16636", "庆余年第一季")}
            style={{ color: "#7ee2a8", fontSize: 13, paddingVertical: 8 }}
          >
            测试播放：庆余年第一季（iqiyizyapi）
          </Text>
          <Text
            onPress={() =>
              void openDirectHls(
                "真实 TS HLS（zuida CDN）",
                "https://v6.zuidazym3u8.com/yyv6/202309/08/4Tcy1iG9SE1/video/2000k_1080/hls/index.m3u8"
              )
            }
            style={{ color: "#e2b07e", fontSize: 13, paddingVertical: 8 }}
          >
            测试直连 HLS：真实 TS 分片
          </Text>
          <Text style={{ color: "#555562", fontSize: 10, numberOfLines: 4 }}>
            {requestLog().join("\n")}
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function PlayScreen() {
  return (
    <View style={{ flex: 1, backgroundColor: "#08080b" }}>
      <Header title={playTitle()} onBack={() => {
        paused.set(true);
        playUrl.set("");
        route.set({ name: "home" });
      }} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ backgroundColor: "#000", minHeight: 200, justifyContent: "center" }}>
          {playUrl() ? (
            <Video
              ref={(node) => {
                videoRef = node;
              }}
              src={playUrl()}
              paused={paused()}
              rate={rate()}
              audioSession="playback"
              progressUpdateInterval={250}
              style={{ width: "100%", height: 210, backgroundColor: "#000" }}
              onLoadStart={() => {
                loadMs.set(-1);
                firstFrameMs.set(-1);
                videoError.set("");
                if (playStartedAt === 0) playStartedAt = Date.now();
              }}
              onLoad={(event) => {
                loadMs.set(Date.now() - playStartedAt);
                duration.set(event.duration);
                note("onLoad " + (Date.now() - playStartedAt) + "ms · " + event.duration.toFixed(1) + "s");
              }}
              onProgress={(event) => {
                currentTime.set(event.currentTime);
                duration.set(event.duration);
                if (firstFrameMs() < 0 && event.currentTime > 0) {
                  firstFrameMs.set(Date.now() - playStartedAt);
                  note("首帧 " + (Date.now() - playStartedAt) + "ms");
                }
              }}
              onBuffer={(event) => {
                buffering.set(event.isBuffering);
                note(event.isBuffering ? "缓冲中" : "缓冲结束");
              }}
              onError={(event) => {
                videoError.set(event.message + (event.code ? " (" + event.code + ")" : ""));
                note("播放失败 " + event.message);
                console.error("nsh spike: video error", event);
              }}
              onEnd={() => {
                paused.set(true);
                note("播放结束");
              }}
            />
          ) : (
            <View style={{ height: 210, alignItems: "center", justifyContent: "center" }}>
              <Text style={{ color: "#777" }}>
                {playLoading() ? "正在找源…" : "没有可播放的地址"}
              </Text>
            </View>
          )}
        </View>

        <View style={{ padding: 12, gap: 10 }}>
          <View style={{ flexDirection: "row", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
            <Text
              onPress={() => paused.update((value) => !value)}
              style={{ color: "#8ab4ff", fontSize: 15 }}
            >
              {paused() ? "播放" : "暂停"}
            </Text>
            <Text
              onPress={() => videoRef?.seek(Math.max(0, currentTime() - 10))}
              style={{ color: "#c9c9d2" }}
            >
              -10s
            </Text>
            <Text
              onPress={() => videoRef?.seek(currentTime() + 30)}
              style={{ color: "#c9c9d2" }}
            >
              +30s
            </Text>
            <Text
              onPress={() => {
                const next = rate() === 1 ? 1.5 : rate() === 1.5 ? 2 : 1;
                rate.set(next);
                note("倍速 " + next + "x");
              }}
              style={{ color: "#c9c9d2" }}
            >
              倍速 {rate()}×
            </Text>
            <Text
              onPress={() => {
                useLocalProxy.update((value) => !value);
                selectEpisode(episodeIndex());
              }}
              style={{ color: useLocalProxy() ? "#7ee2a8" : "#e2b07e" }}
            >
              {useLocalProxy() ? "本地代理" : "直连（无头）"}
            </Text>
          </View>

          <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
            <Text style={{ color: "#8b8b98", fontSize: 12 }}>
              {formatTime(currentTime())} / {formatTime(duration())}
            </Text>
            <Text style={{ color: buffering() ? "#e2b07e" : "#8b8b98", fontSize: 12 }}>
              {buffering() ? "缓冲中" : "就绪"}
            </Text>
          </View>

          <View style={{ backgroundColor: "#131318", borderRadius: 8, padding: 10, gap: 4 }}>
            <Text style={{ color: "#d8d8e0", fontSize: 12 }}>
              首帧 {formatMs(firstFrameMs())} · onLoad {formatMs(loadMs())}
            </Text>
            <Text style={{ color: "#8b8b98", fontSize: 12 }}>
              源 {playSource() || "—"} · 第 {episodeIndex() + 1}/{Math.max(1, episodes().length)} 集
            </Text>
            <Text style={{ color: "#8b8b98", fontSize: 11, numberOfLines: 3 }}>
              {playUrl() || "—"}
            </Text>
            {videoError() ? (
              <Text style={{ color: "#ff8b8b", fontSize: 12 }}>播放失败：{videoError()}</Text>
            ) : null}
            {playError() ? <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{playError()}</Text> : null}
          </View>

          {episodes().length > 0 ? (
            <View style={{ gap: 6 }}>
              <Text style={{ color: "#c9c9d2", fontSize: 13 }}>选集</Text>
              <Scroll horizontal style={{ maxHeight: 40 }}>
                <View style={{ flexDirection: "row", gap: 8 }}>
                  {episodes().map((episode, index) => (
                    <View
                      onPress={() => selectEpisode(index)}
                      style={{
                        paddingHorizontal: 10,
                        paddingVertical: 7,
                        borderRadius: 6,
                        backgroundColor: index === episodeIndex() ? "#2c5bd6" : "#1c1c22",
                      }}
                    >
                      <Text style={{ color: "#fff", fontSize: 12 }}>
                        {episodeTitles()[index]?.replace(/<[^>]+>/g, "") || String(index + 1)}
                      </Text>
                    </View>
                  ))}
                </View>
              </Scroll>
            </View>
          ) : null}

          <Text style={{ color: "#555562", fontSize: 10, numberOfLines: 8 }}>
            {requestLog().join("\n")}
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function SettingsScreen() {
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header title="设置" onBack={() => settingsOpen.set(false)} />
      <Scroll style={{ flex: 1 }}>
        <View style={{ padding: 14, gap: 12 }}>
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>服务器</Text>
          <Input
            value={server()}
            onChange={(event) => server.set(event.value)}
            autoCapitalize="none"
            style={{ color: "#fff", backgroundColor: "#17171d", borderRadius: 8, padding: 10 }}
          />
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>Bearer token（/api/login 的 token 原样）</Text>
          <Input
            value={token()}
            onChange={(event) => token.set(event.value)}
            autoCapitalize="none"
            multiline
            style={{
              color: "#fff",
              backgroundColor: "#17171d",
              borderRadius: 8,
              padding: 10,
              minHeight: 96,
            }}
          />
          <Text style={{ color: "#7f7f8c", fontSize: 11 }}>
            token 只保存在本机 NSH Storage；代理和请求头由 Rust 侧读取。
          </Text>
          <View
            onPress={saveSettings}
            style={{
              backgroundColor: "#2c5bd6",
              borderRadius: 8,
              paddingVertical: 12,
              alignItems: "center",
            }}
          >
            <Text style={{ color: "#fff", fontSize: 14 }}>保存并重载</Text>
          </View>
          <Text style={{ color: "#8b8b98", fontSize: 11 }}>
            本地代理端口：{proxyPort() || "未启动"}
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function App() {
  effect(() => {
    void (async () => {
      const savedServer = await Storage.getItem("server");
      const savedToken = await Storage.getItem("token");
      if (savedServer) server.set(savedServer);
      if (savedToken) token.set(savedToken);
      booted.set(true);
      if (savedToken) {
        startProxy(false);
        await loadHome();
      } else {
        settingsOpen.set(true);
      }
    })();
  });

  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <If when={!booted()}>
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
          <Text style={{ color: "#8b8b98" }}>启动中…</Text>
        </View>
      </If>
      <If when={booted() && settingsOpen()}>
        <SettingsScreen />
      </If>
      <If when={booted() && !settingsOpen() && route().name === "play"}>
        <PlayScreen />
      </If>
      <If when={booted() && !settingsOpen() && route().name === "home"}>
        <HomeScreen />
      </If>
    </View>
  );
}

mount(App);



