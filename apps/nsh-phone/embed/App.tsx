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

type Palette = {
  bg: string;
  card: string;
  text: string;
  secondary: string;
  brand: string;
  selected: string;
  accent: string;
  border: string;
  header: string;
  placeholder: string;
};

const lightPalette: Palette = {
  bg: "#F8F9FA",
  card: "#FFFFFF",
  text: "#2C3E50",
  secondary: "#7F8C8D",
  brand: "#1F8F4F",
  selected: "#1A7340",
  accent: "#27AE60",
  border: "#E4E9EE",
  header: "rgba(255,255,255,0.72)",
  placeholder: "#9AA5A8",
};

const darkPalette: Palette = {
  bg: "#121212",
  card: "#1E1E1E",
  text: "#E4E9EE",
  secondary: "#95A5A6",
  brand: "#1F8F4F",
  selected: "#45C07C",
  accent: "#27AE60",
  border: "#3D4F5D",
  header: "rgba(30,30,30,0.72)",
  placeholder: "#7F8C8D",
};

const theme = signal<"light" | "dark">("light");
const themeVersion = signal(0);

function C(): Palette {
  themeVersion();
  return theme() === "light" ? lightPalette : darkPalette;
}

function toggleTheme() {
  theme.update((value) => (value === "light" ? "dark" : "light"));
  themeVersion.update((value) => value + 1);
  void Storage.setItem("theme", theme());
}

const ICON_PATHS: Record<string, string> = {
  home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
  film: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M7 3v18M17 3v18M3 7.5h4M3 12h4M3 16.5h4M17 7.5h4M17 12h4M17 16.5h4"/>',
  tv: '<rect width="20" height="15" x="2" y="7" rx="2"/><polyline points="17 2 12 7 7 2"/>',
  cat: '<path d="M12 5c.67 0 1.35.09 2 .26 1.78-2 5.03-2.84 6.42-2.26 1.4.58-.42 7-.42 7 .57 1.07 1 2.24 1 3.44C21 17.9 16.97 21 12 21s-9-3-9-7.56c0-1.25.5-2.4 1-3.44 0 0-1.89-6.42-.5-7 1.39-.58 4.72.23 6.5 2.23A9.04 9.04 0 0 1 12 5Z"/><path d="M8 14v.5M16 14v.5M11.25 16.25h1.5L12 17l-.75-.75Z"/>',
  clover: '<path d="M16.17 7.83 2 22"/><path d="M4.02 12a2.827 2.827 0 1 1 1.81-4.99A2.827 2.827 0 1 1 12 4.02a2.827 2.827 0 1 1 4.99 1.81A2.827 2.827 0 1 1 19.98 12a2.827 2.827 0 1 1-1.81 4.99A2.827 2.827 0 1 1 12 19.98a2.827 2.827 0 1 1-4.99-1.81A2.827 2.827 0 1 1 4.02 12Z"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>',
  settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
  chevronRight: '<path d="m9 18 6-6-6-6"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
  list: '<path d="M21 15V6"/><path d="M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/><path d="M12 12H3"/><path d="M16 6H3"/><path d="M12 18H3"/>',
  chevronDown: '<path d="m6 9 6 6 6-6"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  arrowLeft: '<path d="m12 19-7-7 7-7M19 12H5"/>',
  pause: '<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>',
  maximize: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>',
  minimize: '<path d="M8 3v3a2 2 0 0 1-2 2H3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M16 21v-3a2 2 0 0 1 2-2h3"/>',
  pip: '<rect x="2" y="4" width="20" height="16" rx="2"/><rect x="12" y="11" width="7" height="6" rx="1"/>',
  rotate: '<path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/>',
  heart: '<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>',
  clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
  play: '<polygon points="6 3 20 12 6 21 6 3"/>',
  radio: '<path d="M4 11a9 9 0 0 1 9 9"/><path d="M4 4a16 16 0 0 1 16 16"/><circle cx="5" cy="19" r="1"/>',
  user: '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
};

function Icon(props: { name: string; color: string; size?: number }) {
  const body = ICON_PATHS[props.name] ?? ICON_PATHS.search;
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' +
    props.color +
    '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    body +
    '</svg>';
  const size = props.size ?? 24;
  return (
    <Image
      src={"data:image/svg+xml;utf8," + encodeURIComponent(svg)}
      style={{ width: size, height: size }}
    />
  );
}

type Movie = { id: string; title: string; poster: string; rate?: string; year?: string; releaseDate?: string };
type DuanjuItem = {
  id: string;
  title: string;
  poster: string;
  episodes: string[];
  episodes_titles?: string[];
  source: string;
  source_name: string;
  year?: string;
};
type HomeModule = { id: string; name: string; enabled: boolean; order: number };

const DEFAULT_HOME_MODULES: HomeModule[] = [
  { id: "hotMovies", name: "热门电影", enabled: true, order: 0 },
  { id: "hotDuanju", name: "热播短剧", enabled: true, order: 1 },
  { id: "bangumiCalendar", name: "新番放送", enabled: true, order: 2 },
  { id: "hotTvShows", name: "热门剧集", enabled: true, order: 3 },
  { id: "hotVarietyShows", name: "热门综艺", enabled: true, order: 4 },
  { id: "upcomingContent", name: "即将上映", enabled: true, order: 5 },
];
type SearchHit = {
  id: string;
  title: string;
  poster: string;
  source: string;
  sourceName: string;
  year: string;
  rate: string;
  typeName: string;
  episodes: string[];
  episodeTitles: string[];
};
type SourceItem = {
  source: string;
  sourceName: string;
  id: string;
  title: string;
  poster: string;
  episodes: string[];
  episodeTitles: string[];
  year?: string;
  typeName?: string;
};
type UpdateInfo = {
  version: string;
  notes: string;
  versionCode: number;
  apk: { url: string; size: number; sha256: string };
};
type LiveSource = {
  key: string;
  name: string;
  ua?: string;
  proxyMode?: "full" | "m3u8-only" | "direct";
  disabled?: boolean;
};
type LiveChannel = {
  id: string;
  tvgId: string;
  name: string;
  logo: string;
  group: string;
  url: string;
};
type EpgProgram = { start: string; end: string; title: string };
type LiveProgram = { now: string; next: string };

type Route = { name: "login" } | { name: "home" } | { name: "search" } | { name: "browse"; kind: BrowseKind } | { name: "duanju" } | { name: "detail" } | { name: "play" } | { name: "live" } | { name: "live-play" } | { name: "favorites" } | { name: "history" } | { name: "settings" };
type BrowseKind = "movie" | "tv" | "anime" | "show";
type BrowseOption = { label: string; value: string };
const BROWSE_OPTIONS: Record<BrowseKind, { title: string; categories: BrowseOption[]; types: BrowseOption[] }> = {
  movie: {
    title: "电影",
    categories: [
      { label: "全部", value: "全部" },
      { label: "热门", value: "热门" },
      { label: "最新", value: "最新" },
      { label: "豆瓣高分", value: "豆瓣高分" },
      { label: "冷门佳片", value: "冷门佳片" },
    ],
    types: [
      { label: "全部", value: "全部" },
      { label: "华语", value: "华语" },
      { label: "欧美", value: "欧美" },
      { label: "韩国", value: "韩国" },
      { label: "日本", value: "日本" },
    ],
  },
  tv: {
    title: "剧集",
    categories: [
      { label: "全部", value: "全部" },
      { label: "最近热门", value: "最近热门" },
    ],
    types: [
      { label: "全部", value: "tv" },
      { label: "国产", value: "tv_domestic" },
      { label: "欧美", value: "tv_american" },
      { label: "日本", value: "tv_japanese" },
      { label: "韩国", value: "tv_korean" },
      { label: "动漫", value: "tv_animation" },
      { label: "纪录片", value: "tv_documentary" },
    ],
  },
  anime: {
    title: "动漫",
    categories: [
      { label: "全部", value: "全部" },
      { label: "番剧", value: "番剧" },
      { label: "剧场版", value: "剧场版" },
    ],
    types: [
      { label: "全部", value: "全部" },
      { label: "日本", value: "日本" },
      { label: "国产", value: "国产" },
    ],
  },
  show: {
    title: "综艺",
    categories: [
      { label: "全部", value: "全部" },
      { label: "最近热门", value: "最近热门" },
    ],
    types: [
      { label: "全部", value: "show" },
      { label: "国内", value: "show_domestic" },
      { label: "国外", value: "show_foreign" },
    ],
  },
};
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
    live?: boolean;
    orientation?: "auto" | "portrait" | "landscape";
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
  props: ["url", "headers", "paused", "startPosition", "rate", "title", "subtitle", "live", "orientation"],
  events: ["progress", "ready", "state", "error", "tracks", "authExpired", "unsupported"],
});

function imageSrc(url: string): string {
  if (!url) return "";
  if (url.startsWith("data:")) return url;
  if (/^https?:/i.test(url)) {
    return base() + "/api/image-proxy?url=" + encodeURIComponent(url);
  }
  return url;
}

const RemoteImage = nativeComponent<
  {
    url: string;
    radius?: number;
    aspectRatio?: number;
    placeholderColor?: string;
    errorColor?: string;
  },
  {}
>("moontvplus.image", {
  props: ["url", "radius", "aspectRatio", "placeholderColor", "errorColor"],
  events: [],
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
const tvShows = signal<Movie[]>([]);
const varietyShows = signal<Movie[]>([]);
const duanjuItems = signal<DuanjuItem[]>([]);
const upcomingMovies = signal<Movie[]>([]);
const homeModules = signal<HomeModule[]>(DEFAULT_HOME_MODULES);
const homeContinueWatching = signal(true);
const duanjuLoading = signal(false);
const duanjuError = signal("");
const playVertical = signal(false);
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
const detailTab = signal<"episodes" | "sources" | "danmaku">("episodes");
const directUrl = signal("");
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
const rememberLogin = signal(true);
const searchQuery = signal("可能的爱");
const liveEnabled = signal(false);
const liveSources = signal<LiveSource[]>([]);
const liveSourceKey = signal("");
const liveChannels = signal<LiveChannel[]>([]);
const liveGroup = signal("全部");
const liveLoading = signal(false);
const liveError = signal("");
const liveChannel = signal<LiveChannel | null>(null);
const liveEpg = signal<Record<string, LiveProgram>>({});
const livePlayError = signal("");
const livePlayState = signal("");
const livePaused = signal(false);
const navVersion = signal(0);
const liveEpgCache: Record<string, { at: number; value: LiveProgram }> = {};
let liveBootStarted = false;
let liveOpenedAt = 0;
const searchResults = signal<SearchHit[]>([]);
const searchBusy = signal(false);
const searchError = signal("");
const searchMode = signal<"aggregate" | "card" | "list">("card");
const searchType = signal("all");
const searchYear = signal("all");
const searchAdvanced = signal(false);
const sheet = signal<"" | "user" | "favorites" | "history" | "settings" | "sources">("");
const sourcePicker = signal<{ title: string; items: SearchHit[] } | null>(null);
const browseKind = signal<BrowseKind>("movie");
const browseCategory = signal("全部");
const browseType = signal("全部");
const browseItems = signal<Movie[]>([]);
const browseLoading = signal(false);
const browseError = signal("");
const historyRecords = signal<HistoryRecord[]>([]);
const favorites = signal<FavoriteItem[]>([]);
const defaultQuality = signal("auto");
const autoPlay = signal(false);
const settingsMessage = signal("");
const appVersionName = signal(String(host.params.versionName ?? "0.0.0"));
const appVersionCode = signal(Number(host.params.versionCode ?? 0));
const updateVisible = signal(false);
const updateInfo = signal<UpdateInfo | null>(null);
const updateBusy = signal(false);
const updateStatus = signal("");
const updateError = signal("");
const updateReceived = signal(0);
const updateTotal = signal(0);
const updateId = signal("");
let autoUpdateScheduled = false;
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
let pickerGuardUntil = 0;

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
      headers: {
        ...headersFor(url, auth),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    },
    { timeout: 90000 }
  );
  return { status: result.status ?? 0, body: result.body ?? "", json: parseBody(result.body ?? "") };
}

let refreshAuthFailed = false;

async function refreshSession(): Promise<boolean> {
  refreshAuthFailed = false;
  if (!token()) return false;
  try {
    const result = await rawRequest(base() + "/api/auth/refresh", "POST", undefined, true);
    if (result.status === 200 && result.json?.ok && result.json?.token) {
      token.set(result.json.token);
      void Storage.setItem("token", result.json.token);
      void syncAuth(server(), result.json.token);
      return true;
    }
    if (result.status === 401) refreshAuthFailed = true;
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
    if (result.status === 401) {
      if (refreshed || refreshAuthFailed) {
        await clearSession();
        route.set({ name: "login" });
        throw new Error("登录已过期，请重新登录");
      }
      throw new Error("网络异常，请稍后重试");
    }
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

function parseLiveTime(value: string): number {
  if (!value) return 0;
  if (value.includes("T") || value.includes("-")) return new Date(value).getTime();
  const match = value.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})$/);
  if (!match) return new Date(value).getTime();
  return new Date(match[1] + "-" + match[2] + "-" + match[3] + "T" + match[4] + ":" + match[5] + ":" + match[6] + match[7]).getTime();
}

