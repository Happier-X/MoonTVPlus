import {
  effect,
  host,
  Image,
  mount,
  nativeComponent,
  PanResponder,
  Scroll,
  signal,
  Storage,
  Text,
  View,
  type NativeComponentRef,
} from "nsh";
import { Input } from "nsh/base";

const DEFAULT_SERVER = "http://tv.370125.xyz";
const UA = "MoonTVPlus App/1.9.16 (Android) moontvplus app";

type Movie = { id: string; title: string; poster: string; rate?: string; year?: string };
type SourceItem = {
  source: string;
  sourceName: string;
  id: string;
  title: string;
  poster: string;
  episodes: string[];
  episodeTitles: string[];
};
type Route = { name: "login" } | { name: "home" } | { name: "detail" } | { name: "play" } | { name: "favorites" } | { name: "history" } | { name: "settings" };
type HistoryRecord = {
  key: string;
  title: string;
  source_name: string;
  cover: string;
  year: string;
  index: number;
  total_episodes: number;
  play_time: number;
  total_time: number;
  save_time: number;
  search_title: string;
};

type FavoriteItem = {
  key: string;
  title: string;
  source_name: string;
  cover: string;
  year: string;
  total_episodes: number;
  save_time: number;
  search_title: string;
};

type Resume = {
  title: string;
  poster: string;
  source: string;
  sourceName: string;
  id: string;
  episodeIndex: number;
  episodeUrl: string;
  position: number;
  updatedAt: number;
};

type TrackOption = {
  group: number;
  index: number;
  label: string;
  language?: string;
  height?: number;
  bitrate?: number;
  selected: boolean;
};
type DanmakuItem = { time: number; text: string; mode: number; color: string };

const danmakuCache = new Map<string, DanmakuItem[]>();

type TracksPayload = {
  video: TrackOption[];
  audio: TrackOption[];
  subtitle: TrackOption[];
  subtitleEnabled: boolean;
  externalSubtitle: boolean;
};

const Player = nativeComponent<
  {
    url: string;
    headers?: Record<string, string>;
    paused?: boolean;
    startPosition?: number;
    rate?: number;
    title?: string;
    subtitle?: string;
  },
  {
    progress: { position: number; duration: number; rate: number };
    ready: { duration: number };
    state: { state: string };
    error: { message: string };
    tracks: TracksPayload;
    authExpired: { message: string };
    unsupported: { command: string };
  }
>("moontvplus.player", {
  props: ["url", "headers", "paused", "startPosition", "rate", "title", "subtitle"],
  events: ["progress", "ready", "state", "error", "tracks", "authExpired", "unsupported"],
});

const server = signal(DEFAULT_SERVER);
const token = signal("");
const username = signal("");
const route = signal<Route>({ name: "login" });
const booted = signal(false);
const busy = signal("");
const message = signal("");
const movies = signal<Movie[]>([]);
const bangumi = signal<Movie[]>([]);
const resume = signal<Resume | null>(null);
const homeError = signal("");
const detailTitle = signal("");
const detailPoster = signal("");
const detailSources = signal<SourceItem[]>([]);
const detailSource = signal("");
const detailEpisodes = signal<string[]>([]);
const detailEpisodeTitles = signal<string[]>([]);
const detailLoading = signal(false);
const detailError = signal("");
const detailMovie = signal<Movie | null>(null);
const detailSearchDone = signal(0);
const detailSearchTotal = signal(0);
const detailSearchErrors = signal<{ sourceName: string; error: string }[]>([]);
const detailEpisodesLoading = signal(false);
const searchFirstMs = signal(-1);
const searchAllMs = signal(-1);
const searchCacheHit = signal(false);
const playTitle = signal("");
const playPoster = signal("");
const playSource = signal("");
const playSourceName = signal("");
const playId = signal("");
const playEpisodeIndex = signal(0);
const playEpisodeUrl = signal("");
const playHeaders = signal<Record<string, string>>({});
const playStart = signal(0);
const paused = signal(false);
const playPosition = signal(0);
const playDuration = signal(0);
const playState = signal("idle");
const playError = signal("");
const rate = signal(1);
const pipActive = signal(false);
const fullscreen = signal(false);
const controlsVisible = signal(true);
const panel = signal("");
const videoTracks = signal<TrackOption[]>([]);
const audioTracks = signal<TrackOption[]>([]);
const subtitleTracks = signal<TrackOption[]>([]);
const subtitleEnabled = signal(false);
const externalSubtitle = signal(false);
const subtitleUrl = signal("");
const subtitleSize = signal(18);
const subtitleOutline = signal(true);
const gestureHint = signal("");
const seekPreview = signal(-1);
const overlayW = signal(390);
const overlayH = signal(240);
const seekBarW = signal(300);
const brightness = signal(0.5);
const volume = signal(0.5);
const danmakuEnabled = signal(true);
const danmakuOpacity = signal(0.85);
const danmakuFontSize = signal(18);
const danmakuMode = signal("all");
const danmakuItems = signal<DanmakuItem[]>([]);
const danmakuStatus = signal("");
const loginServer = signal(DEFAULT_SERVER);
const loginUser = signal("");
const loginPassword = signal("");
const loginError = signal("");
const searchQuery = signal("可能的爱");
const historyRecords = signal<HistoryRecord[]>([]);
const favorites = signal<FavoriteItem[]>([]);
const defaultQuality = signal("auto");
const autoPlay = signal(false);
const settingsMessage = signal("");
const playEpisodes = signal<string[]>([]);
const playEpisodeTitles = signal<string[]>([]);

let playerRef: NativeComponentRef | undefined;
let lastResumeSave = 0;
let resumeSeekDone = false;
let qualityApplied = false;
let autoNextAt = 0;
let brightnessBase = 0.5;
let volumeBase = 0.5;
let gestureKind = "";
let longPressActive = false;
let longPressRate = 1;
let suppressTapUntil = 0;
let tapTimer = 0;
let seekStartRatio = 0;
let orientationLandscape = false;
let playOpenedAt = 0;

