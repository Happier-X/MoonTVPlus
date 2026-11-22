# MoonTVPlus 移动端与桌面端（Kotlin Multiplatform）

Kotlin 写的多端客户端，直接调用站点的 HTTP API，不用 WebView 套壳。

工程结构（KMP）：

```
shared/   逻辑层：模型、网络、仓库、缓存、播放器抽象（Android + 桌面共用）
app/      Android 应用外壳：Activity、播放器画面、清单、资源
desktop/  Windows/桌面外壳：窗口与临时界面（Compose Desktop）
```

`shared` 里的 `commonMain` 只放平台无关代码；`jvmSharedMain` 放依赖 OkHttp/Retrofit 的网络层，
Android 与桌面共用同一份。依赖用手写 `AppGraph` 装配（Hilt 只能在 Android 用）。

### 播放器抽象

播放由 `commonMain` 的 `PlaybackController` 定义，各平台用 `actual` 实现：

| 平台 | 实现 | 内嵌播放 |
|---|---|---|
| Android | `shared/androidMain` 的 Media3 / ExoPlayer | 支持 |
| Windows/Linux/macOS | `shared/desktopMain` 的占位实现 | 暂不支持，桌面窗口里直接说明并用浏览器打开网页版 |

播放界面本体在 `:ui` 的 `PlayerScreen`（`commonMain`），只把画面控件留给平台：Android 用
`VideoSurface.android.kt` 把 Media3 的 `PlayerView` 包进 Compose，Android 需要的亮度/音量/画中画/
方向能力由宿主通过 `PlayerPlatform` 注入。手势、弹幕排版、字幕解析、投屏协议、进度与连播逻辑都在
`shared/commonMain` 里用普通 Kotlin 实现，可直接跑单元测试；换播放器只需实现
`PlaybackController` 与 `PlaybackEngine`，不用动播放页逻辑。

能力差异用 `PlayerCapabilities` 声明（桌面端没有内嵌播放，就整体不渲染播放控件），界面不会出现
点不动的假按钮。

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架：Kotlin/Compose/Hilt/KSP、主题、占位页、CI 单元测试 | 完成 |
| M1 | 服务器地址 + 登录（两种模式）+ 会话与自动刷新 | 完成（旧版 Cookie 迁移待做） |
| M2 | 首页（豆瓣 / Bangumi / 短剧 / 即将上映 / 继续观看）+ 缓存 + 图片代理 | 完成（卡片点击待 M3/M4 落地） |
| M3 | 搜索（SSE 流式）+ 结果聚合 + 选源 + 历史 | 完成（选源后进详情待 M4） |
| M4 | 详情 + 选集 + ExoPlayer 播放 + 续播 + 进度上报 + 画中画 | 完成 |
| M4.5 | 手势/控制栏/倍速清晰度音轨/字幕/弹幕（弹弹play）/DLNA 投屏/片头片尾/锁屏/自动连播 | 完成（KMP 共享实现，真机只做了模拟器验证） |
| M5 | 收藏 / 历史 / 设置 / 网页版入口 | 待做 |
| M6 | R8、lint、单测补齐、图标与启动页 | 待做 |

直播、观影室、AI 与磁链搜索**不在第一期**，统一走「网页版」入口（带登录 Cookie 的 WebView 页），后续再逐块原生化。

## 和服务端的约定

- 鉴权用 `Authorization: Bearer <token>`，token 就是 `/api/login` 返回的 `token` 字段原样（本身就是 URL 编码过的 JSON），不要二次编码。
- access token 4 小时过期，401 时自动调 `POST /api/auth/refresh` 换新；`StorageType=localstorage` 的站点不调 refresh。
- UA 固定带 `moontvplus app`，服务端据此把配置过的源 m3u8 改写成 `/api/proxy-m3u8?url=`（去广告代理）。
- `proxyMode=true` 的源走 `/api/proxy/vod/m3u8`，该接口需要登录态，所以播放器用共享 OkHttp 的数据源。

## 构建

GitHub Actions：`Build Android Phone APK`，手动运行，参数：