async function loadServerConfig() {
  try {
    const result = await api("/api/server-config", "GET", undefined, false);
    console.log("nshspike: server-config " + result.status + " live=" + String(result.json?.LiveEnabled));
    if (result.status === 200) {
      liveEnabled.set(result.json?.LiveEnabled === true);
      navVersion.update((value) => value + 1);
    }
  } catch {
    // 直播开关拿不到时不显示入口，避免点进去空页
  }
}

function liveSource(): LiveSource | undefined {
  return liveSources().find((item) => item.key === liveSourceKey());
}

function liveShownChannels(): LiveChannel[] {
  const group = liveGroup();
  if (!group || group === "全部") return liveChannels();
  return liveChannels().filter((item) => (item.group || "其他") === group);
}

function liveGroupNames(): string[] {
  const names: string[] = ["全部"];
  for (const item of liveChannels()) {
    const group = item.group || "其他";
    if (!names.includes(group)) names.push(group);
  }
  return names;
}

function liveLogo(channel: LiveChannel): string {
  if (!channel.logo) return "";
  return base() + "/api/proxy/logo?url=" + encodeURIComponent(channel.logo) + "&source=" + encodeURIComponent(liveSourceKey());
}

function liveProgramFor(channel: LiveChannel): string {
  return liveEpg()[channel.tvgId]?.now || "";
}

async function loadLivePrograms(channels: LiveChannel[]) {
  const sourceKey = liveSourceKey();
  const now = Date.now();
  const nextMap = { ...liveEpg() };
  let changed = false;
  await Promise.all(channels.slice(0, 16).map(async (channel) => {
    if (!channel.tvgId) return;
    const cached = liveEpgCache[channel.tvgId];
    if (cached && now - cached.at < 300000) {
      nextMap[channel.tvgId] = cached.value;
      changed = true;
      return;
    }
    try {
      const result = await api("/api/live/epg?source=" + encodeURIComponent(sourceKey) + "&tvgId=" + encodeURIComponent(channel.tvgId));
      if (result.status !== 200 || !result.json?.success) return;
      const programs: EpgProgram[] = result.json.data?.programs ?? [];
      const current = programs.find((item) => parseLiveTime(item.start) <= now && parseLiveTime(item.end) > now);
      const following = programs.filter((item) => parseLiveTime(item.start) > now).sort((a, b) => parseLiveTime(a.start) - parseLiveTime(b.start))[0];
      const value = { now: current?.title ?? "", next: following?.title ?? "" };
      liveEpgCache[channel.tvgId] = { at: now, value };
      nextMap[channel.tvgId] = value;
      changed = true;
    } catch {
      // 单个频道节目单失败不影响列表
    }
  }));
  if (changed) liveEpg.set(nextMap);
}

async function loadLiveChannels(key: string) {
  liveLoading.set(true);
  liveError.set("");
  try {
    const result = await withTimeout(api("/api/live/channels?source=" + encodeURIComponent(key)), 30000);
    if (liveSourceKey() !== key) return;
    if (result.status !== 200 || !result.json?.success) {
      liveError.set(result.json?.error || "频道加载失败");
      liveChannels.set([]);
      return;
    }
    const list: LiveChannel[] = (result.json.data ?? []).map((item: any) => ({
      id: String(item.id ?? ""),
      tvgId: String(item.tvgId || item.name || ""),
      name: String(item.name ?? ""),
      logo: String(item.logo ?? ""),
      group: String(item.group || "其他"),
      url: String(item.url ?? ""),
    }));
    console.log("nshspike: live channels " + result.status + " n=" + list.length);
    liveChannels.set(list);
    liveGroup.set("全部");
    if (list.length === 0) liveError.set("该直播源暂无频道");
    void loadLivePrograms(list);
  } catch (error) {
    if (liveSourceKey() === key) liveError.set("频道加载失败：" + String(error));
  } finally {
    if (liveSourceKey() === key) liveLoading.set(false);
  }
}

async function loadLiveSources() {
  liveLoading.set(true);
  liveError.set("");
  try {
    const result = await withTimeout(api("/api/live/sources"), 30000);
    if (result.status !== 200 || !result.json?.success) {
      liveError.set(result.json?.error || "直播源加载失败");
      return;
    }
    const sources: LiveSource[] = result.json.data ?? [];
    console.log("nshspike: live sources " + result.status + " n=" + sources.length);
    liveSources.set(sources);
    const key = liveSourceKey() || sources[0]?.key || "";
    if (!key) {
      liveError.set("站点未配置直播源");
      return;
    }
    if (liveSourceKey() !== key) liveSourceKey.set(key);
    await loadLiveChannels(key);
  } catch (error) {
    console.log("nshspike: live sources failed " + String(error));
    liveError.set("直播源加载失败：" + String(error));
  } finally {
    liveLoading.set(false);
  }
}

function selectLiveSource(key: string) {
  if (liveSourceKey() === key) return;
  liveSourceKey.set(key);
  liveChannels.set([]);
  liveEpg.set({});
  void loadLiveChannels(key);
}

function selectLiveGroup(group: string) {
  liveGroup.set(group);
  const filtered = group === "全部" ? liveChannels() : liveChannels().filter((item) => (item.group || "其他") === group);
  void loadLivePrograms(filtered);
}

function livePlayUrl(channel = liveChannel()): string {
  if (!channel) return "";
  const source = liveSource();
  const mode = source?.proxyMode || "full";
  if (mode === "direct") return channel.url;
  if (/\.mp4(\?|$)/i.test(channel.url)) return channel.url;
  return base() + "/api/proxy/m3u8?url=" + encodeURIComponent(channel.url) + "&moontv-source=" + encodeURIComponent(source?.key || liveSourceKey());
}

function playLiveChannel(channel: LiveChannel) {
  liveOpenedAt = Date.now();
  console.log("nshspike: live open " + channel.name + " " + channel.url);
  liveChannel.set(channel);
  livePlayError.set("");
  livePlayState.set("loading");
  livePaused.set(false);
  if (route().name === "live-play") {
    const nextUrl = livePlayUrl(channel);
    playerRef?.command("load", {
      url: nextUrl,
      headers: headersFor(nextUrl, nextUrl.startsWith(base())),
      live: true,
    });
  } else {
    route.set({ name: "live-play" });
  }
}

function retryLive() {
  livePlayError.set("");
  livePlayState.set("loading");
  playerRef?.command("retry");
}

function updateVersionCode(raw: any): number {
  const direct = Number(raw?.versionCode ?? raw?.apk?.versionCode ?? 0);
  if (Number.isFinite(direct) && direct > 0) return direct;
  const match = String(raw?.notes || "").match(/versionCode\s*(\d+)/i);
  return match ? Number(match[1]) : 0;
}

function updateUrl(url: string): string {
  if (/^https?:/i.test(url)) return url;
  return base() + (url.startsWith("/") ? url : "/" + url);
}

function updatePercent(): number {
  return updateTotal() > 0 ? Math.min(100, Math.round((updateReceived() / updateTotal()) * 100)) : 0;
}

async function checkUpdate(manual: boolean) {
  if (updateBusy()) return;
  if (!manual) {
    const last = Number((await Storage.getItem("lastUpdateCheck")) || 0);
    if (Date.now() - last < 12 * 60 * 60 * 1000) return;
  }
  if (manual) settingsMessage.set("正在检查更新…");
  try {
    const result = await api("/downloads/latest.json", "GET", undefined, false);
    if (result.status !== 200 || !result.json?.version) throw new Error("HTTP " + result.status);
    const raw = result.json;
    const apk = raw.apk ?? {};
    if (!apk.url) throw new Error("latest.json 缺少 APK 地址");
    const info: UpdateInfo = {
      version: String(raw.version),
      notes: String(raw.notes || ""),
      versionCode: updateVersionCode(raw),
      apk: {
        url: String(apk.url),
        size: Number(apk.size || 0),
        sha256: String(apk.sha256 || "").toLowerCase(),
      },
    };
    await Storage.setItem("lastUpdateCheck", String(Date.now()));
    if (info.versionCode <= 0) {
      if (manual) settingsMessage.set("无法比较版本，请手动更新");
      return;
    }
    const newer = info.versionCode > appVersionCode();
    if (newer) {
      updateInfo.set(info);
      updateReceived.set(0);
      updateTotal.set(info.apk.size);
      updateError.set("");
      updateStatus.set("");
      updateVisible.set(true);
    } else if (manual) {
      settingsMessage.set("已是最新版本 " + appVersionName());
    }
  } catch (error) {
    if (manual) settingsMessage.set("检查更新失败：" + String(error));
  }
}

async function downloadUpdate() {
  const info = updateInfo();
  if (!info) return;
  if (info.versionCode <= 0) {
    updateError.set("latest.json 缺少 versionCode，无法校验安装包");
    return;
  }
  updateBusy.set(true);
  updateError.set("");
  updateStatus.set("下载中…");
  updateReceived.set(0);
  updateTotal.set(info.apk.size);
  try {
    const result: any = await host.call("update.download", {
      id: "app-update",
      url: updateUrl(info.apk.url),
      size: info.apk.size,
      sha256: info.apk.sha256,
      versionName: info.version,
      versionCode: info.versionCode,
    }, { timeout: 0 });
    updateId.set(String(result?.id || "app-update"));
  } catch (error) {
    updateBusy.set(false);
    updateStatus.set("");
    updateError.set("下载失败：" + String(error));
  }
}

async function installUpdate(path: string) {
  try {
    const result: any = await host.call("update.install", { path }, { timeout: 0 });
    if (result?.needPermission) {
      updateStatus.set("请允许安装未知应用，返回后自动继续");
    } else if (result?.started) {
      updateStatus.set("已拉起系统安装器");
    }
  } catch (error) {
    updateError.set("无法安装：" + String(error));
  }
}

function cancelUpdate() {
  void host.call("update.cancel", { id: updateId() || "app-update" }, { timeout: 5000 });
  updateBusy.set(false);
  updateStatus.set("已取消");
}

function closeUpdate() {
  if (updateBusy()) return;
  updateVisible.set(false);
}

async function boot() {
  const savedServer = await Storage.getItem("server");
  const savedTheme = await Storage.getItem("theme");
  const savedRemember = await Storage.getItem("rememberLogin");
  if (savedRemember === "false") rememberLogin.set(false);
  if (savedTheme === "dark" || savedTheme === "light") theme.set(savedTheme);
  const savedToken = await Storage.getItem("token");
  const savedUser = await Storage.getItem("username");
  const savedResume = await Storage.getItem("resume");
  if (savedServer) {
    server.set(savedServer);
    loginServer.set(savedServer);
  }
  void loadServerConfig();
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
  if (!autoUpdateScheduled) {
    autoUpdateScheduled = true;
    setTimeout(() => { void checkUpdate(false); }, 4000);
  }
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
    void loadServerConfig();
    if (rememberLogin()) await Storage.setItem("username", user);
    else await Storage.removeItem("username");
    loginPassword.set("");
    route.set({ name: "home" });
    await loadHome();
  } catch (error) {
    loginError.set(String(error));
  } finally {
    busy.set("");
  }
}

async function homeList(path: string): Promise<Movie[]> {
  const result = await withTimeout(api(path), 30000);
  return Array.isArray(result.json?.list) ? result.json.list : [];
}

function readHomeModules(raw: unknown): HomeModule[] | null {
  if (!raw) return null;
  let value: any = raw;
  if (typeof raw === "string") {
    try {
      value = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  return Array.isArray(value) ? (value as HomeModule[]) : null;
}

async function loadHomeLayout() {
  try {
    const result = await api("/api/local-settings-sync", "GET");
    const data = result.json?.payload?.data;
    if (!data) return;
    const saved = readHomeModules(data.homeModules);
    if (saved?.length) {
      homeModules.set(
        DEFAULT_HOME_MODULES.map((base) => {
          const item = saved.find((entry) => entry.id === base.id);
          return item
            ? { ...base, enabled: item.enabled !== false, order: Number(item.order ?? base.order) }
            : base;
        })
      );
    }
    if (data.homeContinueWatchingEnabled !== undefined) {
      homeContinueWatching.set(String(data.homeContinueWatchingEnabled) !== "false");
    }
  } catch {
    console.log("nshspike: home layout defaults (sync unavailable)");
  }
}

function orderedHomeModules(): HomeModule[] {
  return homeModules()
    .filter((item) => item.enabled)
    .sort((a, b) => a.order - b.order);
}

async function homeLoad<T>(work: () => Promise<T>, fallback: T, label: string): Promise<T> {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      return await withTimeout(work(), 15000);
    } catch (error) {
      if (attempt === 0) {
        await new Promise((resolve) => setTimeout(resolve, 800));
        continue;
      }
      console.warn(label + " failed", error);
      return fallback;
    }
  }
  return fallback;
}