function base(): string {
  return server().replace(/\/+$/, "");
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

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

async function syncAuth(nextServer = server(), nextToken = token()) {
  await host.call("auth.token", { server: nextServer, token: nextToken });
}

function showHint(value: string) {
  gestureHint.set(value);
  setTimeout(() => gestureHint.set(""), 900);
}

function applyRate(next: number) {
  rate.set(next);
  playerRef?.command("setRate", next);
  void Storage.setItem("playerRate", String(next));
}

function logPlay(message: string) {
  console.log("nshspike: " + message);
}

function setFullscreenMode(next: boolean) {
  fullscreen.set(next);
  playerRef?.command("setFullscreen", next);
  orientationLandscape = next;
  playerRef?.command("setOrientation", next ? "landscape" : "portrait");
}

function toggleOrientation() {
  orientationLandscape = !orientationLandscape;
  playerRef?.command("setOrientation", orientationLandscape ? "landscape" : "portrait");
  showHint(orientationLandscape ? "横屏" : "竖屏");
}

function togglePanel(name: string) {
  panel.update((value) => (value === name ? "" : name));
  controlsVisible.set(true);
}

function parseBody(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function headersFor(url: string, auth: boolean): Record<string, string> {
  const headers: Record<string, string> = { "User-Agent": UA, Accept: "*/*" };
  if (auth && token()) headers.Authorization = "Bearer " + token();
  return headers;
}

async function rawRequest(
  url: string,
  method: string,
  body?: unknown,
  auth = true
): Promise<{ status: number; body: string; json: any }> {
  if (!host.available()) throw new Error("需要在 Android 壳里运行");
  const result: any = await host.call(
    "http",
    {
      url,
      method,
      headers: headersFor(url, auth),
      body: body === undefined ? undefined : JSON.stringify(body),
    },
    { timeout: 90000 }
  );
  return { status: result.status ?? 0, body: result.body ?? "", json: parseBody(result.body ?? "") };
}

async function refreshSession(): Promise<boolean> {
  if (!token()) return false;
  try {
    const result = await rawRequest(base() + "/api/auth/refresh", "POST", undefined, true);
    if (result.status === 200 && result.json?.ok && result.json?.token) {
      token.set(result.json.token);
      void Storage.setItem("token", result.json.token);
      void syncAuth(server(), result.json.token);
      return true;
    }
  } catch (error) {
    console.warn("refresh failed", error);
  }
  return false;
}

async function api(
  path: string,
  method = "GET",
  body?: unknown,
  auth = true
): Promise<{ status: number; body: string; json: any }> {
  let result = await rawRequest(base() + path, method, body, auth);
  if (result.status === 401 && auth) {
    const refreshed = await refreshSession();
    if (refreshed) result = await rawRequest(base() + path, method, body, true);
  }
  if (result.status === 401) {
    await clearSession();
    route.set({ name: "login" });
    throw new Error("登录已过期，请重新登录");
  }
  return result;
}

async function clearSession() {
  token.set("");
  username.set("");
  resume.set(null);
  await Storage.removeItem("token");
  await Storage.removeItem("username");
  await Storage.removeItem("resume");
  void host.call("auth.clear", {});
}

async function boot() {
  const savedServer = await Storage.getItem("server");
  const savedToken = await Storage.getItem("token");
  const savedUser = await Storage.getItem("username");
  const savedResume = await Storage.getItem("resume");
  if (savedServer) {
    server.set(savedServer);
    loginServer.set(savedServer);
  }
  if (savedUser) {
    username.set(savedUser);
    loginUser.set(savedUser);
  }
  const savedDanmaku = await Storage.getItem("danmakuEnabled");
  if (savedDanmaku === "false") danmakuEnabled.set(false);
  const savedOpacity = await Storage.getItem("danmakuOpacity");
  if (savedOpacity) danmakuOpacity.set(Number(savedOpacity) || 0.85);
  const savedFont = await Storage.getItem("danmakuFontSize");
  if (savedFont) danmakuFontSize.set(Number(savedFont) || 18);
  const savedMode = await Storage.getItem("danmakuMode");
  if (savedMode) danmakuMode.set(savedMode);
  const savedQuality = await Storage.getItem("defaultQuality");
  if (savedQuality) defaultQuality.set(savedQuality);
  const savedAutoPlay = await Storage.getItem("autoPlay");
  if (savedAutoPlay === "true") autoPlay.set(true);
  const savedRate = await Storage.getItem("playerRate");
  if (savedRate) rate.set(Number(savedRate) || 1);
  if (savedResume) {
    try {
      resume.set(JSON.parse(savedResume));
    } catch {
      // ignore a broken resume entry
    }
  }
  booted.set(true);
  if (!savedToken) {
    route.set({ name: "login" });
    return;
  }
  token.set(savedToken);
  void syncAuth(server(), savedToken);
  route.set({ name: "home" });
  await loadHome();
}

async function doLogin() {
  const serverValue = loginServer().trim().replace(/\/+$/, "");
  const user = loginUser().trim();
  if (!serverValue || !user || !loginPassword()) {
    loginError.set("请填写服务器、用户名和密码");
    return;
  }
  busy.set("登录中…");
  loginError.set("");
  try {
    server.set(serverValue);
    const result = await rawRequest(
      serverValue + "/api/login",
      "POST",
      { username: user, password: loginPassword(), turnstileToken: null },
      false
    );
    if (result.status !== 200 || !result.json?.ok) {
      loginError.set(result.json?.message || "登录失败，请检查账号或服务器");
      return;
    }
    if (!result.json?.token) {
      loginError.set("登录成功但没有返回 token，当前站点模式暂不支持");
      return;
    }
    token.set(result.json.token);
    username.set(user);
    await Storage.setItem("server", serverValue);
    await Storage.setItem("token", result.json.token);
    void syncAuth(serverValue, result.json.token);
    await Storage.setItem("username", user);
    loginPassword.set("");
    route.set({ name: "home" });
    await loadHome();
  } catch (error) {
    loginError.set(String(error));
  } finally {
    busy.set("");
  }
}

async function loadHome() {
  homeError.set("");
  busy.set("加载首页…");
  try {
    const moviePath =
      "/api/douban/categories?kind=movie&category=" +
      encodeURIComponent("热门") +
      "&type=" +
      encodeURIComponent("全部") +
      "&limit=24";
    try {
      const movieResult = await withTimeout(api(moviePath), 30000);
      if (movieResult.json?.list) movies.set(movieResult.json.list);
    } catch (error) {
      homeError.set("电影加载失败：" + String(error));
    }
  } finally {
    busy.set("");
  }

  try {
    const bangumiResult = await withTimeout(api("/api/bangumi/calendar"), 20000);
    if (Array.isArray(bangumiResult.json)) {
      const flat: Movie[] = [];
      for (const day of bangumiResult.json) {
        for (const item of day.items ?? []) {
          flat.push({
            id: String(item.id ?? ""),
            title: item.name_cn || item.name || "",
            poster:
              item.images?.large ||
              item.images?.common ||
              item.images?.medium ||
              item.images?.small ||
              "",
            rate: item.rating?.score ? String(item.rating.score) : "",
            year: (item.air_date || "").slice(0, 4),
          });
        }
      }
      bangumi.set(flat.slice(0, 24));
    }
  } catch (error) {
    console.warn("bangumi failed", error);
  }
}

function absolute(url: string): string {
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  return base() + (url.startsWith("/") ? "" : "/") + url;
}

function pushDanmaku() {
  playerRef?.command("setDanmaku", {
    items: danmakuItems(),
    enabled: danmakuEnabled(),
    opacity: danmakuOpacity(),
    fontSize: danmakuFontSize(),
    mode: danmakuMode(),
  });
}

function episodeNumber(value: string): number | null {
  const sxxExx = value.match(/[Ss]\d+[Ee](\d+)/);
  if (sxxExx) return parseInt(sxxExx[1], 10);
  const match = value.match(/^(\d+)$|第?\s*(\d+)\s*[集话話]?/);
  return match ? parseInt(match[1] || match[2], 10) : null;
}

function convertDanmaku(comments: any[]): DanmakuItem[] {
  const items: DanmakuItem[] = [];
  for (const comment of comments) {
    const parts = String(comment?.p ?? "").split(",");
    const time = parseFloat(parts[0]) || 0;
    const type = parseInt(parts[1], 10) || 1;
    const colorValue = parseInt(parts[3], 10) || 16777215;
    const text = String(comment?.m ?? "").replace(/<[^>]+>/g, "").trim();
    if (!text) continue;
    items.push({
      time: Math.round(time * 1000),
      text,
      mode: type === 5 ? 1 : type === 4 ? 2 : 0,
      color: "#" + colorValue.toString(16).padStart(6, "0"),
    });
    if (items.length >= 5000) break;
  }
  return items.sort((a, b) => a.time - b.time);
}

async function loadDanmakuForCurrent() {
  const title = playTitle();
  if (!title) return;
  const index = playEpisodeIndex();
  const cacheKey = title + "#" + index;
  const cached = danmakuCache.get(cacheKey);
  if (cached) {
    danmakuItems.set(cached);
    danmakuStatus.set(cached.length > 0 ? "共 " + cached.length + " 条" : "无弹幕");
    pushDanmaku();
    return;
  }

  danmakuStatus.set("加载中…");
  const episodeTitle = detailEpisodeTitles()[index] || "第" + (index + 1) + "集";
  let episodeId = 0;
  try {
    const match = await api("/api/danmaku/match", "POST", { fileName: title + " " + episodeTitle });
    if (match.json?.isMatched && match.json?.matches?.[0]?.episodeId) {
      episodeId = Number(match.json.matches[0].episodeId);
    } else {
      const search = await api("/api/danmaku/search?keyword=" + encodeURIComponent(title));
      const anime = search.json?.animes?.[0];
      if (anime) {
        const episodes = await api("/api/danmaku/episodes?animeId=" + anime.animeId);
        const list = episodes.json?.bangumi?.episodes ?? [];
        const wanted = episodeNumber(episodeTitle);
        const found = list.find((item: any) => wanted !== null && episodeNumber(item.episodeTitle) === wanted)
          ?? list[Math.min(index, list.length - 1)];
        if (found) episodeId = Number(found.episodeId);
      }
    }
    if (!episodeId) {
      danmakuStatus.set("未匹配到弹幕");
      danmakuItems.set([]);
      pushDanmaku();
      return;
    }
    const result = await api("/api/danmaku/comment?episodeId=" + episodeId);
    const items = convertDanmaku(result.json?.comments ?? []);
    danmakuCache.set(cacheKey, items);
    danmakuItems.set(items);
    danmakuStatus.set(items.length > 0 ? "共 " + items.length + " 条" : "无弹幕");
    pushDanmaku();
  } catch (error) {
    danmakuStatus.set("加载失败：" + String(error));
    danmakuItems.set([]);
    pushDanmaku();
  }
}

function streamUrl(url: string): string {
  if (url.includes("/api/proxy-m3u8")) return url;
  if (url.startsWith(base())) return url;
  return base() + "/api/proxy-m3u8?url=" + encodeURIComponent(url) + "&source=directplay";
}

function openPlay(
  title: string,
  poster: string,
  source: string,
  sourceName: string,
  id: string,
  episodes: string[],
  episodeTitles: string[],
  index: number,
  start: number
) {
  playTitle.set(title);
  playPoster.set(poster);
  playSource.set(source);
  playSourceName.set(sourceName);
  playId.set(id);
  playEpisodeIndex.set(index);
  playEpisodes.set(episodes);
  playEpisodeTitles.set(episodeTitles);
  const episode = episodes[index] ?? "";
  const resolved = streamUrl(absolute(episode));
  playOpenedAt = Date.now();
  logPlay("open " + resolved);
  playEpisodeUrl.set(resolved);
  playHeaders.set(headersFor(resolved, resolved.startsWith(base())));
  playStart.set(start);
  paused.set(false);
  playPosition.set(start);
  playDuration.set(0);
  playState.set("loading");
  playError.set("");
  resumeSeekDone = false;
  qualityApplied = false;
  videoTracks.set([]);
  audioTracks.set([]);
  subtitleTracks.set([]);
  subtitleEnabled.set(false);
  externalSubtitle.set(false);
  subtitleUrl.set("");
  danmakuItems.set([]);
  danmakuStatus.set("");
  panel.set("");
  controlsVisible.set(true);
  seekPreview.set(-1);
  route.set({ name: "play" });
}

function postPlayRecord() {
  const source = playSource();
  const id = playId();
  if (!source || !id || id === "demo") return;
  const record = {
    title: playTitle(),
    source_name: playSourceName(),
    cover: playPoster(),
    year: "",
    index: playEpisodeIndex() + 1,
    total_episodes: Math.max(playEpisodes().length, 1),
    play_time: Math.round(playPosition()),
    total_time: Math.round(playDuration()),
    save_time: Date.now(),
    search_title: playTitle(),
    origin: "vod",
  };
  void api("/api/playrecords", "POST", { key: source + "+" + id, record }).catch(() => undefined);
}

async function loadHistory() {
  const result = await api("/api/playrecords");
  if (!result.json || typeof result.json !== "object") return;
  const list: HistoryRecord[] = Object.entries(result.json).map(([key, value]: [string, any]) => ({
    key,
    ...value,
  }));
  list.sort((a, b) => (b.save_time ?? 0) - (a.save_time ?? 0));
  historyRecords.set(list);
}

async function loadFavorites() {
  const result = await api("/api/favorites");
  if (!result.json || typeof result.json !== "object") return;
  const list: FavoriteItem[] = Object.entries(result.json).map(([key, value]: [string, any]) => ({
    key,
    ...value,
  }));
  list.sort((a, b) => (b.save_time ?? 0) - (a.save_time ?? 0));
  favorites.set(list);
}

function latestHistory(): HistoryRecord | null {
  return historyRecords()[0] ?? null;
}

async function openHistoryRecord(record: HistoryRecord) {
  const source = record.key.split("+")[0];
  const id = record.key.split("+").slice(1).join("+");
  detailMovie.set({ id, title: record.title, poster: record.cover, year: record.year });
  detailTitle.set(record.title);
  detailPoster.set(record.cover);
  detailSources.set([]);
  detailSource.set(source);
  detailEpisodes.set([]);
  detailEpisodeTitles.set([]);
  detailError.set("");
  route.set({ name: "detail" });
  try {
    const result = await withTimeout(
      api("/api/source-detail?source=" + encodeURIComponent(source) + "&id=" + encodeURIComponent(id)),
      30000
    );
    const episodes = result.json?.episodes ?? [];
    if (!episodes.length) {
      detailError.set("历史记录对应源没有返回集数，可换源重试");
      return;
    }
    const titles = result.json?.episodes_titles ?? [];
    const item: SourceItem = {
      source,
      sourceName: record.source_name,
      id,
      title: record.title,
      poster: record.cover,
      episodes,
      episodeTitles: titles,
    };
    detailSources.set([item]);
    detailEpisodes.set(episodes);
    detailEpisodeTitles.set(titles);
    openPlay(
      record.title,
      record.cover,
      source,
      record.source_name,
      id,
      episodes,
      titles,
      Math.max(0, Math.min(record.index - 1, episodes.length - 1)),
      record.play_time ?? 0
    );
  } catch (error) {
    detailError.set("历史记录加载失败：" + String(error));
  }
}

async function toggleFavorite() {
  const source = detailSource();
  const item = detailSources().find((entry) => entry.source === source);
  if (!item) return;
  const key = source + "+" + item.id;
  const exists = favorites().some((entry) => entry.key === key);
  if (exists) {
    await api("/api/favorites?key=" + encodeURIComponent(key), "DELETE");
  } else {
    const favorite: FavoriteItem = {
      key,
      title: detailTitle(),
      source_name: item.sourceName,
      cover: detailPoster() || item.poster,
      year: "",
      total_episodes: detailEpisodes().length,
      save_time: Date.now(),
      search_title: detailTitle(),
    };
    await api("/api/favorites", "POST", { key, favorite });
  }
  await loadFavorites();
}

function saveResume(force = false) {
  if (!playEpisodeUrl()) return;
  const now = Date.now();
  if (!force && now - lastResumeSave < 5000) return;
  lastResumeSave = now;
  postPlayRecord();
  const entry: Resume = {
    title: playTitle(),
    poster: playPoster(),
    source: playSource(),
    sourceName: playSourceName(),
    id: playId(),
    episodeIndex: playEpisodeIndex(),
    episodeUrl: playEpisodeUrl(),
    position: playPosition(),
    updatedAt: now,
  };
  resume.set(entry);
  void Storage.setItem("resume", JSON.stringify(entry));
}

function demoProxyUrl(): string {
  const master = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
  return (
    base() +
    "/api/proxy-m3u8?url=" +
    encodeURIComponent(master) +
    "&source=directplay"
  );
}

function openDemo() {
  const proxy = demoProxyUrl();
  openPlay(
    "站点代理演示（真实 TS）",
    "",
    "mux.dev",
    "mux.dev",
    "demo",
    [proxy],
    ["第 1 集"],
    0,
    0
  );
}

function resumePlayback() {
  const item = resume();
  if (!item) return;
  openPlay(
    item.title,
    item.poster,
    item.source,
    item.sourceName,
    item.id,
    [item.episodeUrl],
    ["第 " + (item.episodeIndex + 1) + " 集"],
    0,
    item.position
  );
  playEpisodeIndex.set(item.episodeIndex);
}

type SearchCacheEntry = {
  at: number;
  sources: SourceItem[];
  errors: { sourceName: string; error: string }[];
  firstMs: number;
  allMs: number;
  done: number;
  total: number;
};

const searchCache = new Map<string, SearchCacheEntry>();
const SEARCH_TTL = 5 * 60 * 1000;
let activeSearchId = "";
let searchUnsubscribe: (() => void) | null = null;

function searchKey(title: string): string {
  return title.trim().toLowerCase();
}

async function openDetail(movie: Movie) {
  detailMovie.set(movie);
  detailTitle.set(movie.title);
  detailPoster.set(movie.poster);
  detailSources.set([]);
  detailSource.set("");
  detailEpisodes.set([]);
  detailEpisodeTitles.set([]);
  detailError.set("");
  detailEpisodesLoading.set(false);
  detailSearchDone.set(0);
  detailSearchTotal.set(0);
  detailSearchErrors.set([]);
  searchFirstMs.set(-1);
  searchAllMs.set(-1);
  searchCacheHit.set(false);
  route.set({ name: "detail" });

  const key = searchKey(movie.title);
  const cached = searchCache.get(key);
  if (cached && Date.now() - cached.at < SEARCH_TTL) {
    detailSources.set(cached.sources);
    detailSearchDone.set(cached.done);
    detailSearchTotal.set(cached.total);
    detailSearchErrors.set(cached.errors);
    searchFirstMs.set(cached.firstMs);
    searchAllMs.set(cached.allMs);
    searchCacheHit.set(true);
    detailLoading.set(false);
    console.log("nshspike: search cache hit " + key);
    if (cached.sources[0]) await selectSource(cached.sources[0]);
    return;
  }

  detailLoading.set(true);
  await runStreamSearch(movie);
}

async function runStreamSearch(movie: Movie) {
  const started = Date.now();
  const key = searchKey(movie.title);
  const bySource = new Map<string, SourceItem>();
  const errors: { sourceName: string; error: string }[] = [];
  let done = 0;
  let total = 0;
  let firstMs = -1;
  let selected = false;
  let finished = false;
  let timer: any = null;
  const id = "search-" + started + "-" + Math.random().toString(36).slice(2, 8);

  if (activeSearchId) {
    try {
      await host.call("http.cancel", { id: activeSearchId });
    } catch {
      // ignore
    }
  }
  activeSearchId = id;
  searchUnsubscribe?.();

  const finish = (error?: string) => {
    if (finished) return;
    finished = true;
    if (timer) clearTimeout(timer);
    searchUnsubscribe?.();
    searchUnsubscribe = null;
    if (activeSearchId === id) activeSearchId = "";
    const allMs = Date.now() - started;
    detailLoading.set(false);
    searchAllMs.set(allMs);
    detailSearchDone.set(done);
    detailSearchTotal.set(total);
    detailSearchErrors.set(errors);
    detailSources.set([...bySource.values()]);
    if (bySource.size === 0) {
      detailError.set(
        (error ? "搜索失败：" + error : "没有找到可播放的真实源") +
          "，可重试或手动试用演示源"
      );
    }
    if (bySource.size > 0) {
      searchCache.set(key, {
        at: Date.now(),
        sources: [...bySource.values()],
        errors: [...errors],
        firstMs,
        allMs,
        done,
        total,
      });
    }
    console.log(
      "nshspike: search metrics first=" +
        firstMs +
        "ms all=" +
        allMs +
        "ms sources=" +
        bySource.size +
        " errors=" +
        errors.length +
        " done=" +
        done +
        "/" +
        total
    );
  };

  timer = setTimeout(() => {
    try {
      void host.call("http.cancel", { id });
    } catch {
      // ignore
    }
    finish("超时");
  }, 45000);

  searchUnsubscribe = host.on("http.stream", (payload: any) => {
    if (!payload || payload.id !== id) return;
    if (payload.done) console.log("nshspike: stream done event " + id + " " + (payload.error || ""));
    if (payload.data) {
      let event: any = null;
      try {
        event = JSON.parse(payload.data);
      } catch {
        return;
      }
      if (event.type === "start") {
        total = event.totalSources ?? 0;
        detailSearchTotal.set(total);
      } else if (event.type === "source_result") {
        done++;
        detailSearchDone.set(done);
        const results: any[] = Array.isArray(event.results) ? event.results : [];
        const usable = results.find((entry) => entry && entry.id);
        if (usable && !bySource.has(String(event.source))) {
          const item: SourceItem = {
            source: String(event.source || usable.source || ""),
            sourceName: event.sourceName || usable.source_name || String(event.source),
            id: String(usable.id),
            title: usable.title || movie.title,
            poster: usable.poster || movie.poster,
            episodes: Array.isArray(usable.episodes) ? usable.episodes : [],
            episodeTitles: Array.isArray(usable.episodes_titles)
              ? usable.episodes_titles
              : [],
          };
          bySource.set(item.source, item);
          detailSources.set([...bySource.values()]);
          if (firstMs < 0) {
            firstMs = Date.now() - started;
            searchFirstMs.set(firstMs);
            console.log("nshspike: search first result " + firstMs + "ms " + item.sourceName);
          }
          if (!selected) {
            selected = true;
            void selectSource(item);
          }
        }
      } else if (event.type === "source_error") {
        done++;
        errors.push({
          sourceName: event.sourceName || event.source || "未知源",
          error: event.error || "失败",
        });
        detailSearchDone.set(done);
        detailSearchErrors.set([...errors]);
      } else if (event.type === "complete") {
        total = event.completedSources ?? total;
        finish();
      }
    }
    if (payload.done) {
      if (payload.error) finish(payload.error);
      else finish();
    }
  });

  try {
    await host.call(
      "http.stream",
      {
        id,
        url: base() + "/api/search/ws?q=" + encodeURIComponent(movie.title),
        headers: headersFor(base() + "/api/search/ws", true),
      },
      { timeout: 15000 }
    );
  } catch (error) {
    finish(String(error));
  }
}

function retryDetail() {
  const movie = detailMovie();
  if (!movie) return;
  searchCache.delete(searchKey(movie.title));
  void openDetail(movie);
}

function useDemoDetail(movie: Movie, reason: string) {
  const proxy = demoProxyUrl();
  const fallback: SourceItem = {
    source: "demo",
    sourceName: "演示源（站点代理）",
    id: "demo",
    title: movie.title,
    poster: movie.poster,
    episodes: [proxy],
    episodeTitles: ["演示第 1 集"],
  };
  detailSources.set([fallback]);
  detailSource.set("demo");
  detailEpisodes.set([proxy]);
  detailEpisodeTitles.set(["演示第 1 集"]);
  detailError.set(reason + "；当前为演示源，可随时换回真实源");
}

async function selectSource(item: SourceItem) {
  detailSource.set(item.source);
  if (item.source === "demo") return;
  detailEpisodes.set(item.episodes);
  detailEpisodeTitles.set(item.episodeTitles);
  detailEpisodesLoading.set(true);
  detailError.set(item.episodes.length > 0 ? "" : "正在获取 " + item.sourceName + " 的集数…");
  try {
    const result = await withTimeout(
      api(
        "/api/source-detail?source=" +
          encodeURIComponent(item.source) +
          "&id=" +
          encodeURIComponent(item.id) +
          "&fresh=1"
      ),
      30000
    );
    if (result.json?.episodes?.length) {
      detailEpisodes.set(result.json.episodes);
      detailEpisodeTitles.set(result.json.episodes_titles ?? []);
      detailError.set("");
      console.log("nshspike: source-detail ok " + item.source + " eps=" + result.json.episodes.length);
    } else {
      detailError.set(item.sourceName + " 没有返回集数，可换源或重试");
      console.log("nshspike: source-detail empty " + item.source);
    }
  } catch (error) {
    detailError.set(item.sourceName + " 集数加载失败：" + String(error) + "，可换源或重试");
    console.log("nshspike: source-detail fail " + item.source + " " + String(error));
  } finally {
    detailEpisodesLoading.set(false);
  }
}

function playDetailEpisode(index: number) {
  const item = detailSources().find((source) => source.source === detailSource()) ?? detailSources()[0];
  if (!item) return;
  const titles = detailEpisodeTitles();
  openPlay(
    detailTitle(),
    detailPoster() || item.poster,
    item.source,
    item.sourceName,
    item.id,
    detailEpisodes(),
    titles,
    index,
    0
  );
}

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return "0:00";
  const total = Math.floor(seconds);
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return minutes + ":" + String(rest).padStart(2, "0");
}

function Header(props: { title: string; onBack?: () => void; right?: any }) {
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
        <Text onPress={props.onBack} style={{ color: "#8ab4ff", fontSize: 15, paddingRight: 8 }}>
          ‹ 返回
        </Text>
      ) : null}
      <Text
        style={{ color: "#f2f2f5", fontSize: 16, fontWeight: "600", flex: 1, numberOfLines: 1 }}
      >
        {props.title}
      </Text>
      {props.right}
    </View>
  );
}