- `base_url`：可选，作为默认服务器地址（用户仍可在 App 内改）
- `app_name`：桌面显示名称，默认 `MoonTVPlus`
- `version_name` / `version_code`（**覆盖升级要求 version_code 递增**）
- `min_sdk`：默认 23（Android 6+）

产物在 Actions 的 artifacts 里。默认必须配置完整的
`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
才能产出正式签名 release APK（和 TV 版共用同一套 secrets）；只有显式关闭 `require_signed` 才允许 debug。
当前安卓默认版本是 **1.9.13 / versionCode 23**。

## 安卓应用内更新

已登录：设置 → 关于与更新 → 检查更新。未设置服务器或尚未登录时，页面底部同样提供「关于与更新」。
显示的当前版本直接取自 Android BuildConfig，与网站版本、桌面 JAR 版本独立。

- 启动后延迟 4 秒静默检查，最多每 12 小时一次；失败不弹窗、不阻塞启动。发现新版展示版本和发布说明，不自动下载、不强制更新。
- 可忽略当前版本；手动检查不受检查间隔与忽略状态限制。下载可收起、取消与重试，后台或 Activity 重建继续由应用管理；进程被回收后删除未完成文件，重新下载，已完成且仍通过校验的文件可恢复。
- 优先读取 MoonTVPlus 站点 /downloads/latest.json，APK 也由站点提供，国内网络不需要访问 GitHub；站点不可用时再回退到 Happier-X/MoonTVPlus GitHub Releases。
- 下载前并行探测站点与 GitHub 的 64 KiB 分片，按实际吞吐自动选最快线路；选中线路下载中失败会自动切到另一条，不会因为某条线路慢而卡住更新。
- 下载限 HTTPS、官方站点与 GitHub 附件域名，核对发布文件大小（最大 512 MiB）；站点清单、附件 digest 或 SHA256SUMS.txt 提供 SHA-256 时必须匹配。
- 下载完成及每次安装前都检查包名、APK 实际 versionName、严格递增的 versionCode、非调试包、系统版本与签名。API 28+ 允许 Android 已验证的向前签名轮换，旧版要求签名集合完全一致。
- APK 存放在应用私有 `files/updates/`，通过专用 FileProvider 临时只读 `content://` URI 交给系统安装器。API 26+ 未授权时先打开该应用的「安装未知应用」页面，授权返回后继续安装；拒绝或取消可重试。不申请存储、通知或全文件访问权限。

后续正式发布仍需人工通过现有签名 CI 构建，再发布符合上述命名约定的客户端 Release；本功能不会发布 Release。
Debug 测试 APK 的签名与正式应用不同，不能用它验证覆盖正式版；需要使用同一正式签名 CI 包在真机上验收覆盖安装。
桌面端不提供 APK 更新入口。

更新相关自动化：`:app:testDebugUnitTest` 包括发布筛选、网络/哈希/进度、Android 包信息与签名、权限返回和恢复测试。
Compose 原生绘图测试截图输出到 `app/build/reports/update-ui/`（Robolectric 渲染，不能替代真机系统安装验收）。

桌面端（当前为临时外壳，界面在阶段四抽成共享模块）：

```bash
cd apps/android-phone
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :desktop:run
# 打包成可运行的程序目录
./gradlew :desktop:createDistributable
```

应用数据存在 `~/.moontvplus/`（settings.json / session.json / cache/）。

CI 与本地都用仓库内的 Gradle Wrapper（8.10.2），只需要一个 JDK 17：

```bash
cd apps/android-phone
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PBASE_URL="https://tv.example.com"
```

Windows 上如果没有配置 `JAVA_HOME`，可以直接指向 Android Studio 自带的 JDK：

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

## 目录结构

```
app/src/main/java/com/moontvplus/phone/
├── MoonTvApplication.kt      @HiltAndroidApp
├── MainActivity.kt           单 Activity + Compose
├── core/                     通用模型、网络、存储、主题
├── data/                     仓库层、本地缓存、播放器解析
└── feature/                  按页面分包
```