async function runPool(jobs: (() => Promise<void>)[], limit: number) {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, jobs.length) }, async () => {
    while (next < jobs.length) {
      const job = jobs[next];
      next += 1;
      await job();
    }
  });
  await Promise.all(workers);
}

async function loadHome() {
  homeError.set("");
  busy.set("加载首页…");
  void loadHomeLayout();
  const jobs: (() => Promise<void>)[] = [
    async () => {
      const data = await homeLoad(
        () => homeList(
          "/api/douban/categories?kind=movie&category=" +
            encodeURIComponent("热门") +
            "&type=" +
            encodeURIComponent("全部") +
            "&limit=24"
        ),
        null as Movie[] | null,
        "movies"
      );
      if (data) movies.set(data);
      else homeError.set("电影加载失败，请稍后重试");
    },
    async () => {
      tvShows.set(await homeLoad(() => homeList("/api/douban/categories?kind=tv&category=tv&type=tv&limit=24"), [] as Movie[], "tv"));
    },
    async () => {
      varietyShows.set(await homeLoad(() => homeList("/api/douban/categories?kind=tv&category=show&type=show&limit=24"), [] as Movie[], "variety"));
    },
    async () => {
      const flat = await homeLoad(
        () =>
          api("/api/bangumi/calendar").then((result) => {
            const list: Movie[] = [];
            if (!Array.isArray(result.json)) return list;
            for (const day of result.json) {
              for (const item of day.items ?? []) {
                if (!item.images) continue;
                list.push({
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
            return list;
          }),
        [] as Movie[],
        "bangumi"
      );
      bangumi.set(flat);
    },
    async () => {
      const data = await homeLoad(
        () =>
          api("/api/duanju/recommends").then((result) =>
            Array.isArray(result.json?.data) ? (result.json.data as DuanjuItem[]) : []
          ),
        [] as DuanjuItem[],
        "duanju"
      );
      duanjuItems.set(data);
    },
    async () => {
      const data = await homeLoad(
        () =>
          api("/api/tmdb/upcoming").then((result) => {
            const list = Array.isArray(result.json?.data) ? result.json.data : [];
            return list
              .map((item: any) => ({
                id: String(item.id ?? ""),
                title: item.title || item.name || "",
                poster: item.poster_path ? "https://image.tmdb.org/t/p/w500" + item.poster_path : "",
                rate: item.vote_average ? Number(item.vote_average).toFixed(1) : "",
                year: String(item.release_date || "").slice(0, 4),
                releaseDate: String(item.release_date || ""),
              }))
              .sort((a: Movie, b: Movie) => String(a.releaseDate).localeCompare(String(b.releaseDate)));
          }),
        [] as Movie[],
        "upcoming"
      );
      upcomingMovies.set(data);
    },
  ];
  await runPool(jobs, 3);
  busy.set("");
}

async function loadDuanju() {
  duanjuLoading.set(true);
  duanjuError.set("");
  try {
    const result = await withTimeout(api("/api/duanju/recommends"), 30000);
    if (result.status !== 200) throw new Error("HTTP " + result.status);
    duanjuItems.set(Array.isArray(result.json?.data) ? result.json.data : []);
  } catch (error) {
    duanjuError.set("短剧加载失败：" + String(error));
  } finally {
    duanjuLoading.set(false);
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
  start: number,
  vertical = false
) {
  playVertical.set(vertical);
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
  if (vertical) {
    setTimeout(() => {
      playerRef?.command("setFullscreen", true);
      playerRef?.command("setOrientation", "portrait");
    }, 60);
  }
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

async function openBrowse(kind: BrowseKind) {
  browseKind.set(kind);
  browseCategory.set(kind === "movie" ? "热门" : kind === "anime" ? "番剧" : "最近热门");
  browseType.set(kind === "tv" ? "tv" : kind === "show" ? "show" : "全部");
  route.set({ name: "browse", kind });
  await loadBrowse();
}

async function loadBrowse() {
  const kind = browseKind();
  browseLoading.set(true);
  browseError.set("");
  browseItems.set([]);
  try {
    const category =
      browseCategory() === "全部"
        ? kind === "movie"
          ? "热门"
          : kind === "anime"
            ? "番剧"
            : "最近热门"
        : browseCategory();
    const response = await api(
      "/api/douban/categories?kind=" +
        encodeURIComponent(kind) +
        "&category=" +
        encodeURIComponent(category) +
        "&type=" +
        encodeURIComponent(browseType()) +
        "&limit=30"
    );
    const data = response.json ?? {};
    browseItems.set(Array.isArray(data.list) ? data.list : []);
  } catch (error) {
    browseError.set(String(error));
  } finally {
    browseLoading.set(false);
  }
}

function toSearchHit(item: SourceItem): SearchHit {
  return {
    id: item.id,
    title: item.title,
    poster: item.poster,
    source: item.source,
    sourceName: item.sourceName,
    year: item.year ?? "",
    rate: "",
    typeName: item.typeName ?? "",
    episodes: item.episodes,
    episodeTitles: item.episodeTitles,
  };
}

function searchTypeOf(item: SearchHit): string {
  const name = (item.typeName || "").toLowerCase();
  if (name.includes("电影") || name.endsWith("片")) return "movie";
  if (name.includes("剧") || name.includes("电视")) return "tv";
  if (name.includes("动漫") || name.includes("动画")) return "anime";
  if (name.includes("综艺") || name.includes("真人秀")) return "show";
  return "";
}

function filteredSearchHits(): SearchHit[] {
  return searchResults().filter((item) => {
    if (searchType() !== "all" && searchTypeOf(item) !== searchType()) return false;
    if (searchYear() !== "all" && item.year !== searchYear()) return false;
    return true;
  });
}

function aggregateSearchHits(): { key: string; title: string; poster: string; year: string; items: SearchHit[] }[] {
  const map = new Map<string, { key: string; title: string; poster: string; year: string; items: SearchHit[] }>();
  for (const item of filteredSearchHits()) {
    const key = item.title + "|" + item.year;
    const group = map.get(key);
    if (group) group.items.push(item);
    else map.set(key, { key, title: item.title, poster: item.poster, year: item.year, items: [item] });
  }
  return [...map.values()];
}

function openPickerFor(title: string, items: SearchHit[]) {
  pickerGuardUntil = Date.now() + 400;
  sourcePicker.set({ title, items });
  sheet.set("sources");
}

async function runSearch() {
  const query = searchQuery().trim();
  if (!query) return;
  const key = searchKey(query);
  searchError.set("");
  searchBusy.set(true);
  const cached = searchCache.get(key);
  if (cached && Date.now() - cached.at < SEARCH_TTL) {
    searchResults.set(cached.sources.map(toSearchHit));
    searchCacheHit.set(true);
    searchBusy.set(false);
    return;
  }
  searchCacheHit.set(false);
  searchResults.set([]);
  await runStreamSearch(
    { id: "search", title: query, poster: "", rate: "", year: "" },
    true
  );
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
  detailTab.set("episodes");
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

async function runStreamSearch(movie: Movie, forSearch = false) {
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
    searchAllMs.set(allMs);
    detailSearchDone.set(done);
    detailSearchTotal.set(total);
    detailSearchErrors.set(errors);
    if (forSearch) {
      searchBusy.set(false);
      searchResults.set([...bySource.values()].map(toSearchHit));
      if (bySource.size === 0) {
        searchError.set(error ? "搜索失败：" + error : "没有找到结果");
      }
    } else {
      detailLoading.set(false);
      detailSources.set([...bySource.values()]);
      if (bySource.size === 0) {
        detailError.set(
          (error ? "搜索失败：" + error : "没有找到可播放的真实源") +
            "，可重试或手动试用演示源"
        );
      }
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
            year: usable.year ? String(usable.year) : "",
            typeName: usable.typeName ? String(usable.typeName) : usable.type_name ? String(usable.type_name) : "",
          };
          bySource.set(item.source, item);
          if (forSearch) {
            searchResults.set([...bySource.values()].map(toSearchHit));
          } else {
            detailSources.set([...bySource.values()]);
          }
          if (firstMs < 0) {
            firstMs = Date.now() - started;
            searchFirstMs.set(firstMs);
            console.log("nshspike: search first result " + firstMs + "ms " + item.sourceName);
          }
          if (!forSearch && !selected) {
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
        <RemoteImage
          url={imageSrc(props.movie.poster)}
          aspectRatio={2 / 3}
          radius={8}
          placeholderColor="#1C1C22"
          errorColor="#1C1C22"
          style={{ width: "100%" }}
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
  const c = C();
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
      }}
    >
      <View
        style={{
          width: "100%",
          maxWidth: 360,
          backgroundColor: c.card,
          borderRadius: 8,
          borderWidth: 1,
          borderColor: c.border,
          padding: 24,
          gap: 12,
        }}
      >
        <Text style={{ fontSize: 24, fontWeight: "700", color: c.brand, textAlign: "center" }}>
          MoonTVPlus
        </Text>
        <Text style={{ fontSize: 13, color: c.secondary, textAlign: "center", marginBottom: 8 }}>
          登录后继续观看
        </Text>

        <Input
          value={loginServer()}
          onChange={(event) => loginServer.set(event.value)}
          autoCapitalize="none"
          placeholder="服务器地址"
          placeholderColor={c.placeholder}
          style={{
            height: 48,
            color: c.text,
            backgroundColor: c.card,
            borderRadius: 8,
            borderWidth: 1,
            borderColor: c.border,
            paddingHorizontal: 12,
            fontSize: 14,
          }}
        />
        <Input
          value={loginUser()}
          onChange={(event) => loginUser.set(event.value)}
          autoCapitalize="none"
          placeholder="用户名"
          placeholderColor={c.placeholder}
          style={{
            height: 48,
            color: c.text,
            backgroundColor: c.card,
            borderRadius: 8,
            borderWidth: 1,
            borderColor: c.border,
            paddingHorizontal: 12,
            fontSize: 14,
          }}
        />
        <Input
          value={loginPassword()}
          onChange={(event) => loginPassword.set(event.value)}
          masked
          autoCapitalize="none"
          placeholder="密码"
          placeholderColor={c.placeholder}
          style={{
            height: 48,
            color: c.text,
            backgroundColor: c.card,
            borderRadius: 8,
            borderWidth: 1,
            borderColor: c.border,
            paddingHorizontal: 12,
            fontSize: 14,
          }}
        />

        <View
          onPress={() => {
            rememberLogin.update((value) => !value);
            void Storage.setItem("rememberLogin", rememberLogin() ? "true" : "false");
          }}
          style={{ flexDirection: "row", alignItems: "center", gap: 8, paddingVertical: 2 }}
        >
          <View
            style={{
              width: 16,
              height: 16,
              borderRadius: 4,
              borderWidth: 1,
              borderColor: rememberLogin() ? c.selected : c.border,
              backgroundColor: rememberLogin() ? c.selected : "transparent",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            {rememberLogin() ? <Text style={{ color: "#fff", fontSize: 11 }}>✓</Text> : null}
          </View>
          <Text style={{ fontSize: 13, color: c.text }}>记住密码</Text>
        </View>

        {loginError() ? <Text style={{ color: "#C0392B", fontSize: 12 }}>{loginError()}</Text> : null}

        <View
          onPress={() => void doLogin()}
          style={{
            height: 48,
            borderRadius: 8,
            backgroundColor: c.brand,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <Text style={{ color: "#fff", fontSize: 16, fontWeight: "600" }}>{busy() || "登录"}</Text>
        </View>

        <Text
          onPress={() => loginError.set("注册请前往网页版完成")}
          style={{ fontSize: 13, color: c.brand, textAlign: "center", paddingTop: 4 }}
        >
          还没有账号？立即注册
        </Text>
      </View>
    </View>
  );
}

function cleanSource(name: string): string {
  return name.replace(/^[^A-Za-z0-9\u4e00-\u9fa5]+/, "").trim() || name;
}

function WebTopBar(props: { title?: string; onBack?: () => void; showSearch?: boolean }) {
  themeVersion();
  const c = C();
  return (
    <View
      style={{
        paddingTop: 24,
        backgroundColor: c.header,
        backdropFilter: "blur(24px)",
        borderBottomWidth: 1,
        borderBottomColor: c.border,
      }}
    >
      <View
        style={{
          height: 48,
          flexDirection: "row",
          alignItems: "center",
          paddingHorizontal: 16,
          gap: 4,
        }}
      >
        <View
          onPress={props.onBack ?? (() => route.set({ name: "search" }))}
          style={{ width: 40, height: 40, alignItems: "center", justifyContent: "center", borderRadius: 20 }}
        >
          <Icon name={props.onBack ? "arrowLeft" : "search"} color={c.text} />
        </View>
        <Text
          style={{
            flex: 1,
            textAlign: "center",
            fontSize: 24,
            fontWeight: "700",
            color: c.brand,
            numberOfLines: 1,
          }}
        >
          {props.title ?? "MoonTVPlus"}
        </Text>
        <View style={{ flexDirection: "row", gap: 4 }}>
          <View
            onPress={toggleTheme}
            style={{ width: 40, height: 40, alignItems: "center", justifyContent: "center", borderRadius: 20 }}
          >
            <Icon name={theme() === "light" ? "moon" : "sun"} color={c.text} />
          </View>
          <View
            onPress={() => sheet.set("user")}
            style={{
              width: 40,
              height: 40,
              alignItems: "center",
              justifyContent: "center",
              borderRadius: 20,
              backgroundColor: c.border,
            }}
          >
            <Text style={{ fontSize: 14, fontWeight: "600", color: c.text }}>
              {(username() || "M").slice(0, 1).toUpperCase()}
            </Text>
          </View>
        </View>
      </View>
    </View>
  );
}

function BottomNav(props: { active: "home" | "search" | "live" | BrowseKind }) {
  themeVersion();
  const c = C();
  navVersion();
  const items: { key: string; label: string; icon: string; kind: BrowseKind | null }[] = [
    { key: "home", label: "首页", icon: "home", kind: null },
    { key: "movie", label: "电影", icon: "film", kind: "movie" },
    { key: "tv", label: "剧集", icon: "tv", kind: "tv" },
    { key: "anime", label: "动漫", icon: "cat", kind: "anime" },
    { key: "show", label: "综艺", icon: "clover", kind: "show" },
    { key: "live", label: "电视直播", icon: "radio", kind: null },
  ];
  return (
    <View
      style={{
        flexDirection: "row",
        backgroundColor: theme() === "light" ? "rgba(255,255,255,0.9)" : "rgba(30,30,30,0.8)",
        backdropFilter: "blur(24px)",
        borderTopWidth: 1,
        borderTopColor: c.border,
      }}
    >
      {items.map((item) => {
        const active = item.key === props.active;
        return (
          <If when={item.key !== "live" || liveEnabled()}>
            <View
              onPress={() => {
                if (item.key === "live") route.set({ name: "live" });
                else if (item.kind) void openBrowse(item.kind as BrowseKind);
                else route.set({ name: "home" });
              }}
              style={{ flex: 1, height: 56, alignItems: "center", justifyContent: "center", gap: 2 }}
            >
              <Icon name={item.icon} color={active ? c.selected : c.secondary} size={24} />
              <Text style={{ fontSize: 12, color: active ? c.selected : c.secondary }}>{item.label}</Text>
            </View>
          </If>
        );
      })}
    </View>
  );
}

function SectionTitle(props: { title: string; onMore?: () => void }) {
  themeVersion();
  const c = C();
  return (
    <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
      <Text style={{ fontSize: 20, fontWeight: "700", color: c.text, lineHeight: 28 }}>{props.title}</Text>
      <View onPress={props.onMore} style={{ flexDirection: "row", alignItems: "center", gap: 2 }}>
        <Text style={{ fontSize: 14, color: c.secondary }}>查看更多</Text>
        <Icon name="chevronRight" color={c.secondary} size={16} />
      </View>
    </View>
  );
}

function PosterCard(props: { movie: Movie; width?: number | string }) {
  themeVersion();
  const c = C();
  return (
    <View onPress={() => void openDetail(props.movie)} style={{ width: props.width ?? 96, gap: 8 }}>
      <View style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
        <RemoteImage
          url={imageSrc(props.movie.poster)}
          radius={8}
          placeholderColor={c.border}
          errorColor={c.border}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />
        {props.movie.rate ? (
          <View
            style={{
              position: "absolute",
              top: 6,
              left: 6,
              paddingHorizontal: 5,
              paddingVertical: 2,
              borderRadius: 4,
              backgroundColor: c.brand,
            }}
          >
            <Text style={{ fontSize: 11, fontWeight: "600", color: "#fff" }}>{props.movie.rate}</Text>
          </View>
        ) : null}
      </View>
      <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, lineHeight: 20, numberOfLines: 1 }}>
        {props.movie.title}
      </Text>
    </View>
  );
}

function HomeRow(props: { title: string; onMore: () => void; children: any }) {
  return (
    <View style={{ gap: 16 }}>
      <SectionTitle title={props.title} onMore={props.onMore} />
      <Scroll horizontal>
        <View style={{ flexDirection: "row", gap: 8, paddingHorizontal: 16 }}>{props.children}</View>
      </Scroll>
    </View>
  );
}

function DuanjuCard(props: { item: DuanjuItem }) {
  themeVersion();
  const c = C();
  return (
    <View
      onPress={() => {
        const episodes = Array.isArray(props.item.episodes) ? props.item.episodes : [];
        if (!episodes.length) return;
        openPlay(
          props.item.title,
          props.item.poster,
          props.item.source,
          props.item.source_name,
          props.item.id,
          episodes,
          Array.isArray(props.item.episodes_titles) && props.item.episodes_titles.length
            ? props.item.episodes_titles
            : episodes.map((_, index) => "第 " + (index + 1) + " 集"),
          0,
          0,
          true
        );
      }}
      style={{ width: "31%", gap: 8 }}
    >
      <View style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
        <RemoteImage
          url={imageSrc(props.item.poster)}
          radius={8}
          placeholderColor={c.border}
          errorColor={c.border}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />
      </View>
      <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, lineHeight: 20, numberOfLines: 1 }}>
        {props.item.title}
      </Text>
    </View>
  );
}

function HomeScreen() {
  themeVersion();
  const c = C();
  const historyItem = latestHistory();
  const resumeItem = resume();
  const resumeTitle = historyItem?.title ?? resumeItem?.title ?? "";
  const resumeEpisode = historyItem ? historyItem.index - 1 : resumeItem?.episodeIndex ?? 0;
  const resumePosition = historyItem?.play_time ?? resumeItem?.position ?? 0;
  const resumeCover = historyItem?.cover ?? resumeItem?.poster ?? "";

  const renderModule = (id: string) => {
    if (id === "hotMovies" && movies().length) {
      return (
        <HomeRow key={id} title="热门电影" onMore={() => void openBrowse("movie")}>
          {movies().map((movie) => (
            <PosterCard key={"movie-" + movie.id} movie={movie} />
          ))}
        </HomeRow>
      );
    }
    if (id === "hotDuanju" && duanjuItems().length) {
      return (
        <HomeRow key={id} title="热播短剧" onMore={() => { void loadDuanju(); route.set({ name: "duanju" }); }}>
          {duanjuItems().map((item) => (
            <View
              key={"duanju-" + item.id}
              onPress={() => {
                const episodes = item.episodes ?? [];
                if (!episodes.length) return;
                openPlay(
                  item.title,
                  item.poster,
                  item.source,
                  item.source_name,
                  item.id,
                  episodes,
                  item.episodes_titles?.length
                    ? item.episodes_titles
                    : episodes.map((_, index) => "第 " + (index + 1) + " 集"),
                  0,
                  0,
                  true
                );
              }}
              style={{ width: 96, gap: 8 }}
            >
              <View style={{ width: 96, aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
                <RemoteImage
                  url={imageSrc(item.poster)}
                  radius={8}
                  placeholderColor={c.border}
                  errorColor={c.border}
                  style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
                />
              </View>
              <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, lineHeight: 20, numberOfLines: 1 }}>{item.title}</Text>
            </View>
          ))}
        </HomeRow>
      );
    }
    if (id === "bangumiCalendar" && bangumi().length) {
      return (
        <HomeRow key={id} title="新番放送" onMore={() => void openBrowse("anime")}>
          {bangumi().map((movie) => (
            <PosterCard key={"anime-" + movie.id} movie={movie} />
          ))}
        </HomeRow>
      );
    }
    if (id === "hotTvShows" && tvShows().length) {
      return (
        <HomeRow key={id} title="热门剧集" onMore={() => void openBrowse("tv")}>
          {tvShows().map((movie) => (
            <PosterCard key={"tv-" + movie.id} movie={movie} />
          ))}
        </HomeRow>
      );
    }
    if (id === "hotVarietyShows" && varietyShows().length) {
      return (
        <HomeRow key={id} title="热门综艺" onMore={() => void openBrowse("show")}>
          {varietyShows().map((movie) => (
            <PosterCard key={"show-" + movie.id} movie={movie} />
          ))}
        </HomeRow>
      );
    }
    if (id === "upcomingContent" && upcomingMovies().length) {
      return (
        <HomeRow key={id} title="即将上映" onMore={() => void openBrowse("movie")}>
          {upcomingMovies().map((movie) => (
            <PosterCard key={"upcoming-" + movie.id} movie={movie} />
          ))}
        </HomeRow>
      );
    }
    return null;
  };

  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <WebTopBar showSearch />
      <View style={{ flexDirection: "row", justifyContent: "flex-end", gap: 8, paddingHorizontal: 16, paddingTop: 8 }}>
        <View
          onPress={() => sheet.set("direct")}
          style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center", borderRadius: 18 }}
        >
          <Icon name="link" color="#3B82F6" size={20} />
        </View>
        <View
          onPress={() => route.set({ name: "search" })}
          style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center", borderRadius: 18 }}
        >
          <Icon name="list" color="#3B82F6" size={20} />
        </View>
      </View>
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ paddingHorizontal: 16, paddingTop: 16, gap: 32 }}>
          {homeError() ? (
            <Text style={{ color: "#C0392B", fontSize: 12 }}>{homeError()}</Text>
          ) : null}
          {homeContinueWatching() && resumeTitle ? (
            <View style={{ gap: 12 }}>
              <SectionTitle title="继续观看" onMore={() => { sheet.set("history"); void loadHistory(); }} />
              <View
                onPress={() => {
                  if (latestHistory()) void openHistoryRecord(latestHistory()!);
                  else resumePlayback();
                }}
                style={{
                  width: 180,
                  height: 120,
                  borderRadius: 8,
                  overflow: "hidden",
                  backgroundColor: c.card,
                }}
              >
                {resumeCover ? (
                  <RemoteImage
                    url={imageSrc(resumeCover)}
                    radius={8}
                    placeholderColor={c.card}
                    errorColor={c.card}
                    style={{ position: "absolute", width: "100%", height: "100%" }}
                  />
                ) : null}
                <View
                  style={{
                    position: "absolute",
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 3,
                    backgroundColor: "rgba(255,255,255,0.25)",
                  }}
                >
                  <View
                    style={{
                      height: 3,
                      width: (historyItem && historyItem.total_time > 0
                        ? Math.min(100, (historyItem.play_time / historyItem.total_time) * 100)
                        : 0) + "%",
                      backgroundColor: c.brand,
                    }}
                  />
                </View>
                <View
                  style={{
                    position: "absolute",
                    left: 0,
                    right: 0,
                    bottom: 3,
                    padding: 8,
                    gap: 2,
                    backgroundColor: "rgba(0,0,0,0.55)",
                  }}
                >
                  <Text style={{ fontSize: 13, fontWeight: "600", color: "#fff", numberOfLines: 1 }}>
                    {resumeTitle}
                  </Text>
                  <Text style={{ fontSize: 11, color: "#fff", numberOfLines: 1 }}>
                    第 {resumeEpisode + 1} 集 · {formatTime(resumePosition)}
                  </Text>
                </View>
              </View>
            </View>
          ) : null}
          {orderedHomeModules().map((item) => renderModule(item.id))}
          <Text onPress={() => void loadHome()} style={{ fontSize: 13, color: c.secondary, paddingVertical: 8 }}>
            刷新
          </Text>
        </View>
      </Scroll>
      <BottomNav active="home" />
    </View>
  );
}

function DuanjuScreen() {
  const c = C();
  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <Header title="短剧" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ padding: 16, gap: 12 }}>
          {duanjuLoading() ? <Text style={{ fontSize: 13, color: c.secondary }}>加载中…</Text> : null}
          {duanjuError() ? (
            <View style={{ gap: 10 }}>
              <Text style={{ fontSize: 13, color: "#C0392B" }}>{duanjuError()}</Text>
              <View
                onPress={() => void loadDuanju()}
                style={{ alignSelf: "flex-start", paddingHorizontal: 14, paddingVertical: 8, borderRadius: 8, backgroundColor: c.brand }}
              >
                <Text style={{ fontSize: 13, color: "#fff" }}>重试</Text>
              </View>
            </View>
          ) : null}
          {!duanjuLoading() && !duanjuError() && duanjuItems().length === 0 ? (
            <Text style={{ fontSize: 13, color: c.secondary, paddingVertical: 24, textAlign: "center" }}>暂无短剧</Text>
          ) : null}
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 12 }}>
            {duanjuItems().map((item) => (
              <DuanjuCard key={item.id + item.source} item={item} />
            ))}
          </View>
        </View>
      </Scroll>
    </View>
  );
}