function Card(props: { movie: Movie }) {
  return (
    <View onPress={() => void openDetail(props.movie)} style={{ width: "47.8%", gap: 4 }}>
      {props.movie.poster ? (
        <Image
          src={props.movie.poster}
          style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, backgroundColor: "#1c1c22" }}
        />
      ) : (
        <View
          style={{
            width: "100%",
            aspectRatio: 2 / 3,
            borderRadius: 8,
            backgroundColor: "#1c1c22",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <Text style={{ color: "#666", fontSize: 12 }}>无海报</Text>
        </View>
      )}
      <Text style={{ color: "#e8e8ee", fontSize: 13, numberOfLines: 1 }}>{props.movie.title}</Text>
      <Text style={{ color: "#7f7f8c", fontSize: 11, numberOfLines: 1 }}>
        {(props.movie.rate ? "★ " + props.movie.rate + "  " : "") + (props.movie.year ?? "")}
      </Text>
    </View>
  );
}

function LoginScreen() {
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header title="登录 MoonTVPlus" />
      <Scroll style={{ flex: 1 }}>
        <View style={{ padding: 16, gap: 12 }}>
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>服务器</Text>
          <Input
            value={loginServer()}
            onChange={(event) => loginServer.set(event.value)}
            autoCapitalize="none"
            style={{ color: "#fff", backgroundColor: "#17171d", borderRadius: 8, padding: 10 }}
          />
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>用户名</Text>
          <Input
            value={loginUser()}
            onChange={(event) => loginUser.set(event.value)}
            autoCapitalize="none"
            style={{ color: "#fff", backgroundColor: "#17171d", borderRadius: 8, padding: 10 }}
          />
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>密码</Text>
          <Input
            value={loginPassword()}
            onChange={(event) => loginPassword.set(event.value)}
            masked
            autoCapitalize="none"
            style={{ color: "#fff", backgroundColor: "#17171d", borderRadius: 8, padding: 10 }}
          />
          {loginError() ? <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{loginError()}</Text> : null}
          <View
            onPress={() => void doLogin()}
            style={{ backgroundColor: "#2c5bd6", borderRadius: 8, paddingVertical: 12, alignItems: "center" }}
          >
            <Text style={{ color: "#fff", fontSize: 14 }}>{busy() || "登录"}</Text>
          </View>
          <Text style={{ color: "#7f7f8c", fontSize: 11 }}>
            密码不落盘；会话 token 保存在应用私有目录，重启后自动恢复。token 过期会走
            /api/auth/refresh。
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function HomeScreen() {
  const historyItem = latestHistory();
  const resumeItem = resume();
  const resumeTitle = historyItem?.title ?? resumeItem?.title ?? "";
  const resumeEpisode = historyItem ? historyItem.index - 1 : resumeItem?.episodeIndex ?? 0;
  const resumePosition = historyItem?.play_time ?? resumeItem?.position ?? 0;
  return (
    <View style={{ flex: 1 }}>
      <Header
        title="MoonTVPlus · Native Shell"
        right={
          <View style={{ flexDirection: "row", gap: 10, alignItems: "center" }}>
            <Text onPress={() => route.set({ name: "favorites" })} style={{ color: "#8b8b98", fontSize: 11 }}>收藏</Text>
            <Text onPress={() => route.set({ name: "history" })} style={{ color: "#8b8b98", fontSize: 11 }}>历史</Text>
            <Text onPress={() => route.set({ name: "settings" })} style={{ color: "#8b8b98", fontSize: 11 }}>设置</Text>
            <Text style={{ color: "#8b8b98", fontSize: 12 }}>{username()}</Text>
            <Text
              onPress={() => {
                void clearSession();
                route.set({ name: "login" });
              }}
              style={{ color: "#8ab4ff", fontSize: 13 }}
            >
              退出
            </Text>
          </View>
        }
      />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ padding: 12, gap: 12 }}>
          {busy() ? <Text style={{ color: "#8b8b98" }}>{busy()}</Text> : null}
          {homeError() ? <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{homeError()}</Text> : null}

          {historyItem || resumeItem ? (
            <View
              onPress={() => {
                if (latestHistory()) void openHistoryRecord(latestHistory()!);
                else resumePlayback();
              }}
              style={{ backgroundColor: "#151a24", borderRadius: 10, padding: 12, gap: 6 }}
            >
              <Text style={{ color: "#8ab4ff", fontSize: 12 }}>继续观看</Text>
              <Text style={{ color: "#f2f2f5", fontSize: 15, numberOfLines: 1 }}>
                {resumeTitle}
              </Text>
              <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                第 {resumeEpisode + 1} 集 · {formatTime(resumePosition)}
              </Text>
            </View>
          ) : null}

          <View
            onPress={openDemo}
            style={{ backgroundColor: "#20180f", borderRadius: 10, padding: 12, gap: 4 }}
          >
            <Text style={{ color: "#e2b07e", fontSize: 13 }}>试用演示源：真实 TS + 站点代理 + Bearer</Text>
            <Text style={{ color: "#8b8b98", fontSize: 11 }}>
              用 ExoPlayer 带 Bearer 访问 /api/proxy-m3u8
            </Text>
          </View>

          <View style={{ flexDirection: "row", gap: 8, alignItems: "center" }}>
            <Input
              value={searchQuery()}
              onChange={(event) => searchQuery.set(event.value)}
              autoCapitalize="none"
              style={{
                flex: 1,
                color: "#fff",
                backgroundColor: "#17171d",
                borderRadius: 8,
                padding: 9,
              }}
            />
            <View
              onPress={() => {
                const query = searchQuery().trim();
                if (!query) return;
                void openDetail({ id: "search", title: query, poster: "", rate: "", year: "" });
              }}
              style={{ backgroundColor: "#2c5bd6", borderRadius: 8, paddingHorizontal: 14, paddingVertical: 9 }}
            >
              <Text style={{ color: "#fff", fontSize: 13 }}>搜索</Text>
            </View>
          </View>

          <Text style={{ color: "#f2f2f5", fontSize: 16, fontWeight: "600" }}>热门电影</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10 }}>
            {movies().map((movie) => (
              <Card movie={movie} />
            ))}
          </View>

          <Text style={{ color: "#f2f2f5", fontSize: 16, fontWeight: "600" }}>番剧</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10 }}>
            {bangumi().map((movie) => (
              <Card movie={movie} />
            ))}
          </View>

          <Text
            onPress={() => void loadHome()}
            style={{ color: "#8ab4ff", fontSize: 13, paddingVertical: 12 }}
          >
            刷新
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function FavoritesScreen() {
  effect(() => {
    void loadFavorites();
  });
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header title="收藏" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }}>
        <View style={{ padding: 12, gap: 10 }}>
          {favorites().length === 0 ? <Text style={{ color: "#8b8b98" }}>还没有收藏</Text> : null}
          {favorites().map((item) => {
            const id = item.key.split("+").slice(1).join("+");
            return (
              <View style={{ backgroundColor: "#15151b", borderRadius: 8, padding: 10, gap: 4 }}>
                <Text
                  onPress={() => void openDetail({ id, title: item.title, poster: item.cover, year: item.year })}
                  style={{ color: "#f2f2f5", fontSize: 14 }}
                >
                  {item.title}
                </Text>
                <Text style={{ color: "#8b8b98", fontSize: 11 }}>{item.source_name}</Text>
                <Text
                  onPress={() => {
                    void api("/api/favorites?key=" + encodeURIComponent(item.key), "DELETE")
                      .then(() => loadFavorites())
                      .catch(() => undefined);
                  }}
                  style={{ color: "#ff9b9b", fontSize: 12 }}
                >
                  取消收藏
                </Text>
              </View>
            );
          })}
        </View>
      </Scroll>
    </View>
  );
}

