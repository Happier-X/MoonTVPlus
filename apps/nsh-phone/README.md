# MoonTVPlus · Native Shell 迁移（Phase 1）

这条线用 Native Shell 重做手机端，现有 KMP 版保留在 apps/android-phone 并行，不改动它。
本轮只做一条端到端竖切：登录 -> 首页 -> 详情/选集 -> ExoPlayer 播放 -> 重启恢复登录和续播。
不使用 WebView。

## 目录

    android-host/           极薄的 Android 壳（Kotlin + Gradle）
      app/libs/nsh.aar       NSH 嵌入运行时（从 nsh 发行包取）
      app/src/main/assets/nsh/  nsh build 生成的 TSX bundle
    embed/                  TSX 应用（登录/首页/详情/播放）
    tools/dev-mirror.mjs    开发用站点镜像（可选，见“网络”）
    App.tsx, src/lib.rs     上一轮的独立 spike，保留作参考，不是本轮方案

## 架构

    Android Activity
      └─ com.longbridge.nsh.NshView
           ├─ 加载 assets/nsh/bundle.nshc（embed/App.tsx）
           ├─ registerComponent("moontvplus.player", ExoPlayerComponent)
           └─ handle("http", ...) 用 OkHttp 执行 TSX 的 API 请求

- 所有页面由 TSX 原生组件绘制。
- 播放页使用宿主注册的原生组件，内部是 Media3/ExoPlayer + PlayerView。
- 控制层、手势状态和阈值全部在 TSX；Kotlin 只提供播放能力和事件。
- 字幕、音轨、清晰度留在 ExoPlayer 侧；弹幕、PiP 本轮未做。

## 环境

- nsh 0.6.1
- Android SDK 35、JDK 17+（Android Studio 自带 jbr）
- Gradle 8.10.2（仓库已带 wrapper）
- TSX 构建不依赖 Rust；只有旧的独立 spike 需要 Rust 1.99

把 nsh 发行包里的 nsh.aar 放到：

    apps/nsh-phone/android-host/app/libs/nsh.aar

来源：nsh-embed-android.zip（nsh v0.6.1 的 release 资产），解压后取 nsh.aar。

## 构建与安装

    # 1. 构建 TSX bundle 到壳的 assets
    nsh build apps/nsh-phone/embed/App.tsx --release --out apps/nsh-phone/android-host/app/src/main/assets/nsh

    # 2. 构建 Android 壳
    cd apps/nsh-phone/android-host
    .\gradlew.bat assembleDebug

    # 3. 安装
    adb install -r app/build/outputs/apk/debug/app-debug.apk

也可以直接用 tools/build-host.ps1 一次完成前两步。

## 登录与会话

- 登录调用 /api/login，token 按现有规则原样放进 Authorization: Bearer。
- token 过期时，TSX 先调 /api/auth/refresh，成功后重试原请求。
- 播放链路另有一层：ResolvingDataSource 在每次站点请求前解析 token 时间戳，
  临近过期先刷新再发请求；请求返回 401 时数据源刷新 token 并重试一次。
  刷新失败才向 TSX 发 authExpired 并要求重新登录。
- 会话保存在应用私有目录：

      /data/data/com.moontvplus.nshhost/files/nsh/session/storage.json

  文件权限 600。密码不落盘，只保存服务器、token、用户名、续播位置。
- 冷启动直接读 Storage；token 存在就进首页并加载数据。

## 首页 / 详情 / 播放

- 首页：/api/douban/categories + /api/bangumi/calendar，卡片网格。
- 详情：/api/search/ws（SSE）分源流式找源，先到先渲染；选源后 /api/source-detail 取选集。
- 同一关键词的搜索结果缓存 5 分钟，返回详情页不重新聚合。
- 真实源失败时显示失败态和重试/换源入口；“试用演示源”只在用户手动点击时出现。
- 播放：原生组件 moontvplus.player，ExoPlayer 直接请求站点
  /api/proxy-m3u8 和分片，默认头包含：
  - Authorization: Bearer token
  - User-Agent: MoonTVPlus App/1.9.16 (Android) moontvplus app