function SearchScreen() {
  const c = C();
  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <WebTopBar title="搜索" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ paddingHorizontal: 16, paddingTop: 16, gap: 16 }}>
          <View
            style={{
              height: 48,
              flexDirection: "row",
              alignItems: "center",
              gap: 8,
              paddingHorizontal: 12,
              backgroundColor: c.card,
              borderWidth: 1,
              borderColor: c.border,
              borderRadius: 8,
            }}
          >
            <Icon name="search" color={c.secondary} size={20} />
            <Input
              value={searchQuery()}
              onChange={(event) => searchQuery.set(event.value)}
              autoCapitalize="none"
              placeholder="搜索电影、电视剧..."
              placeholderColor={c.placeholder}
              style={{ flex: 1, color: c.text, fontSize: 14 }}
            />
            <Text onPress={() => void runSearch()} style={{ fontSize: 13, color: c.brand }}>
              搜索
            </Text>
          </View>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <View style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.border }}>
              <Text style={{ fontSize: 14, color: c.text }}>影视搜索</Text>
            </View>
          </View>
          {searchBusy() ? <Text style={{ fontSize: 13, color: c.secondary }}>搜索中…</Text> : null}
          {searchError() ? <Text style={{ fontSize: 13, color: "#C0392B" }}>{searchError()}</Text> : null}

          <View
            onPress={() => searchAdvanced.update((value) => !value)}
            style={{ flexDirection: "row", alignItems: "center", justifyContent: "flex-end", gap: 4 }}
          >
            <Text style={{ fontSize: 13, color: c.brand }}>高级</Text>
            <Icon name={searchAdvanced() ? "chevronDown" : "chevronRight"} color={c.secondary} size={16} />
          </View>

          {searchAdvanced() ? (
            <View style={{ gap: 8 }}>
              <Scroll horizontal>
                <View style={{ flexDirection: "row", gap: 8 }}>
                  {([["aggregate", "聚合"], ["card", "卡片"], ["list", "列表"]] as const).map(([value, label]) => (
                    <SettingChip label={label} active={searchMode() === value} onPress={() => searchMode.set(value)} />
                  ))}
                </View>
              </Scroll>
              <Scroll horizontal>
                <View style={{ flexDirection: "row", gap: 8 }}>
                  {([["all", "全部"], ["movie", "电影"], ["tv", "剧集"], ["anime", "动漫"], ["show", "综艺"]] as const).map(([value, label]) => (
                    <SettingChip label={label} active={searchType() === value} onPress={() => searchType.set(value)} />
                  ))}
                </View>
              </Scroll>
              <Scroll horizontal>
                <View style={{ flexDirection: "row", gap: 8 }}>
                  {([["all", "全部"], ["2026", "2026"], ["2025", "2025"], ["2024", "2024"]] as const).map(([value, label]) => (
                    <SettingChip label={label} active={searchYear() === value} onPress={() => searchYear.set(value)} />
                  ))}
                </View>
              </Scroll>
            </View>
          ) : null}

          {searchMode() === "list" ? (
            <View style={{ gap: 12 }}>
              {filteredSearchHits().map((item) => (
                <View
                  onPress={() => {
                    const same = searchResults().filter((entry) => entry.title === item.title);
                    openPickerFor(item.title, same.length > 0 ? same : [item]);
                  }}
                  style={{ flexDirection: "row", alignItems: "center", gap: 10 }}
                >
                  <RemoteImage
                    url={imageSrc(item.poster)}
                    radius={6}
                    placeholderColor={c.border}
                    errorColor={c.border}
                    style={{ width: 48, height: 72 }}
                  />
                  <View style={{ flex: 1, gap: 4 }}>
                    <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, numberOfLines: 1 }}>{item.title}</Text>
                    <Text style={{ fontSize: 12, color: c.secondary }}>
                      {cleanSource(item.sourceName || item.source)} · {item.year || "年份未知"}
                    </Text>
                  </View>
                  <Icon name="chevronRight" color={c.secondary} size={18} />
                </View>
              ))}
            </View>
          ) : searchMode() === "aggregate" ? (
            <View style={{ flexDirection: "row", flexWrap: "wrap", rowGap: 24, columnGap: 8 }}>
              {aggregateSearchHits().map((group) => (
                <View
                  onPress={() => openPickerFor(group.title, group.items)}
                  style={{ width: "31.5%", gap: 6 }}
                >
<View style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
        <RemoteImage
          url={imageSrc(group.poster)}
          radius={8}
          placeholderColor={c.border}
          errorColor={c.border}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />
      </View>
                  <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, lineHeight: 20, numberOfLines: 2 }}>{group.title}</Text>
                  <Text style={{ fontSize: 11, color: c.secondary }}>{group.items.length} 个源</Text>
                </View>
              ))}
            </View>
          ) : (
            <View
              style={{
                flexDirection: "row",
                flexWrap: "wrap",
                rowGap: 24,
                columnGap: 8,
                opacity: searchType() === "__never__" || searchYear() === "__never__" ? 0.99 : 1,
              }}
            >
              {filteredSearchHits().map((item) => (
                <View
                  onPress={() => {
                    const same = searchResults().filter((entry) => entry.title === item.title);
                    openPickerFor(item.title, same.length > 0 ? same : [item]);
                  }}
                  style={{ width: "31.5%", gap: 6 }}
                >
<View style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
        <RemoteImage
          url={imageSrc(item.poster)}
          radius={8}
          placeholderColor={c.border}
          errorColor={c.border}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />
      </View>
                  <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, lineHeight: 20, numberOfLines: 2 }}>{item.title}</Text>
                  <Text style={{ fontSize: 11, color: c.secondary, numberOfLines: 1 }}>
                    {cleanSource(item.sourceName || item.source)}
                  </Text>
                </View>
              ))}
            </View>
          )}
          {!searchBusy() && filteredSearchHits().length === 0 ? (
            <Text style={{ fontSize: 13, color: c.secondary }}>没有匹配结果</Text>
          ) : null}
        </View>
      </Scroll>
      <BottomNav active="search" />
    </View>
  );
}