function HistoryScreen() {
  effect(() => {
    void loadHistory();
  });
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header
        title="播放历史"
        onBack={() => route.set({ name: "home" })}
        right={
          <Text
            onPress={() => {
              void api("/api/playrecords", "DELETE")
                .then(() => loadHistory())
                .catch(() => undefined);
            }}
            style={{ color: "#ff9b9b", fontSize: 12 }}
          >
            清空
          </Text>
        }
      />
      <Scroll style={{ flex: 1 }}>
        <View style={{ padding: 12, gap: 10 }}>
          {historyRecords().length === 0 ? <Text style={{ color: "#8b8b98" }}>还没有播放记录</Text> : null}
          {historyRecords().map((record) => (
            <View style={{ backgroundColor: "#15151b", borderRadius: 8, padding: 10, gap: 4 }}>
              <Text onPress={() => void openHistoryRecord(record)} style={{ color: "#f2f2f5", fontSize: 14 }}>
                {record.title}
              </Text>
              <Text style={{ color: "#8b8b98", fontSize: 11 }}>
                第 {record.index} 集 · {formatTime(record.play_time)} · {record.source_name}
              </Text>
              <Text
                onPress={() => {
                  void api("/api/playrecords?key=" + encodeURIComponent(record.key), "DELETE")
                    .then(() => loadHistory())
                    .catch(() => undefined);
                }}
                style={{ color: "#ff9b9b", fontSize: 12 }}
              >
                删除
              </Text>
            </View>
          ))}
        </View>
      </Scroll>
    </View>
  );
}