- 进度每 500ms 回调，TSX 每 5 秒写入 resume；重启后首页出现“继续观看”，
  点进去会 seek 到保存位置。

## 原生组件接口

moontvplus.player

Props：

- url：HLS/m3u8 或普通媒体地址
- headers：请求头（UA 等；站点 Authorization 由数据源按最新 token 注入）
- paused：暂停
- startPosition：起播位置（秒）
- rate：倍速（0.5 / 0.75 / 1 / 1.25 / 1.5 / 2）

Events：

- ready { duration }
- progress { position, duration, rate }（毫秒）
- state { state: buffering | playing | paused | ended }
- tracks { video, audio, subtitle, subtitleEnabled, externalSubtitle }
- error { message }
- authExpired { message }
- unsupported { command }

Commands：

- seek（秒）、play、pause、stop、setRate、retry、refreshTracks
- selectVideo { group, index }、autoVideo
- selectAudio { group, index }
- selectSubtitle { group, index }、setSubtitleEnabled { enabled }
- loadSubtitle { url }、clearSubtitle、setSubtitleStyle { size, outline }
- setFullscreen（布尔）、setOrientation（landscape | portrait）

## 播放控制与手势

- TSX 覆盖层：播放/暂停、进度条拖动预览、时间、全屏/退出、横竖屏。
- 左半屏上下滑调亮度，右半屏调音量，双击暂停/播放，进度条区域横向拖动。
- 倍速 0.5–2，写入 Storage，下次播放继续使用。
- 清晰度/音轨/字幕面板从 tracks 事件实时生成；切清晰度/音轨不重建播放器，
  当前进度保持。
- 外挂字幕：SRT/VTT/ASS；HLS 的 MediaItem 外挂字幕在部分容器不生效，
  所以外挂字幕由 Kotlin 侧解析并在播放器上叠加 TextView 渲染，
  支持开关、字号和描边。内嵌字幕仍走 ExoPlayer。
- 播放错误自动重试 3 次并保持位置；失败后显示“重试”。
- 进度每 500ms 回调，TSX 每 5s 写入 resume；换集/换源/切清晰度后仍保存。

## 网络注意

本机模拟器访问站点 HTTPS（Cloudflare）会长时间挂住；HTTP 正常。
Phase 1 默认服务器写成 http://tv.370125.xyz，壳开了 usesCleartextTraffic。
这是模拟器环境限制，不代表最终方案；正式环境应使用 HTTPS。
必要时可以启动开发镜像：

    node apps/nsh-phone/tools/dev-mirror.mjs

然后把服务器改成 http://10.0.2.2:9000。

## PiP、后台与锁屏

- 播放页顶部有 PiP 按钮；Android 12+ 播放中按 Home 会尝试自动进入 PiP。
- PiP 内只保留视频，TSX 控制层隐藏；退出回原页面，播放器不重建、进度不丢。
- 播放时启动 PlaybackService 前台服务，通知栏有播放/暂停；MediaSession 供锁屏控制。
- Android 14 需要 supportsPictureInPicture、resizeableActivity、configChanges
  和 setAutoEnterEnabled，这些已在 manifest/Activity 配好。
- 模拟器没有音频输出，锁屏“音频是否继续”只能从服务和 MediaSession 状态判断。

## 弹幕

- 加载链路：/api/danmaku/match -> /api/danmaku/episodes -> /api/danmaku/comment；
  失败时用 /api/danmaku/search 兜底。
- 弹幕按集缓存；播放页“弹幕”面板支持开关、透明度、字号、全部/滚动/顶部/底部。
- 弹幕层由 Kotlin DanmakuView 用 Canvas 绘制，不接收触摸，控制层不受影响。
- 当前站点弹幕上游返回 503，真实弹幕不可用；dev-mirror.mjs 支持
  MOCK_DANMAKU=1 返回测试弹幕，只用于验证加载和面板。

## 收藏、历史、设置