function BrowseScreen() {
  const c = C();
  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <WebTopBar title={BROWSE_OPTIONS[browseKind()].title} onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ paddingHorizontal: 16, paddingTop: 16, gap: 12 }}>
          <Scroll horizontal>
            <View style={{ flexDirection: "row", gap: 8 }}>
              {BROWSE_OPTIONS[browseKind()].categories.map((item) => {
                const active = browseCategory() === item.value;
                return (
                  <View
                    onPress={() => {
                      browseCategory.set(item.value);
                      void loadBrowse();
                    }}
                    style={{
                      height: 32,
                      paddingHorizontal: 12,
                      justifyContent: "center",
                      borderRadius: 16,
                      backgroundColor: active ? c.selected : "transparent",
                      borderWidth: 1,
                      borderColor: active ? c.selected : c.border,
                    }}
                  >
                    <Text style={{ fontSize: 14, color: active ? "#fff" : c.text }}>{item.label}</Text>
                  </View>
                );
              })}
            </View>
          </Scroll>
          <Scroll horizontal>
            <View style={{ flexDirection: "row", gap: 8 }}>
              {BROWSE_OPTIONS[browseKind()].types.map((item) => {
                const active = browseType() === item.value;
                return (
                  <View
                    onPress={() => {
                      browseType.set(item.value);
                      void loadBrowse();
                    }}
                    style={{
                      height: 32,
                      paddingHorizontal: 12,
                      justifyContent: "center",
                      borderRadius: 16,
                      backgroundColor: active ? c.selected : "transparent",
                      borderWidth: 1,
                      borderColor: active ? c.selected : c.border,
                    }}
                  >
                    <Text style={{ fontSize: 14, color: active ? "#fff" : c.text }}>{item.label}</Text>
                  </View>
                );
              })}
            </View>
          </Scroll>
          {browseLoading() ? (
            <Text style={{ fontSize: 13, color: c.secondary }}>正在加载…</Text>
          ) : null}
          {browseError() ? (
            <Text style={{ fontSize: 13, color: "#C0392B" }}>{browseError()}</Text>
          ) : null}
          <View style={{ flexDirection: "row", flexWrap: "wrap", rowGap: 24, columnGap: 8 }}>
            {browseItems().map((movie) => (
              <PosterCard movie={movie} width="31.5%" />
            ))}
          </View>
        </View>
      </Scroll>
      <BottomNav active={browseKind()} />
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

function SheetShell(props: { title: string; onClose: () => void; children?: any }) {
  const c = C();
  return (
    <View style={{ flex: 1, paddingTop: 16, paddingHorizontal: 16, gap: 12 }}>
      <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
        <Text style={{ fontSize: 20, fontWeight: "700", color: c.text }}>{props.title}</Text>
        <View
          onPress={props.onClose}
          style={{ width: 40, height: 40, alignItems: "center", justifyContent: "center", borderRadius: 20 }}
        >
          <Icon name="x" color={c.text} size={22} />
        </View>
      </View>
      {props.children}
    </View>
  );
}

function SheetRow(props: { icon: string; label: string; onPress: () => void }) {
  const c = C();
  return (
    <View
      onPress={props.onPress}
      style={{
        height: 48,
        flexDirection: "row",
        alignItems: "center",
        gap: 12,
        borderBottomWidth: 1,
        borderBottomColor: c.border,
      }}
    >
      <Icon name={props.icon} color={c.text} size={22} />
      <Text style={{ flex: 1, fontSize: 15, color: c.text }}>{props.label}</Text>
      <Icon name="chevronRight" color={c.secondary} size={18} />
    </View>
  );
}

function UserSheet() {
  return (
    <SheetShell title={username() || "我的"} onClose={() => sheet.set("")}>
      <SheetRow
        icon="heart"
        label="收藏"
        onPress={() => {
          sheet.set("favorites");
          void loadFavorites();
        }}
      />
      <SheetRow
        icon="clock"
        label="播放历史"
        onPress={() => {
          sheet.set("history");
          void loadHistory();
        }}
      />
      <SheetRow
        icon="settings"
        label="设置"
        onPress={() => {
          sheet.set("settings");
        }}
      />
      <SheetRow
        icon="arrowLeft"
        label="退出登录"
        onPress={() => {
          sheet.set("");
          void clearSession();
          route.set({ name: "login" });
        }}
      />
    </SheetShell>
  );
}

function FavoritesSheet() {
  const c = C();
  effect(() => {
    void loadFavorites();
  });
  return (
    <SheetShell title="收藏" onClose={() => sheet.set("user")}>
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        {favorites().length === 0 ? (
          <Text style={{ fontSize: 13, color: c.secondary, paddingTop: 8 }}>还没有收藏</Text>
        ) : (
          <View style={{ flexDirection: "row", flexWrap: "wrap", rowGap: 24, columnGap: 8, paddingTop: 8 }}>
            {favorites().map((item) => {
              const id = item.key.split("+").slice(1).join("+");
              return (
                <View
                  onPress={() => {
                    sheet.set("");
                    void openDetail({ id, title: item.title, poster: item.cover, year: item.year });
                  }}
                  style={{ width: "31.5%", gap: 6 }}
                >
<View style={{ width: "100%", aspectRatio: 2 / 3, borderRadius: 8, overflow: "hidden" }}>
        <RemoteImage
          url={imageSrc(item.cover)}
          radius={8}
          placeholderColor={c.border}
          errorColor={c.border}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
        />
      </View>
                  <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, numberOfLines: 2 }}>
                    {item.title}
                  </Text>
                  <Text style={{ fontSize: 11, color: c.secondary, numberOfLines: 1 }}>{item.source_name}</Text>
                </View>
              );
            })}
          </View>
        )}
      </Scroll>
    </SheetShell>
  );
}

function HistorySheet() {
  const c = C();
  effect(() => {
    void loadHistory();
  });
  return (
    <SheetShell title="播放历史" onClose={() => sheet.set("user")}>
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        {historyRecords().length === 0 ? (
          <Text style={{ fontSize: 13, color: c.secondary, paddingTop: 8 }}>暂无播放记录</Text>
        ) : (
          <View style={{ flexDirection: "row", flexWrap: "wrap", rowGap: 24, columnGap: 8, paddingTop: 8 }}>
            {historyRecords().map((item) => {
              const percent =
                item.total_time > 0 ? Math.min(100, Math.round((item.play_time / item.total_time) * 100)) : 0;
              return (
                <View
                  onPress={() => {
                    sheet.set("");
                    void openHistoryRecord(item);
                  }}
                  style={{ width: "31.5%", gap: 6 }}
                >
                  <RemoteImage
                    url={imageSrc(item.cover)}
                    aspectRatio={2 / 3}
                    radius={8}
                    placeholderColor={c.border}
                    errorColor={c.border}
                    style={{ width: "100%" }}
                  />
                  <Text style={{ fontSize: 14, fontWeight: "600", color: c.text, numberOfLines: 2 }}>
                    {item.title}
                  </Text>
                  <Text style={{ fontSize: 11, color: c.secondary, numberOfLines: 1 }}>
                    第 {item.index} 集 · {formatTime(item.play_time)}
                  </Text>
                  <View style={{ height: 3, borderRadius: 2, backgroundColor: c.border }}>
                    <View
                      style={{
                        width: String(percent) + "%",
                        height: 3,
                        borderRadius: 2,
                        backgroundColor: c.selected,
                      }}
                    />
                  </View>
                </View>
              );
            })}
          </View>
        )}
      </Scroll>
    </SheetShell>
  );
}