function SettingsScreen() {
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header title="设置" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }}>
        <View style={{ padding: 14, gap: 12 }}>
          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>服务器地址</Text>
          <Input
            value={loginServer()}
            onChange={(event) => loginServer.set(event.value)}
            autoCapitalize="none"
            style={{ color: "#fff", backgroundColor: "#17171d", borderRadius: 8, padding: 10 }}
          />
          <View
            onPress={() => {
              const value = loginServer().trim().replace(/\/+$/, "");
              if (!value) return;
              server.set(value);
              void Storage.setItem("server", value);
              settingsMessage.set("服务器已保存，重新登录后生效");
            }}
            style={{ backgroundColor: "#2c5bd6", borderRadius: 8, paddingVertical: 10, alignItems: "center" }}
          >
            <Text style={{ color: "#fff", fontSize: 13 }}>保存服务器</Text>
          </View>
          {settingsMessage() ? <Text style={{ color: "#8ab4ff", fontSize: 12 }}>{settingsMessage()}</Text> : null}

          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>播放偏好</Text>
          <Text style={{ color: "#8b8b98", fontSize: 12 }}>倍速记忆：{rate()}×</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
            {["auto", "720p", "1080p"].map((value) => (
              <Option
                label={value === "auto" ? "清晰度自动" : value}
                active={defaultQuality() === value}
                onPress={() => {
                  defaultQuality.set(value);
                  void Storage.setItem("defaultQuality", value);
                }}
              />
            ))}
          </View>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <Option label="自动连播开" active={autoPlay()} onPress={() => {
              autoPlay.set(true);
              void Storage.setItem("autoPlay", "true");
            }} />
            <Option label="自动连播关" active={!autoPlay()} onPress={() => {
              autoPlay.set(false);
              void Storage.setItem("autoPlay", "false");
            }} />
          </View>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <Option label="弹幕开" active={danmakuEnabled()} onPress={() => {
              danmakuEnabled.set(true);
              void Storage.setItem("danmakuEnabled", "true");
              pushDanmaku();
            }} />
            <Option label="弹幕关" active={!danmakuEnabled()} onPress={() => {
              danmakuEnabled.set(false);
              void Storage.setItem("danmakuEnabled", "false");
              pushDanmaku();
            }} />
          </View>

          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>账号</Text>
          <View
            onPress={() => {
              void clearSession();
              route.set({ name: "login" });
            }}
            style={{ backgroundColor: "#2a1a1a", borderRadius: 8, paddingVertical: 10, alignItems: "center" }}
          >
            <Text style={{ color: "#ff9b9b", fontSize: 13 }}>退出登录</Text>
          </View>

          <Text style={{ color: "#c9c9d2", fontSize: 13 }}>关于</Text>
          <Text style={{ color: "#8b8b98", fontSize: 12 }}>MoonTVPlus Native Shell · 0.1.0（P1.3）</Text>
          <View
            onPress={() => settingsMessage.set("当前已是最新开发版，自动下载未接")}
            style={{ backgroundColor: "#17171d", borderRadius: 8, paddingVertical: 10, alignItems: "center" }}
          >
            <Text style={{ color: "#8ab4ff", fontSize: 13 }}>检查更新</Text>
          </View>
        </View>
      </Scroll>
    </View>
  );
}