- 收藏：/api/favorites 列表/新增/取消；详情页可选源后收藏。
- 历史：/api/playrecords 列表/删除/清空；播放每 5 秒上报一次。
- 首页“继续观看”优先使用 /api/playrecords 的最新记录，点击后按 source+id
  重新取集并续播；没有记录时回退本地 resume。
- 设置：服务器地址、退出登录、倍速记忆、默认清晰度、自动连播、弹幕开关、
  关于与版本、检查更新（只显示，不自动下载）。

## Release 构建

    cd apps/nsh-phone/android-host
    .\gradlew.bat assembleRelease

- R8 + 资源压缩开启，release 使用 debug 签名，proguard-rules.pro 保留
  com.longbridge.nsh.**、Media3 和宿主组件。
- 已开 ABI 分包：arm64-v8a、armeabi-v7a、x86_64。
- 实测：debug 204.7MB；release arm64/x86_64 约 74.9MB，armeabi-v7a 55.1MB。
- 模拟器 x86_64 release 冷启动 am start -W TotalTime 999ms。

## CI 构建与发布

- 工作流：.github/workflows/android-nsh.yml，手动触发，参数 version_name、version_code、base_url。
- 版本号单点来源：apps/nsh-phone/embed/nsh.json 的 version/build；Gradle 直接读取，
  不再手抄到 build.gradle.kts。
- CI 会下载 nsh v0.6.1 的 nsh.aar、构建 TSX、运行 Gradle assembleRelease，
  用 ANDROID_KEYSTORE_* secrets 签名，并用 apksigner/aapt 校验包名和版本后上传 artifact。
- 发布产物：app-arm64-v8a-release.apk、app-armeabi-v7a-release.apk、SHA256SUMS。
- 本版 tag：client-nsh-1.9.17；GitHub pre-release，不影响站点 /downloads/latest.json。

### 回退 1.9.16

1.9.17 的 versionCode 是 27，直接装 1.9.16（26）会被系统拒绝。回退需要：
- 卸载当前应用后安装 client-v1.9.16 的 APK（会清空应用数据）；或
- 用 adb install -d client-v1.9.16.apk 允许降级。
- 1.9.16 APK：
  https://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.16/moontvplus-phone-1.9.16-release-signed.apk
  （SHA-256 fbbcd62ef5a898b9b24cae7cb448fdb212ecbdba2d6a12e5163fb81ce9c8eb62）。

## 已知限制

- /api/search/ws 在模拟器上首条约 2.4-3.2s、全部约 7-8.8s；同关键词二次打开走缓存。
- 真实源返回率随片名变化（实测 9/28、19/28 个源有结果），源本身失败会单独提示。
- ExoPlayer 播放链路本身已验证：站点代理 + Bearer + UA，真实 TS 分片可播。
- 站点弹幕上游当前 503，真实弹幕未验证；代码和 MOCK 测试链路已通。
- PiP 自动进入依赖播放中触发；本次模拟器网络反复缓冲，只在手动 PiP 上做了进入/退出验证。
- 长按 2× 在 NSH 0.6.1 Android 后端的 onLongPress 不触发，改用倍速面板；
  其余手势（亮度/音量/双击/进度拖动）可用。本轮不自己在壳里算长按：
  和 TSX 手势一起会重复消费触摸，容易和双击误触发，等 NSH 上游修复再补。
- 外挂字幕按 SRT/VTT/ASS 基础解析，复杂 ASS 样式（定位、特效）不支持。
- 清晰度/音轨切换依赖源暴露的 track；单档源会显示“只有一档”。
- 源站代理对 fMP4 的 EXT-X-MAP、byte-range 和相对 EXT-X-MEDIA URI 处理不完整，
  这类源建议直连或换源；开发镜像对 EXT-X-MEDIA 做了测试用重写。
- Debug APK 约 214 MB（nsh.aar 带三套 ABI + Media3），release 需要裁剪/混淆。
- 图片直接走豆瓣 URL，没有做鉴权代理；个别图可能 403。

## 版本

android-host/app/build.gradle.kts 的 versionCode/versionName 是 APK 版本。
Gradle 的 debug 构建使用 Android debug key。