function SettingChip(props: { label: string; active: boolean; onPress: () => void }) {
  const c = C();
  return (
    <View
      onPress={props.onPress}
      style={{
        height: 32,
        paddingHorizontal: 12,
        justifyContent: "center",
        borderRadius: 16,
        backgroundColor: props.active ? c.selected : "transparent",
        borderWidth: 1,
        borderColor: props.active ? c.selected : c.border,
      }}
    >
      <Text style={{ fontSize: 13, color: props.active ? "#fff" : c.text }}>{props.label}</Text>
    </View>
  );
}

function openSourceHit(hit: SearchHit) {
  detailMovie.set({ id: hit.id, title: hit.title, poster: hit.poster, year: hit.year, rate: hit.rate });
  detailTitle.set(hit.title);
  detailPoster.set(hit.poster);
  detailSources.set([
    {
      source: hit.source,
      sourceName: hit.sourceName,
      id: hit.id,
      title: hit.title,
      poster: hit.poster,
      episodes: hit.episodes,
      episodeTitles: hit.episodeTitles,
      year: hit.year,
      typeName: hit.typeName,
    },
  ]);
  detailSource.set(hit.source);
  detailEpisodes.set(hit.episodes);
  detailEpisodeTitles.set(hit.episodeTitles);
  detailLoading.set(false);
  detailError.set(hit.episodes.length > 0 ? "" : "该源没有返回可播放剧集");
  route.set({ name: "detail" });
}

function directPlayHit(hit: SearchHit) {
  if (hit.episodes.length === 0) {
    openSourceHit(hit);
    return;
  }
  openPlay(
    hit.title,
    hit.poster,
    hit.source,
    hit.sourceName,
    hit.id,
    hit.episodes,
    hit.episodeTitles,
    0,
    0
  );
}

function SourcePickerSheet() {
  const c = C();
  const picker = sourcePicker();
  if (!picker) return null;
  const first = picker.items[0];
  return (
    <SheetShell title="选择播放源" onClose={() => sheet.set("")}>
      <Text style={{ fontSize: 14, color: c.secondary }}>{picker.title}</Text>
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ gap: 8, paddingTop: 8 }}>
          {picker.items.map((hit) => (
            <View
              onPress={() => {
                if (Date.now() < pickerGuardUntil) return;
                sheet.set("");
                openSourceHit(hit);
              }}
              style={{
                flexDirection: "row",
                alignItems: "center",
                gap: 10,
                paddingVertical: 12,
                borderBottomWidth: 1,
                borderBottomColor: c.border,
              }}
            >
              <View style={{ flex: 1, gap: 2 }}>
                <Text style={{ fontSize: 15, color: c.text }}>{cleanSource(hit.sourceName || hit.source)}</Text>
                <Text style={{ fontSize: 12, color: c.secondary }}>
                  {hit.year || "年份未知"} · {hit.episodes.length} 集
                </Text>
              </View>
              <Icon name="chevronRight" color={c.secondary} size={18} />
            </View>
          ))}
        </View>
      </Scroll>
      {first ? (
        <View
          onPress={() => {
            if (Date.now() < pickerGuardUntil) return;
            sheet.set("");
            directPlayHit(first);
          }}
          style={{ height: 48, borderRadius: 8, backgroundColor: c.brand, alignItems: "center", justifyContent: "center" }}
        >
          <Text style={{ color: "#fff", fontSize: 16, fontWeight: "600" }}>直接播放第一源</Text>
        </View>
      ) : null}
    </SheetShell>
  );
}

function UpdateSheet() {
  themeVersion();
  const info = updateInfo();
  if (!info) return null;
  return (
    <View style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0 }}>
      <View
        onPress={closeUpdate}
        style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0, backgroundColor: "rgba(0,0,0,0.45)" }}
      />
      <View
        style={{
          position: "absolute",
          left: 16,
          right: 16,
          bottom: 24,
          maxHeight: "78%",
          padding: 20,
          gap: 12,
          borderRadius: 20,
          backgroundColor: C().card,
        }}
      >
        <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
          <Text style={{ fontSize: 18, fontWeight: "700", color: C().text }}>发现新版本 {info.version}</Text>
          <View onPress={closeUpdate} style={{ width: 32, height: 32, alignItems: "center", justifyContent: "center" }}>
            <Icon name="x" color={C().secondary} size={20} />
          </View>
        </View>
        <Text style={{ fontSize: 12, color: C().secondary }}>
          当前 {appVersionName()}（{appVersionCode()}） → 新版本 {info.version}（{info.versionCode || "未知"}）
        </Text>
        <Scroll style={{ maxHeight: 220 }}>
          <Text style={{ fontSize: 12, color: C().text, lineHeight: 20 }}>{info.notes || "暂无更新说明"}</Text>
        </Scroll>
        <Text style={{ fontSize: 11, color: C().secondary }}>
          安装包 {(info.apk.size / 1024 / 1024).toFixed(1)} MB · sha256 {info.apk.sha256.slice(0, 12)}…
        </Text>
        {updateBusy() || updateReceived() > 0 ? (
          <View style={{ gap: 6 }}>
            <View style={{ height: 6, borderRadius: 3, overflow: "hidden", backgroundColor: C().border }}>
              <View style={{ height: 6, width: updatePercent() + "%", backgroundColor: C().brand }} />
            </View>
            <Text style={{ fontSize: 11, color: C().secondary }}>
              {updatePercent()}% · {updateStatus() || "下载中…"}
            </Text>
          </View>
        ) : null}
        {updateError() ? (
          <Text style={{ fontSize: 12, color: "#C0392B" }}>{updateError()}</Text>
        ) : null}
        {updateStatus() && !updateBusy() && !updateError() ? (
          <Text style={{ fontSize: 12, color: C().brand }}>{updateStatus()}</Text>
        ) : null}
        <View style={{ flexDirection: "row", gap: 10 }}>
          {updateBusy() ? (
            <View onPress={cancelUpdate} style={{ flex: 1, paddingVertical: 12, borderRadius: 10, alignItems: "center", borderWidth: 1, borderColor: C().border }}>
              <Text style={{ fontSize: 14, color: C().text }}>取消下载</Text>
            </View>
          ) : updateError() ? (
            <View onPress={() => void downloadUpdate()} style={{ flex: 1, paddingVertical: 12, borderRadius: 10, alignItems: "center", backgroundColor: C().brand }}>
              <Text style={{ fontSize: 14, color: "#FFFFFF" }}>重试</Text>
            </View>
          ) : updateStatus().startsWith("已拉起") || updateStatus().includes("允许安装") ? (
            <View onPress={closeUpdate} style={{ flex: 1, paddingVertical: 12, borderRadius: 10, alignItems: "center", borderWidth: 1, borderColor: C().border }}>
              <Text style={{ fontSize: 14, color: C().text }}>关闭</Text>
            </View>
          ) : (
            <View onPress={() => void downloadUpdate()} style={{ flex: 1, paddingVertical: 12, borderRadius: 10, alignItems: "center", backgroundColor: C().brand }}>
              <Text style={{ fontSize: 14, color: "#FFFFFF" }}>下载并安装</Text>
            </View>
          )}
        </View>
      </View>
    </View>
  );
}

function DirectSheet() {
  const c = C();
  const play = () => {
    const value = directUrl().trim();
    if (!value) return;
    sheet.set("");
    openPlay("直链播放", "", "directplay", "直链", value, [value], ["第 1 集"], 0, 0);
  };
  return (
    <SheetShell title="直链播放" onClose={() => sheet.set("")}>
      <Input
        value={directUrl()}
        onChange={(event) => directUrl.set(event.value)}
        placeholder="输入 m3u8 或视频直链"
        autoCapitalize="none"
        style={{ height: 44, color: c.text, backgroundColor: c.card, borderRadius: 8, paddingHorizontal: 10, borderWidth: 1, borderColor: c.border, fontSize: 13 }}
      />
      <View
        onPress={play}
        style={{ height: 44, borderRadius: 8, backgroundColor: c.brand, alignItems: "center", justifyContent: "center" }}
      >
        <Text style={{ fontSize: 15, fontWeight: "600", color: "#fff" }}>播放</Text>
      </View>
    </SheetShell>
  );
}