function DetailScreen() {
  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      <Header title={detailTitle()} onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ padding: 12, gap: 12 }}>
          <View style={{ flexDirection: "row", gap: 12 }}>
            {detailPoster() ? (
              <Image
                src={detailPoster()}
                style={{ width: 96, height: 144, borderRadius: 8, backgroundColor: "#1c1c22" }}
              />
            ) : null}
            <View style={{ flex: 1, gap: 6 }}>
              <Text style={{ color: "#f2f2f5", fontSize: 18, fontWeight: "600" }}>{detailTitle()}</Text>
              <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                {detailSources().length} 个源 · {detailEpisodes().length} 集
              </Text>
              {detailLoading() ? (
                <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                  搜索中 {detailSearchDone()}/{detailSearchTotal() || "?"} 源
                  {searchFirstMs() >= 0 ? " · 首条 " + searchFirstMs() + "ms" : ""}
                </Text>
              ) : null}
              {searchCacheHit() ? (
                <Text style={{ color: "#7ee2a8", fontSize: 12 }}>
                  缓存命中 · 首条 {searchFirstMs()}ms / 全部 {searchAllMs()}ms
                </Text>
              ) : null}
              {!detailLoading() && !searchCacheHit() && searchAllMs() >= 0 ? (
                <Text style={{ color: "#7ee2a8", fontSize: 12 }}>
                  首条 {searchFirstMs()}ms / 全部 {searchAllMs()}ms
                </Text>
              ) : null}
              {detailSearchErrors().length > 0 ? (
                <Text style={{ color: "#e2b07e", fontSize: 11, numberOfLines: 2 }}>
                  失败源 {detailSearchErrors().length}:{" "}
                  {detailSearchErrors()
                    .slice(0, 3)
                    .map((item) => item.sourceName)
                    .join("、")}
                </Text>
              ) : null}
              {detailError() ? <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{detailError()}</Text> : null}
              {!detailLoading() && detailSources().length === 0 ? (
                <View style={{ flexDirection: "row", gap: 12, paddingTop: 4 }}>
                  <Text onPress={retryDetail} style={{ color: "#8ab4ff", fontSize: 13 }}>
                    重试
                  </Text>
                  <Text
                    onPress={() => {
                      const movie = detailMovie();
                      if (movie) useDemoDetail(movie, "手动试用演示源");
                    }}
                    style={{ color: "#e2b07e", fontSize: 13 }}
                  >
                    试用演示源
                  </Text>
                </View>
              ) : null}
            </View>
          </View>

          {detailSources().length > 0 ? (
            <View style={{ gap: 6 }}>
              <Text style={{ color: "#c9c9d2", fontSize: 13 }}>选源</Text>
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                {detailSources().map((source) => (
                  <View
                    onPress={() => void selectSource(source)}
                    style={{
                      paddingHorizontal: 10,
                      paddingVertical: 7,
                      borderRadius: 6,
                      backgroundColor: source.source === detailSource() ? "#2c5bd6" : "#1c1c22",
                    }}
                  >
                    <Text style={{ color: "#fff", fontSize: 12 }}>{source.sourceName}</Text>
                  </View>
                ))}
              </View>
            </View>
          ) : null}

          {detailEpisodesLoading() ? (
            <Text style={{ color: "#8b8b98", fontSize: 12 }}>正在获取集数…</Text>
          ) : null}

          {detailSources().length > 0 && detailEpisodes().length === 0 && !detailEpisodesLoading() ? (
            <View style={{ flexDirection: "row", gap: 12 }}>
              <Text
                onPress={() => {
                  const item =
                    detailSources().find((source) => source.source === detailSource()) ??
                    detailSources()[0];
                  if (item) void selectSource(item);
                }}
                style={{ color: "#8ab4ff", fontSize: 13 }}
              >
                重试集数
              </Text>
              <Text style={{ color: "#8b8b98", fontSize: 13 }}>或在上方换源</Text>
            </View>
          ) : null}

          {detailEpisodes().length > 0 ? (
            <View style={{ gap: 6 }}>
              <Text style={{ color: "#c9c9d2", fontSize: 13 }}>选集</Text>
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                {detailEpisodes().map((episode, index) => (
                  <View
                    onPress={() => playDetailEpisode(index)}
                    style={{
                      minWidth: 52,
                      paddingHorizontal: 10,
                      paddingVertical: 8,
                      borderRadius: 6,
                      backgroundColor: "#1c1c22",
                    }}
                  >
                    <Text style={{ color: "#fff", fontSize: 12, textAlign: "center" }}>
                      {detailEpisodeTitles()[index]?.replace(/<[^>]+>/g, "") || String(index + 1)}
                    </Text>
                  </View>
                ))}
              </View>
            </View>
          ) : null}

          <Text
            onPress={() => {
              const movie = detailMovie();
              if (movie) useDemoDetail(movie, "手动试用演示源");
            }}
            style={{ color: "#8b7a5a", fontSize: 11, paddingTop: 8 }}
          >
            试用演示源（真实 TS 测试流，手动）
          </Text>
        </View>
      </Scroll>
    </View>
  );
}

function playProgress(): number {
  return playDuration() > 0 ? Math.min(100, (playPosition() / playDuration()) * 100) : 0;
}

function Option(props: { label: string; active?: boolean; onPress: () => void }) {
  return (
    <View
      onPress={props.onPress}
      style={{
        paddingHorizontal: 12,
        paddingVertical: 8,
        borderRadius: 6,
        backgroundColor: props.active ? "#2c5bd6" : "#1c1c24",
      }}
    >
      <Text style={{ color: props.active ? "#fff" : "#c9c9d2", fontSize: 12 }}>{props.label}</Text>
    </View>
  );
}

function qualityLabel(): string {
  const selected = videoTracks().find((item) => item.selected);
  return selected ? selected.label : "自动";
}

function audioLabel(): string {
  const selected = audioTracks().find((item) => item.selected);
  return selected ? selected.label : "默认";
}