function SettingsSheet() {
  const c = C();
  return (
    <SheetShell title="设置" onClose={() => sheet.set("user")}>
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ gap: 12, paddingBottom: 24 }}>
          <Text style={{ fontSize: 13, fontWeight: "600", color: c.secondary }}>账号</Text>
          <Input
            value={loginServer()}
            onChange={(event) => loginServer.set(event.value)}
            autoCapitalize="none"
            style={{ height: 44, color: c.text, backgroundColor: c.card, borderRadius: 8, paddingHorizontal: 10, borderWidth: 1, borderColor: c.border, fontSize: 13 }}
          />
          <View style={{ flexDirection: "row", gap: 8 }}>
            <SettingChip
              label="保存服务器"
              active={false}
              onPress={() => {
                const value = loginServer().trim().replace(/\/+$/, "");
                if (!value) return;
                server.set(value);
                void Storage.setItem("server", value);
                settingsMessage.set("服务器已保存，重新登录后生效");
              }}
            />
            <SettingChip
              label="退出登录"
              active={false}
              onPress={() => {
                sheet.set("");
                void clearSession();
                route.set({ name: "login" });
              }}
            />
          </View>
          {settingsMessage() ? <Text style={{ fontSize: 12, color: c.secondary }}>{settingsMessage()}</Text> : null}

          <Text style={{ fontSize: 13, fontWeight: "600", color: c.secondary }}>播放</Text>
          <Text style={{ fontSize: 12, color: c.secondary }}>倍速记忆：{rate()}×</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
            {["auto", "720p", "1080p"].map((value) => (
              <SettingChip
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
            <SettingChip label="自动连播开" active={autoPlay()} onPress={() => { autoPlay.set(true); void Storage.setItem("autoPlay", "true"); }} />
            <SettingChip label="自动连播关" active={!autoPlay()} onPress={() => { autoPlay.set(false); void Storage.setItem("autoPlay", "false"); }} />
          </View>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <SettingChip label="弹幕开" active={danmakuEnabled()} onPress={() => { danmakuEnabled.set(true); void Storage.setItem("danmakuEnabled", "true"); pushDanmaku(); }} />
            <SettingChip label="弹幕关" active={!danmakuEnabled()} onPress={() => { danmakuEnabled.set(false); void Storage.setItem("danmakuEnabled", "false"); pushDanmaku(); }} />
          </View>

          <Text style={{ fontSize: 13, fontWeight: "600", color: c.secondary }}>外观</Text>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <SettingChip label="浅色" active={theme() === "light"} onPress={() => { if (theme() !== "light") toggleTheme(); }} />
            <SettingChip label="深色" active={theme() === "dark"} onPress={() => { if (theme() !== "dark") toggleTheme(); }} />
          </View>

          <Text style={{ fontSize: 13, fontWeight: "600", color: c.secondary }}>关于</Text>
          <Text style={{ fontSize: 12, color: c.secondary }}>MoonTVPlus Native Shell · {appVersionName()}</Text>
          <SettingChip
            label="检查更新"
            active={false}
            onPress={() => void checkUpdate(true)}
          />
        </View>
      </Scroll>
    </SheetShell>
  );
}

function SettingsScreen() {
  const c = C();
  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <WebTopBar title="设置" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ padding: 16, gap: 12 }}>
          <Text style={{ fontSize: 14, color: c.text }}>外观</Text>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <SettingChip label="浅色" active={theme() === "light"} onPress={() => { if (theme() !== "light") toggleTheme(); }} />
            <SettingChip label="深色" active={theme() === "dark"} onPress={() => { if (theme() !== "dark") toggleTheme(); }} />
          </View>

          <Text style={{ fontSize: 14, color: c.text }}>服务器地址</Text>
          <Input
            value={loginServer()}
            onChange={(event) => loginServer.set(event.value)}
            autoCapitalize="none"
            style={{ color: c.text, backgroundColor: c.card, borderRadius: 8, padding: 10, borderWidth: 1, borderColor: c.border }}
          />
          <View
            onPress={() => {
              const value = loginServer().trim().replace(/\/+$/, "");
              if (!value) return;
              server.set(value);
              void Storage.setItem("server", value);
              settingsMessage.set("服务器已保存，重新登录后生效");
            }}
            style={{ backgroundColor: c.brand, borderRadius: 8, paddingVertical: 12, alignItems: "center" }}
          >
            <Text style={{ color: "#fff", fontSize: 14 }}>保存服务器</Text>
          </View>
          {settingsMessage() ? <Text style={{ color: c.brand, fontSize: 12 }}>{settingsMessage()}</Text> : null}

          <Text style={{ fontSize: 14, color: c.text }}>播放偏好</Text>
          <Text style={{ fontSize: 12, color: c.secondary }}>倍速记忆：{rate()}×</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
            {["auto", "720p", "1080p"].map((value) => (
              <SettingChip
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
            <SettingChip label="自动连播开" active={autoPlay()} onPress={() => { autoPlay.set(true); void Storage.setItem("autoPlay", "true"); }} />
            <SettingChip label="自动连播关" active={!autoPlay()} onPress={() => { autoPlay.set(false); void Storage.setItem("autoPlay", "false"); }} />
          </View>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <SettingChip label="弹幕开" active={danmakuEnabled()} onPress={() => { danmakuEnabled.set(true); void Storage.setItem("danmakuEnabled", "true"); pushDanmaku(); }} />
            <SettingChip label="弹幕关" active={!danmakuEnabled()} onPress={() => { danmakuEnabled.set(false); void Storage.setItem("danmakuEnabled", "false"); pushDanmaku(); }} />
          </View>

          <Text style={{ fontSize: 14, color: c.text }}>账号</Text>
          <View
            onPress={() => {
              void clearSession();
              route.set({ name: "login" });
            }}
            style={{ backgroundColor: c.card, borderWidth: 1, borderColor: c.border, borderRadius: 8, paddingVertical: 12, alignItems: "center" }}
          >
            <Text style={{ color: "#C0392B", fontSize: 14 }}>退出登录</Text>
          </View>

          <Text style={{ fontSize: 14, color: c.text }}>关于</Text>
          <Text style={{ fontSize: 12, color: c.secondary }}>MoonTVPlus Native Shell · {appVersionName()}（{appVersionCode()}）</Text>
          <View
            onPress={() => void checkUpdate(true)}
            style={{ backgroundColor: c.card, borderWidth: 1, borderColor: c.border, borderRadius: 8, paddingVertical: 12, alignItems: "center" }}
          >
            <Text style={{ color: c.brand, fontSize: 14 }}>检查更新</Text>
          </View>
        </View>
      </Scroll>
    </View>
  );
}

function DetailScreen() {
  const c = C();
  return (
    <View style={{ flex: 1, backgroundColor: theme() === "light" ? "#F8F9FA" : "#121212" }}>
      <WebTopBar title="详情" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ paddingHorizontal: 16, paddingTop: 16, gap: 16 }}>
          <View style={{ flexDirection: "row", gap: 12 }}>
            {detailPoster() ? (
              <RemoteImage
                url={imageSrc(detailPoster())}
                radius={8}
                placeholderColor={c.border}
                errorColor={c.border}
                style={{ width: 96, height: 144 }}
              />
            ) : null}
            <View style={{ flex: 1, gap: 6 }}>
              <Text style={{ fontSize: 20, fontWeight: "700", color: c.text, numberOfLines: 2 }}>
                {detailTitle()}
              </Text>
              <Text style={{ fontSize: 14, color: c.secondary }}>
                {detailSources().length} 个源 · {detailEpisodes().length} 集
              </Text>
              {detailLoading() ? (
                <Text style={{ fontSize: 12, color: c.secondary }}>
                  搜索中 {detailSearchDone()}/{detailSearchTotal() || "?"} 源
                </Text>
              ) : null}
              {detailError() ? (
                <Text style={{ fontSize: 12, color: "#C0392B" }}>{detailError()}</Text>
              ) : null}
              {!detailLoading() && detailSources().length === 0 ? (
                <View style={{ flexDirection: "row", gap: 16, paddingTop: 4 }}>
                  <Text onPress={retryDetail} style={{ fontSize: 14, color: c.brand }}>
                    重试
                  </Text>
                  <Text
                    onPress={() => {
                      const movie = detailMovie();
                      if (movie) useDemoDetail(movie, "手动试用演示源");
                    }}
                    style={{ fontSize: 14, color: c.secondary }}
                  >
                    试用演示源
                  </Text>
                </View>
              ) : null}
            </View>
          </View>

          <View style={{ flexDirection: "row", borderBottomWidth: 1, borderBottomColor: c.border }}>
            {([
              { key: "episodes", label: "选集" },
              { key: "sources", label: "换源" },
              { key: "danmaku", label: "弹幕" },
            ] as const).map((tab) => {
              const active = detailTab() === tab.key;
              return (
                <View
                  key={tab.key}
                  onPress={() => detailTab.set(tab.key)}
                  style={{
                    flex: 1,
                    paddingVertical: 10,
                    alignItems: "center",
                    borderBottomWidth: 2,
                    borderBottomColor: active ? c.selected : "transparent",
                  }}
                >
                  <Text style={{ fontSize: 14, fontWeight: active ? "600" : "400", color: active ? c.selected : c.secondary }}>
                    {tab.label}
                  </Text>
                </View>
              );
            })}
          </View>

          {detailTab() === "sources" ? (
            <View style={{ gap: 8 }}>
              {detailSources().length > 0 ? (
                <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
                  {detailSources().map((source) => {
                    const active = source.source === detailSource();
                    return (
                      <View
                        onPress={() => void selectSource(source)}
                        style={{
                          height: 32,
                          paddingHorizontal: 12,
                          justifyContent: "center",
                          borderRadius: 16,
                          backgroundColor: active ? c.selected : "transparent",
                          borderWidth: 1,
                          borderColor: active ? c.selected : c.border,
                        }}
                      >
                        <Text style={{ fontSize: 14, color: active ? "#fff" : c.text }}>
                          {cleanSource(source.sourceName)}
                        </Text>
                      </View>
                    );
                  })}
                </View>
              ) : (
                <Text style={{ fontSize: 13, color: c.secondary }}>还没有可用源</Text>
              )}
            </View>
          ) : null}

          {detailTab() === "episodes" ? (
            <View style={{ gap: 10 }}>
              {detailEpisodesLoading() ? (
                <Text style={{ fontSize: 12, color: c.secondary }}>正在获取集数…</Text>
              ) : null}
              {detailEpisodes().length > 0 ? (
                <View style={{ flexDirection: "row", flexWrap: "wrap", rowGap: 8, columnGap: 8 }}>
                  {detailEpisodes().map((episode, index) => (
                    <View
                      onPress={() => playDetailEpisode(index)}
                      style={{
                        width: "31.5%",
                        height: 44,
                        alignItems: "center",
                        justifyContent: "center",
                        borderRadius: 8,
                        backgroundColor: c.card,
                        borderWidth: 1,
                        borderColor: c.border,
                      }}
                    >
                      <Text style={{ fontSize: 14, color: c.text, numberOfLines: 1 }}>
                        {detailEpisodeTitles()[index]?.replace(/<[^>]+>/g, "") || String(index + 1)}
                      </Text>
                    </View>
                  ))}
                </View>
              ) : null}
              {detailEpisodes().length > 0 ? (
                <View
                  onPress={() => playDetailEpisode(0)}
                  style={{
                    height: 48,
                    borderRadius: 8,
                    backgroundColor: c.brand,
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  <Text style={{ fontSize: 16, fontWeight: "600", color: "#fff" }}>播放第 1 集</Text>
                </View>
              ) : null}
              {detailSources().length > 0 && detailEpisodes().length === 0 && !detailEpisodesLoading() ? (
                <View style={{ flexDirection: "row", gap: 16 }}>
                  <Text
                    onPress={() => {
                      const item =
                        detailSources().find((source) => source.source === detailSource()) ??
                        detailSources()[0];
                      if (item) void selectSource(item);
                    }}
                    style={{ fontSize: 14, color: c.brand }}
                  >
                    重试集数
                  </Text>
                  <Text onPress={() => detailTab.set("sources")} style={{ fontSize: 14, color: c.secondary }}>
                    去换源
                  </Text>
                </View>
              ) : null}
            </View>
          ) : null}

          {detailTab() === "danmaku" ? (
            <View style={{ gap: 10 }}>
              <View style={{ flexDirection: "row", gap: 8 }}>
                <SettingChip label="弹幕开" active={danmakuEnabled()} onPress={() => { danmakuEnabled.set(true); void Storage.setItem("danmakuEnabled", "true"); pushDanmaku(); }} />
                <SettingChip label="弹幕关" active={!danmakuEnabled()} onPress={() => { danmakuEnabled.set(false); void Storage.setItem("danmakuEnabled", "false"); pushDanmaku(); }} />
              </View>
              <Text style={{ fontSize: 12, color: c.secondary }}>
                透明度和字号可在播放页的弹幕面板调整。
              </Text>
            </View>
          ) : null}
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
        height: 32,
        paddingHorizontal: 12,
        justifyContent: "center",
        borderRadius: 16,
        backgroundColor: props.active ? "#27AE60" : "rgba(255,255,255,0.08)",
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

function LiveScreen() {
  effect(() => {
    if (!liveBootStarted) {
      liveBootStarted = true;
      void loadLiveSources();
    }
  });
  return (
    <View style={{ flex: 1, backgroundColor: C().bg }}>
      <WebTopBar title="电视直播" onBack={() => route.set({ name: "home" })} />
      <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
        <View style={{ paddingHorizontal: 16, paddingTop: 16, gap: 12 }}>
          {liveSources().length > 1 ? (
            <Scroll horizontal>
              <View style={{ flexDirection: "row", gap: 8 }}>
                {liveSources().map((source) => {
                  const active = source.key === liveSourceKey();
                  return (
                    <View
                      onPress={() => selectLiveSource(source.key)}
                      style={{
                        paddingHorizontal: 14,
                        paddingVertical: 7,
                        borderRadius: 16,
                        backgroundColor: active ? C().brand : C().card,
                        borderWidth: 1,
                        borderColor: active ? C().brand : C().border,
                      }}
                    >
                      <Text style={{ fontSize: 12, color: active ? "#FFFFFF" : C().secondary }}>{source.name}</Text>
                    </View>
                  );
                })}
              </View>
            </Scroll>
          ) : null}
          {liveGroupNames().length > 1 ? (
            <Scroll horizontal>
              <View style={{ flexDirection: "row", gap: 8 }}>
                {liveGroupNames().map((group) => {
                  const active = group === liveGroup();
                  return (
                    <View
                      onPress={() => selectLiveGroup(group)}
                      style={{
                        paddingHorizontal: 14,
                        paddingVertical: 7,
                        borderRadius: 16,
                        backgroundColor: active ? C().brand : C().card,
                        borderWidth: 1,
                        borderColor: active ? C().brand : C().border,
                      }}
                    >
                      <Text style={{ fontSize: 12, color: active ? "#FFFFFF" : C().secondary }}>{group}</Text>
                    </View>
                  );
                })}
              </View>
            </Scroll>
          ) : null}
          {liveLoading() && liveChannels().length === 0 ? (
            <Text style={{ fontSize: 13, color: C().secondary, paddingVertical: 24, textAlign: "center" }}>正在加载直播频道…</Text>
          ) : null}
          {liveError() ? (
            <View style={{ gap: 10, paddingVertical: 12 }}>
              <Text style={{ fontSize: 13, color: "#C0392B" }}>{liveError()}</Text>
              <View onPress={() => void loadLiveSources()} style={{ flexDirection: "row", alignItems: "center", gap: 6, alignSelf: "flex-start", paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8, backgroundColor: C().card, borderWidth: 1, borderColor: C().border }}>
                <Icon name="rotate" color={C().secondary} size={16} />
                <Text style={{ fontSize: 12, color: C().secondary }}>重试</Text>
              </View>
            </View>
          ) : null}
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
            {liveShownChannels().map((channel) => (
              <View
                key={channel.id}
                onPress={() => playLiveChannel(channel)}
                style={{
                  width: "31.5%",
                  padding: 10,
                  gap: 6,
                  borderRadius: 10,
                  backgroundColor: C().card,
                  borderWidth: 1,
                  borderColor: liveChannel()?.id === channel.id ? C().brand : C().border,
                }}
              >
                <View style={{ height: 44, alignItems: "center", justifyContent: "center" }}>
                  {channel.logo ? (
                    <Image src={liveLogo(channel)} style={{ width: 40, height: 40, borderRadius: 6 }} />
                  ) : (
                    <View style={{ width: 40, height: 40, borderRadius: 6, alignItems: "center", justifyContent: "center", backgroundColor: C().border }}>
                      <Icon name="tv" color={C().secondary} size={22} />
                    </View>
                  )}
                </View>
                <Text style={{ fontSize: 13, fontWeight: "600", color: C().text, numberOfLines: 1 }}>{channel.name}</Text>
                <Text style={{ fontSize: 10, color: liveProgramFor(channel) ? C().secondary : C().placeholder, numberOfLines: 2 }}>
                  {liveProgramFor(channel) || "暂无节目单"}
                </Text>
              </View>
            ))}
          </View>
          {!liveLoading() && liveShownChannels().length === 0 ? (
            <Text style={{ fontSize: 13, color: C().secondary, paddingVertical: 24, textAlign: "center" }}>暂无频道</Text>
          ) : null}
        </View>
      </Scroll>
      <BottomNav active="live" />
    </View>
  );
}

function LivePlayScreen() {
  themeVersion();
  const c = C();
  const channel = liveChannel();
  const program = channel ? liveEpg()[channel.tvgId] : undefined;
  const url = livePlayUrl();
  const back = () => {
    if (fullscreen()) setFullscreenMode(false);
    livePaused.set(true);
    route.set({ name: "live" });
  };
  return (
    <View style={{ flex: 1, backgroundColor: "#08080b" }}>
      {!fullscreen() ? <Header title={channel?.name ?? "电视直播"} onBack={back} /> : null}
      <View
        style={{
          height: fullscreen() ? undefined : 240,
          flex: fullscreen() ? 1 : undefined,
          position: fullscreen() ? "absolute" : "relative",
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
          url={url}
          headers={headersFor(url, url.startsWith(base()))}
          live={true}
          paused={livePaused()}
          rate={1}
          title={channel?.name ?? "电视直播"}
          subtitle={liveSource()?.name ?? ""}
          style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}
          onReady={() => {
            console.log("nshspike: live ready " + (Date.now() - liveOpenedAt) + "ms");
            livePlayState.set("ready");
            playerRef?.command("setMediaInfo", { title: channel?.name ?? "电视直播", subtitle: liveSource()?.name ?? "" });
          }}
          onState={(event) => {
            const state = event.state ?? "";
            console.log("nshspike: live state " + state + " +" + (Date.now() - liveOpenedAt) + "ms");
            livePlayState.set(state);
            if (state === "playing") livePaused.set(false);
            if (state === "paused") livePaused.set(true);
          }}
          onError={(event) => {
            console.log("nshspike: live error " + (event.message ?? ""));
            livePlayError.set(event.message ?? "直播播放失败");
            livePlayState.set("error");
          }}
          onAuthExpired={(event) => {
            void clearSession();
            loginError.set(event.message ?? "播放鉴权失败，请重新登录");
            route.set({ name: "login" });
          }}
        />
        <View style={{ position: "absolute", left: 12, top: 12, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4, backgroundColor: "rgba(229,57,53,0.9)" }}>
          <Text style={{ fontSize: 10, fontWeight: "700", color: "#FFFFFF" }}>直播</Text>
        </View>
        {livePlayState() === "loading" || livePlayState() === "buffering" ? (
          <Text style={{ position: "absolute", right: 12, top: 12, fontSize: 11, color: "#FFFFFF" }}>缓冲中…</Text>
        ) : null}
        <View style={{ position: "absolute", left: 12, right: 12, bottom: 10, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
          <View onPress={() => { if (livePaused()) { livePaused.set(false); playerRef?.command("play"); } else { livePaused.set(true); playerRef?.command("pause"); } }} style={{ width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center", backgroundColor: "rgba(0,0,0,0.45)" }}>
            <Icon name={livePaused() ? "play" : "pause"} color="#FFFFFF" size={20} />
          </View>
          <View style={{ flexDirection: "row", gap: 10 }}>
            <View onPress={retryLive} style={{ width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center", backgroundColor: "rgba(0,0,0,0.45)" }}>
              <Icon name="rotate" color="#FFFFFF" size={18} />
            </View>
            <View onPress={() => setFullscreenMode(!fullscreen())} style={{ width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center", backgroundColor: "rgba(0,0,0,0.45)" }}>
              <Icon name={fullscreen() ? "minimize" : "maximize"} color="#FFFFFF" size={20} />
            </View>
          </View>
        </View>
        {livePlayError() ? (
          <View style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0, alignItems: "center", justifyContent: "center", gap: 10, backgroundColor: "rgba(0,0,0,0.72)" }}>
            <Text style={{ fontSize: 13, color: "#FFFFFF", textAlign: "center", paddingHorizontal: 24 }}>{livePlayError()}</Text>
            <View style={{ flexDirection: "row", gap: 10 }}>
              <View onPress={retryLive} style={{ paddingHorizontal: 16, paddingVertical: 8, borderRadius: 8, backgroundColor: C().brand }}>
                <Text style={{ fontSize: 12, color: "#FFFFFF" }}>重试</Text>
              </View>
              <View onPress={back} style={{ paddingHorizontal: 16, paddingVertical: 8, borderRadius: 8, borderWidth: 1, borderColor: "rgba(255,255,255,0.5)" }}>
                <Text style={{ fontSize: 12, color: "#FFFFFF" }}>换台</Text>
              </View>
            </View>
          </View>
        ) : null}
      </View>
      {!fullscreen() ? (
        <Scroll style={{ flex: 1 }} contentInset={{ bottom: 24 }}>
          <View style={{ padding: 16, gap: 14 }}>
            <View style={{ gap: 4 }}>
              <Text style={{ fontSize: 16, fontWeight: "700", color: "#F2F2F5" }}>{channel?.name ?? "电视直播"}</Text>
              <Text style={{ fontSize: 12, color: "#9AA5B1" }}>{liveSource()?.name ?? ""} · {channel?.group ?? ""}</Text>
              <Text style={{ fontSize: 12, color: "#C7CDD4" }}>正在播出：{program?.now || "暂无节目单"}</Text>
              {program?.next ? <Text style={{ fontSize: 11, color: "#8B95A1" }}>下一档：{program.next}</Text> : null}
            </View>
            <Text style={{ fontSize: 13, fontWeight: "600", color: "#F2F2F5" }}>快速换台</Text>
            <Scroll horizontal>
              <View style={{ flexDirection: "row", gap: 8 }}>
                {liveChannels().map((item) => (
                  <View
                    key={item.id}
                    onPress={() => playLiveChannel(item)}
                    style={{
                      width: 104,
                      padding: 8,
                      gap: 4,
                      borderRadius: 8,
                      backgroundColor: item.id === channel?.id ? "rgba(31,143,79,0.35)" : "#1E1E1E",
                      borderWidth: 1,
                      borderColor: item.id === channel?.id ? "#45C07C" : "#3D4F5D",
                    }}
                  >
                    <Text style={{ fontSize: 12, fontWeight: "600", color: "#FFFFFF", numberOfLines: 1 }}>{item.name}</Text>
                    <Text style={{ fontSize: 10, color: "#9AA5B1", numberOfLines: 1 }}>{liveProgramFor(item) || item.group}</Text>
                  </View>
                ))}
              </View>
            </Scroll>
          </View>
        </Scroll>
      ) : null}
    </View>
  );
}

function exitPlay() {
  saveResume(true);
  paused.set(true);
  const vertical = playVertical();
  playerRef?.command("setOrientation", "auto");
  if (vertical) playerRef?.command("setFullscreen", false);
  if (fullscreen()) setFullscreenMode(false);
  playVertical.set(false);
  route.set(vertical ? { name: "duanju" } : { name: "detail" });
}

function PlayScreen() {
  const shown = () => (seekPreview() >= 0 ? seekPreview() : playPosition());
  const percent = () =>
    playDuration() > 0 ? Math.min(100, Math.max(0, (shown() / playDuration()) * 100)) : 0;
  const immersive = () => fullscreen() || pipActive() || playVertical();

  return (
    <View style={{ flex: 1, backgroundColor: "#08080b" }}>
      {!immersive() ? (
        <Header title={playTitle()} onBack={exitPlay} />
      ) : null}

      <View
        onLayout={(event) => {
          overlayW.set(event.bounds.size.width);
          overlayH.set(event.bounds.size.height);
        }}
        style={{
          height: immersive() ? undefined : 240,
          flex: immersive() ? 1 : undefined,
          position: immersive() ? "absolute" : "relative",
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
          orientation={playVertical() ? "portrait" : "auto"}
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
              <View
                onPress={exitPlay}
                style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center" }}
              >
                <Icon name="arrowLeft" color="#fff" size={22} />
              </View>
              <Text
                style={{ color: "#fff", fontSize: 14, fontWeight: "600", flex: 1, numberOfLines: 1 }}
              >
                {playTitle()}
              </Text>
              <View onPress={toggleOrientation} style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center" }}>
                <Icon name="rotate" color="#fff" size={22} />
              </View>
              <View onPress={() => playerRef?.command("enterPip")} style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center" }}>
                <Icon name="pip" color="#fff" size={22} />
              </View>
              <View onPress={() => setFullscreenMode(!fullscreen())} style={{ width: 36, height: 36, alignItems: "center", justifyContent: "center" }}>
                <Icon name={fullscreen() ? "minimize" : "maximize"} color="#fff" size={22} />
              </View>
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
                <Icon name={paused() ? "play" : "pause"} color="#fff" size={28} />
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
                  <View style={{ height: 3, borderRadius: 2, backgroundColor: "rgba(255,255,255,0.28)" }}>
                    <View
                      style={{
                        width: (percent() + "%") as any,
                        height: 3,
                        borderRadius: 2,
                        backgroundColor: "#27AE60",
                      }}
                    />
                  </View>
                  <View
                    style={{
                      position: "absolute",
                      left: (percent() + "%") as any,
                      marginLeft: -5,
                      width: 12,
                      height: 12,
                      borderRadius: 6,
                      backgroundColor: "#fff",
                    }}
                  />
                </View>
                <Text style={{ color: "#fff", fontSize: 11 }}>{formatTime(playDuration())}</Text>
              </View>

              <View style={{ flexDirection: "row", gap: 16, flexWrap: "wrap", alignItems: "center", paddingTop: 2 }}>
                <Text onPress={() => togglePanel("rate")} style={{ color: "#fff", fontSize: 12 }}>
                  {rate()}×
                </Text>
                <Text onPress={() => togglePanel("quality")} style={{ color: "#fff", fontSize: 12 }}>
                  {qualityLabel()}
                </Text>
                <Text onPress={() => togglePanel("audio")} style={{ color: "#fff", fontSize: 12 }}>
                  {audioLabel()}
                </Text>
                <Text onPress={() => togglePanel("subtitle")} style={{ color: "#fff", fontSize: 12 }}>
                  字幕 {subtitleTracks().length > 0 || externalSubtitle() ? (subtitleEnabled() ? "开" : "关") : "无"}
                </Text>
                <Text onPress={() => togglePanel("danmaku")} style={{ color: "#fff", fontSize: 12 }}>
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
                  backgroundColor: "rgba(20,20,24,0.98)",
                  borderTopWidth: 1,
                  borderTopColor: "rgba(255,255,255,0.12)",
                  borderTopLeftRadius: 12,
                  borderTopRightRadius: 12,
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
  navVersion();
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
  effect(() => {
    return host.on("update.progress", (payload: any) => {
      if (!payload) return;
      if (payload.done) {
        updateBusy.set(false);
        if (payload.error) {
          updateStatus.set("");
          updateError.set(String(payload.error));
        } else {
          updateReceived.set(Number(payload.received || updateTotal()));
          updateStatus.set("校验通过，正在拉起安装器…");
          if (payload.path) void installUpdate(String(payload.path));
        }
      } else {
        updateReceived.set(Number(payload.received || 0));
        updateTotal.set(Number(payload.total || updateTotal()));
      }
    });
  });

  effect(() => {
    return host.on("update.installResult", (payload: any) => {
      if (payload?.error) updateError.set(String(payload.error));
      else updateStatus.set("已拉起系统安装器");
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
      <If when={booted() && route().name === "search"}>
        <SearchScreen />
      </If>
      <If when={booted() && route().name === "browse"}>
        <BrowseScreen />
      </If>
      <If when={booted() && route().name === "duanju"}>
        <DuanjuScreen />
      </If>
      <If when={booted() && route().name === "detail"}>
        <DetailScreen />
      </If>
      <If when={booted() && route().name === "play"}>
        <PlayScreen />
      </If>
      <If when={booted() && route().name === "live"}>
        <LiveScreen />
      </If>
      <If when={booted() && route().name === "live-play"}>
        <LivePlayScreen />
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
      {sheet() ? (
        <View style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0 }}>
          <View
            onPress={() => sheet.set("")}
            style={{ position: "absolute", left: 0, right: 0, top: 0, height: "18%", backgroundColor: "rgba(0,0,0,0.35)" }}
          />
          <View
            style={{
              position: "absolute",
              left: 0,
              right: 0,
              bottom: 0,
              height: "82%",
              backgroundColor: theme() === "light" ? "#FFFFFF" : "#1E1E1E",
              borderTopLeftRadius: 24,
              borderTopRightRadius: 24,
            }}
          >
            {sheet() === "user" ? <UserSheet /> : null}
            {sheet() === "favorites" ? <FavoritesSheet /> : null}
            {sheet() === "history" ? <HistorySheet /> : null}
            {sheet() === "settings" ? <SettingsSheet /> : null}
            {sheet() === "sources" ? <SourcePickerSheet /> : null}
            {sheet() === "direct" ? <DirectSheet /> : null}
          </View>
        </View>
      ) : null}
      <If when={updateVisible()}>
        <UpdateSheet />
      </If>
    </View>
  );
}

mount(App);