function uniqueVideo(): TrackOption[] {
  const seen = new Set<string>();
  const out: TrackOption[] = [];
  for (const item of videoTracks()) {
    const key = item.label + "|" + (item.height ?? 0);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

function handlePlayerTap(count: number) {
  if (Date.now() < suppressTapUntil) return;
  if (count >= 2) {
    if (tapTimer) {
      clearTimeout(tapTimer);
      tapTimer = 0;
    }
    paused.update((value) => !value);
    return;
  }
  if (tapTimer) return;
  tapTimer = setTimeout(() => {
    tapTimer = 0;
    controlsVisible.update((value) => !value);
  }, 250);
}

function startLongPress() {
  if (longPressActive) return;
  longPressActive = true;
  logPlay("longpress 2x");
  suppressTapUntil = Date.now() + 700;
  longPressRate = rate();
  rate.set(2);
  playerRef?.command("setRate", 2);
  showHint("长按 2× 播放");
}

function endLongPress() {
  if (!longPressActive) return;
  longPressActive = false;
  logPlay("longpress end " + longPressRate + "x");
  rate.set(longPressRate);
  playerRef?.command("setRate", longPressRate);
}

const playerGesture = PanResponder.create({
  onStartShouldSetPanResponder: () => false,
  onMoveShouldSetPanResponder: (_event, state) =>
    Math.abs(state.dy) > 12 && Math.abs(state.dy) > Math.abs(state.dx),
  onPanResponderGrant: (_event, state) => {
    brightnessBase = brightness();
    volumeBase = volume();
    gestureKind = state.x0 < overlayW() / 2 ? "brightness" : "volume";
  },
  onPanResponderMove: (_event, state) => {
    const delta = -state.dy / Math.max(200, overlayH() * 0.7);
    if (gestureKind === "brightness") {
      const next = clamp01(brightnessBase + delta);
      if (Math.abs(next - brightness()) < 0.01) return;
      brightness.set(next);
      void host.call("device.brightness", { value: next });
      showHint("亮度 " + Math.round(next * 100) + "%");
    } else if (gestureKind === "volume") {
      const next = clamp01(volumeBase + delta);
      if (Math.abs(next - volume()) < 0.01) return;
      volume.set(next);
      void host.call("device.volume", { value: next });
      showHint("音量 " + Math.round(next * 100) + "%");
    }
  },
  onPanResponderRelease: () => {
    gestureKind = "";
    endLongPress();
  },
  onPanResponderTerminate: () => {
    gestureKind = "";
    endLongPress();
  },
});

const seekGesture = PanResponder.create({
  onStartShouldSetPanResponder: () => true,
  onMoveShouldSetPanResponder: () => true,
  onPanResponderGrant: (event) => {
    const width = seekBarW() || 1;
    seekStartRatio = clamp01(event.nativeEvent.locationX / width);
    seekPreview.set(seekStartRatio * playDuration());
  },
  onPanResponderMove: (_event, state) => {
    const width = seekBarW() || 1;
    const ratio = clamp01(seekStartRatio + state.dx / width);
    seekPreview.set(ratio * playDuration());
  },
  onPanResponderRelease: () => {
    if (seekPreview() >= 0) {
      playerRef?.command("seek", seekPreview());
      playPosition.set(seekPreview());
    }
    seekPreview.set(-1);
  },
  onPanResponderTerminate: () => seekPreview.set(-1),
});

function changeSubtitleSize(delta: number) {
  const next = Math.max(12, Math.min(30, subtitleSize() + delta));
  subtitleSize.set(next);
  playerRef?.command("setSubtitleStyle", { size: next, outline: subtitleOutline() });
}

function toggleSubtitleOutline() {
  const next = !subtitleOutline();
  subtitleOutline.set(next);
  playerRef?.command("setSubtitleStyle", { size: subtitleSize(), outline: next });
}

function toggleSubtitle() {
  const next = !subtitleEnabled();
  subtitleEnabled.set(next);
  playerRef?.command("setSubtitleEnabled", { enabled: next });
}

function loadExternalSubtitle() {
  const value = subtitleUrl().trim();
  if (!value) return;
  const url = value.startsWith("http://") || value.startsWith("https://") ? value : absolute(value);
  subtitleUrl.set(url);
  playerRef?.command("loadSubtitle", { url });
  showHint("加载外挂字幕");
}

function PlayScreen() {
  const shown = () => (seekPreview() >= 0 ? seekPreview() : playPosition());
  const percent = () =>
    playDuration() > 0 ? Math.min(100, Math.max(0, (shown() / playDuration()) * 100)) : 0;

  return (
    <View style={{ flex: 1, backgroundColor: "#08080b" }}>
      {!fullscreen() && !pipActive() ? (
        <Header
          title={playTitle()}
          onBack={() => {
            saveResume(true);
            paused.set(true);
            if (fullscreen()) setFullscreenMode(false);
            route.set({ name: "detail" });
          }}
        />
      ) : null}

      <View
        onLayout={(event) => {
          overlayW.set(event.bounds.size.width);
          overlayH.set(event.bounds.size.height);
        }}
        style={{
          height: fullscreen() || pipActive() ? undefined : 240,
          flex: fullscreen() || pipActive() ? 1 : undefined,
          position: fullscreen() || pipActive() ? "absolute" : "relative",
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          zIndex: 20,
          backgroundColor: "#000",
        }}
      >
        <Player
          ref={(node) => {
            playerRef = node;
          }}
          url={playEpisodeUrl()}
          headers={playHeaders()}
          paused={paused()}
          rate={rate()}
          startPosition={playStart()}
          title={playTitle()}
          subtitle={playSourceName()}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
          onReady={(event) => {
            logPlay("ready " + (Date.now() - playOpenedAt) + "ms duration=" + event.duration);
            if (!resumeSeekDone && playStart() > 0) {
              playerRef?.command("seek", playStart());
              resumeSeekDone = true;
            }
            playDuration.set((event.duration ?? 0) / 1000);
            playerRef?.command("refreshTracks");
            playerRef?.command("setMediaInfo", { title: playTitle(), subtitle: playSourceName() });
            void loadDanmakuForCurrent();
          }}
          onProgress={(event) => {
            playPosition.set((event.position ?? 0) / 1000);
            playDuration.set((event.duration ?? 0) / 1000);
            saveResume(false);
          }}
          onState={(event) => {
            logPlay("state " + event.state + " +" + (Date.now() - playOpenedAt) + "ms");
            if (event.state === "playing") playError.set("");
            playState.set(event.state ?? "");
            if (event.state === "ended" && autoPlay() && playEpisodeIndex() + 1 < playEpisodes().length &&
                Date.now() - autoNextAt > 3000) {
              autoNextAt = Date.now();
              openPlay(
                playTitle(),
                playPoster(),
                playSource(),
                playSourceName(),
                playId(),
                playEpisodes(),
                playEpisodeTitles(),
                playEpisodeIndex() + 1,
                0
              );
            }
            if (event.state === "playing") paused.set(false);
            if (event.state === "paused") paused.set(true);
          }}
          onError={(event) => playError.set(event.message ?? "播放失败")}
          onTracks={(event) => {
            if (!qualityApplied && defaultQuality() !== "auto") {
              const match = (event.video ?? []).find((item) => item.label.startsWith(defaultQuality()));
              if (match) {
                qualityApplied = true;
                playerRef?.command("selectVideo", { group: match.group, index: match.index });
              }
            }
            logPlay(
              "selected video=" +
              (event.video ?? []).filter((item) => item.selected).map((item) => item.label).join(",") +
              " audio=" +
              (event.audio ?? []).filter((item) => item.selected).map((item) => item.label).join(",") +
              " subtitle=" +
              (event.subtitle ?? []).filter((item) => item.selected).map((item) => item.label).join(",")
            );
            logPlay(
              "tracks video=" + (event.video ?? []).length +
              " audio=" + (event.audio ?? []).length +
              " subtitle=" + (event.subtitle ?? []).length +
              " external=" + event.externalSubtitle
            );
            videoTracks.set(event.video ?? []);
            audioTracks.set(event.audio ?? []);
            subtitleTracks.set(event.subtitle ?? []);
            subtitleEnabled.set(event.subtitleEnabled);
            externalSubtitle.set(event.externalSubtitle);
          }}
          onAuthExpired={(event) => {
            void clearSession();
            if (fullscreen()) setFullscreenMode(false);
            loginError.set(event.message ?? "播放鉴权失败，请重新登录");
            route.set({ name: "login" });
          }}
          onUnsupported={(event) => showHint("暂不支持 " + event.command)}
        />

        <View
          {...playerGesture.panHandlers}
          onPress={(event) => handlePlayerTap(event.clickCount)}
          onLongPress={startLongPress}
          onPressUp={endLongPress}
          delayLongPress={500}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />

        {gestureHint() ? (
          <View
            style={{
              pointerEvents: "none",
              position: "absolute",
              top: 22,
              alignSelf: "center",
              backgroundColor: "rgba(0,0,0,0.72)",
              borderRadius: 8,
              paddingHorizontal: 12,
              paddingVertical: 7,
            }}
          >
            <Text style={{ color: "#fff", fontSize: 13 }}>{gestureHint()}</Text>
          </View>
        ) : null}

        {controlsVisible() && !pipActive() ? (
          <View
            style={{ pointerEvents: "box-none", position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
          >
            <View
              style={{
                flexDirection: "row",
                alignItems: "center",
                gap: 10,
                paddingHorizontal: 10,
                paddingVertical: 8,
                backgroundColor: "rgba(0,0,0,0.42)",
              }}
            >
              <Text
                onPress={() => {
                  saveResume(true);
                  paused.set(true);
                  if (fullscreen()) setFullscreenMode(false);
                  route.set({ name: "detail" });
                }}
                style={{ color: "#fff", fontSize: 15, paddingRight: 4 }}
              >
                ‹
              </Text>
              <Text
                style={{ color: "#fff", fontSize: 14, flex: 1, numberOfLines: 1 }}
              >
                {playTitle()}
              </Text>
              <Text onPress={toggleOrientation} style={{ color: "#c9c9d2", fontSize: 12 }}>
                {orientationLandscape ? "竖屏" : "横屏"}
              </Text>
              <Text
                onPress={() => playerRef?.command("enterPip")}
                style={{ color: "#c9c9d2", fontSize: 12 }}
              >
                PiP
              </Text>
              <Text
                onPress={() => setFullscreenMode(!fullscreen())}
                style={{ color: "#8ab4ff", fontSize: 12 }}
              >
                {fullscreen() ? "退出" : "全屏"}
              </Text>
            </View>

            <View
              style={{ pointerEvents: "box-none", position: "absolute", left: 0, right: 0, top: "42%", alignItems: "center" }}
            >
              <View
                onPress={() => {
                  if (playError()) {
                    playError.set("");
                    playerRef?.command("retry");
                    return;
                  }
                  paused.update((value) => !value);
                }}
                style={{
                  width: 54,
                  height: 54,
                  borderRadius: 27,
                  backgroundColor: "rgba(0,0,0,0.55)",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                <Text style={{ color: "#fff", fontSize: 18 }}>{paused() ? "▶" : "❚❚"}</Text>
              </View>
            </View>

            <View
              style={{
                position: "absolute",
                left: 0,
                right: 0,
                bottom: 0,
                paddingHorizontal: 10,
                paddingVertical: 8,
                gap: 6,
                backgroundColor: "rgba(0,0,0,0.55)",
              }}
            >
              <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                <Text style={{ color: "#fff", fontSize: 11 }}>{formatTime(shown())}</Text>
                <View
                  {...seekGesture.panHandlers}
                  onLayout={(event) => seekBarW.set(event.bounds.size.width)}
                  style={{ flex: 1, height: 24, justifyContent: "center" }}
                >
                  <View style={{ height: 4, borderRadius: 2, backgroundColor: "#3a3a44" }}>
                    <View
                      style={{
                        width: (percent() + "%") as any,
                        height: 4,
                        borderRadius: 2,
                        backgroundColor: "#2c5bd6",
                      }}
                    />
                  </View>
                  <View
                    style={{
                      position: "absolute",
                      left: (percent() + "%") as any,
                      marginLeft: -5,
                      width: 10,
                      height: 10,
                      borderRadius: 5,
                      backgroundColor: "#fff",
                    }}
                  />
                </View>
                <Text style={{ color: "#fff", fontSize: 11 }}>{formatTime(playDuration())}</Text>
              </View>

              <View style={{ flexDirection: "row", gap: 8, flexWrap: "wrap" }}>
                <Text onPress={() => togglePanel("rate")} style={{ color: "#e8e8ee", fontSize: 12, paddingRight: 6 }}>
                  倍速 {rate()}x
                </Text>
                <Text onPress={() => togglePanel("quality")} style={{ color: "#e8e8ee", fontSize: 12, paddingRight: 6 }}>
                  清晰度 {qualityLabel()}
                </Text>
                <Text onPress={() => togglePanel("audio")} style={{ color: "#e8e8ee", fontSize: 12, paddingRight: 6 }}>
                  音轨 {audioLabel()}
                </Text>
                <Text onPress={() => togglePanel("subtitle")} style={{ color: "#e8e8ee", fontSize: 12 }}>
                  字幕 {subtitleTracks().length > 0 || externalSubtitle() ? (subtitleEnabled() ? "开" : "关") : "无"}
                </Text>
                <Text onPress={() => togglePanel("danmaku")} style={{ color: "#e8e8ee", fontSize: 12 }}>
                  弹幕 {danmakuEnabled() ? danmakuItems().length : "关"}
                </Text>
              </View>
            </View>

            {panel() ? (
              <View
                style={{
                  position: "absolute",
                  left: 0,
                  right: 0,
                  bottom: 0,
                  maxHeight: 280,
                  padding: 12,
                  gap: 10,
                  backgroundColor: "rgba(16,16,20,0.97)",
                  borderTopWidth: 1,
                  borderTopColor: "#2a2a33",
                }}
              >
                <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
                  <Text style={{ color: "#f2f2f5", fontSize: 13 }}>
                    {panel() === "rate" ? "播放倍速" : panel() === "quality" ? "清晰度" : panel() === "audio" ? "音轨" : panel() === "danmaku" ? "弹幕" : "字幕"}
                  </Text>
                  <View
                    onPress={() => panel.set("")}
                    style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 6, backgroundColor: "#2a2a33" }}
                  >
                    <Text style={{ color: "#c9c9d2", fontSize: 12 }}>收起</Text>
                  </View>
                </View>
                {panel() === "rate" ? (
                  <View style={{ gap: 8 }}>
                    <Text style={{ color: "#8b8b98", fontSize: 12 }}>播放倍速（记住上次选择）</Text>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                      {[0.5, 0.75, 1, 1.25, 1.5, 2].map((value) => (
                        <Option
                          label={value + "×"}
                          active={rate() === value}
                          onPress={() => applyRate(value)}
                        />
                      ))}
                    </View>
                  </View>
                ) : null}

                {panel() === "quality" ? (
                  <View style={{ gap: 8 }}>
                    <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                      {videoTracks().length > 0 ? "当前源提供的清晰度" : "当前源未暴露清晰度档位"}
                    </Text>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                      <Option label="自动" active={qualityLabel() === "自动"} onPress={() => { playerRef?.command("autoVideo"); showHint("清晰度 自动"); }} />
                      {uniqueVideo().map((item) => (
                        <Option
                          label={item.label}
                          active={item.selected}
                          onPress={() => {
                            logPlay("quality " + item.label);
                            playerRef?.command("selectVideo", { group: item.group, index: item.index });
                            showHint("切换清晰度 " + item.label);
                          }}
                        />
                      ))}
                    </View>
                  </View>
                ) : null}

                {panel() === "audio" ? (
                  <View style={{ gap: 8 }}>
                    <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                      {audioTracks().length > 1 ? "切换音轨（不中断播放）" : "当前源没有多音轨"}
                    </Text>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                      {audioTracks().map((item) => (
                        <Option
                          label={item.label}
                          active={item.selected}
                          onPress={() => {
                            logPlay("audio " + item.label);
                            playerRef?.command("selectAudio", { group: item.group, index: item.index });
                            showHint("切换音轨 " + item.label);
                          }}
                        />
                      ))}
                    </View>
                  </View>
                ) : null}

                {panel() === "danmaku" ? (
                  <View style={{ gap: 10 }}>
                    <Text style={{ color: "#8b8b98", fontSize: 12 }}>{danmakuStatus() || "等待加载"}</Text>
                    <View style={{ flexDirection: "row", gap: 8 }}>
                      <Option label="开" active={danmakuEnabled()} onPress={() => {
                        danmakuEnabled.set(true);
                        void Storage.setItem("danmakuEnabled", "true");
                        pushDanmaku();
                      }} />
                      <Option label="关" active={!danmakuEnabled()} onPress={() => {
                        danmakuEnabled.set(false);
                        void Storage.setItem("danmakuEnabled", "false");
                        pushDanmaku();
                      }} />
                    </View>
                    <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
                      <Text style={{ color: "#8b8b98", fontSize: 12 }}>透明度 {Math.round(danmakuOpacity() * 100)}%</Text>
                      <Text onPress={() => {
                        danmakuOpacity.set(Math.max(0.2, Math.round((danmakuOpacity() - 0.1) * 10) / 10));
                        void Storage.setItem("danmakuOpacity", String(danmakuOpacity()));
                        pushDanmaku();
                      }} style={{ color: "#c9c9d2", fontSize: 14 }}>−</Text>
                      <Text onPress={() => {
                        danmakuOpacity.set(Math.min(1, Math.round((danmakuOpacity() + 0.1) * 10) / 10));
                        void Storage.setItem("danmakuOpacity", String(danmakuOpacity()));
                        pushDanmaku();
                      }} style={{ color: "#c9c9d2", fontSize: 14 }}>＋</Text>
                      <Text style={{ color: "#8b8b98", fontSize: 12 }}>字号 {danmakuFontSize()}</Text>
                      <Text onPress={() => {
                        danmakuFontSize.set(Math.max(12, danmakuFontSize() - 2));
                        void Storage.setItem("danmakuFontSize", String(danmakuFontSize()));
                        pushDanmaku();
                      }} style={{ color: "#c9c9d2", fontSize: 14 }}>−</Text>
                      <Text onPress={() => {
                        danmakuFontSize.set(Math.min(30, danmakuFontSize() + 2));
                        void Storage.setItem("danmakuFontSize", String(danmakuFontSize()));
                        pushDanmaku();
                      }} style={{ color: "#c9c9d2", fontSize: 14 }}>＋</Text>
                    </View>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                      {[
                        ["all", "全部"],
                        ["scroll", "滚动"],
                        ["top", "顶部"],
                        ["bottom", "底部"],
                      ].map(([value, label]) => (
                        <Option label={label} active={danmakuMode() === value} onPress={() => {
                          danmakuMode.set(value);
                          void Storage.setItem("danmakuMode", value);
                          pushDanmaku();
                        }} />
                      ))}
                    </View>
                  </View>
                ) : null}

                {panel() === "subtitle" ? (
                  <View style={{ gap: 10 }}>
                    <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
                      <Text style={{ color: "#8b8b98", fontSize: 12 }}>
                        {externalSubtitle() ? "外挂字幕已加载" : subtitleTracks().length > 0 ? "内嵌字幕" : "无字幕"}
                      </Text>
                      <Text onPress={toggleSubtitle} style={{ color: subtitleEnabled() ? "#8ab4ff" : "#8b8b98", fontSize: 12 }}>
                        {subtitleEnabled() ? "关闭字幕" : "开启字幕"}
                      </Text>
                    </View>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                      {subtitleTracks().map((item) => (
                        <Option
                          label={item.label}
                          active={item.selected}
                          onPress={() => {
                            playerRef?.command("selectSubtitle", { group: item.group, index: item.index });
                            showHint("字幕 " + item.label);
                          }}
                        />
                      ))}
                    </View>
                    <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                      <Input
                        value={subtitleUrl()}
                        onChange={(event) => subtitleUrl.set(event.value)}
                        autoCapitalize="none"
                        placeholder="外挂字幕 URL（.srt / .vtt / .ass）"
                        style={{ flex: 1, color: "#fff", backgroundColor: "#17171d", borderRadius: 6, padding: 8, fontSize: 12 }}
                      />
                      <Text onPress={loadExternalSubtitle} style={{ color: "#8ab4ff", fontSize: 12 }}>
                        加载
                      </Text>
                      {externalSubtitle() ? (
                        <Text
                          onPress={() => {
                            playerRef?.command("clearSubtitle");
                            subtitleUrl.set("");
                            showHint("已移除外挂字幕");
                          }}
                          style={{ color: "#ff9b9b", fontSize: 12 }}
                        >
                          移除
                        </Text>
                      ) : null}
                    </View>
                    <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
                      <Text style={{ color: "#8b8b98", fontSize: 12 }}>字号 {subtitleSize()}</Text>
                      <Text onPress={() => changeSubtitleSize(-2)} style={{ color: "#c9c9d2", fontSize: 14 }}>−</Text>
                      <Text onPress={() => changeSubtitleSize(2)} style={{ color: "#c9c9d2", fontSize: 14 }}>＋</Text>
                      <Text onPress={toggleSubtitleOutline} style={{ color: subtitleOutline() ? "#8ab4ff" : "#8b8b98", fontSize: 12 }}>
                        描边{subtitleOutline() ? "开" : "关"}
                      </Text>
                    </View>
                    <View style={{ flexDirection: "row", gap: 8 }}>
                      <Option
                        label="字幕开"
                        active={subtitleEnabled()}
                        onPress={() => {
                          if (!subtitleEnabled()) toggleSubtitle();
                        }}
                      />
                      <Option
                        label="字幕关"
                        active={!subtitleEnabled()}
                        onPress={() => {
                          if (subtitleEnabled()) toggleSubtitle();
                        }}
                      />
                    </View>
                  </View>
                ) : null}
              </View>
            ) : null}
          </View>
        ) : null}
      </View>

      {!fullscreen() && !pipActive() ? (
        <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
          <View style={{ padding: 12, gap: 8 }}>
            <Text style={{ color: "#8b8b98", fontSize: 12 }}>
              状态 {playState()} · {playSourceName()} · 第 {playEpisodeIndex() + 1} 集 · {rate()}×
            </Text>
            <Text style={{ color: "#666672", fontSize: 11 }}>
              清晰度 {qualityLabel()} · 音轨 {audioLabel()} · 字幕{" "}
              {subtitleTracks().length > 0 || externalSubtitle() ? (subtitleEnabled() ? "开" : "关") : "无"}
            </Text>
            {playError() ? (
              <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
                <Text style={{ color: "#ff8b8b", fontSize: 12 }}>{playError()}</Text>
                <Text
                  onPress={() => {
                    playError.set("");
                    playerRef?.command("retry");
                  }}
                  style={{ color: "#8ab4ff", fontSize: 12 }}
                >
                  重试
                </Text>
              </View>
            ) : null}
            <Text style={{ color: "#555562", fontSize: 10, numberOfLines: 2 }}>
              鉴权由 ExoPlayer 数据源在请求时补 token；播放地址 {playEpisodeUrl()}
            </Text>
          </View>
        </Scroll>
      ) : null}
    </View>
  );
}

function App() {
  effect(() => {
    void boot();
  });

  effect(() => {
    return host.on("pip", (payload: any) => {
      pipActive.set(payload?.active === true);
    });
  });

  effect(() => {
    return host.on("session.token", (payload: any) => {
      if (!payload?.token) return;
      token.set(payload.token);
      void Storage.setItem("token", payload.token);
    });
  });

  return (
    <View style={{ flex: 1, backgroundColor: "#0b0b0f" }}>
      {!booted() ? (
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
          <Text style={{ color: "#8b8b98" }}>启动中…</Text>
        </View>
      ) : null}
      <If when={booted() && route().name === "login"}>
        <LoginScreen />
      </If>
      <If when={booted() && route().name === "home"}>
        <HomeScreen />
      </If>
      <If when={booted() && route().name === "detail"}>
        <DetailScreen />
      </If>
      <If when={booted() && route().name === "play"}>
        <PlayScreen />
      </If>
      <If when={booted() && route().name === "favorites"}>
        <FavoritesScreen />
      </If>
      <If when={booted() && route().name === "history"}>
        <HistoryScreen />
      </If>
      <If when={booted() && route().name === "settings"}>
        <SettingsScreen />
      </If>
    </View>
  );
}

mount(App);


