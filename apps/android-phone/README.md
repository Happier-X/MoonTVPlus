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
| Windows/Linux/macOS | `shared/desktopMain` 的占位实现 | 暂不支持，界面走「外部播放器」 |

界面层只剩 Android 的 `PlayerView`（画面控件）需要 Media3 类型；换播放器只需实现
`PlaybackController` 与 `PlaybackEngine`，不用动播放页逻辑。

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架：Kotlin/Compose/Hilt/KSP、主题、占位页、CI 单元测试 | 完成 |
| M1 | 服务器地址 + 登录（两种模式）+ 会话与自动刷新 | 完成（旧版 Cookie 迁移待做） |
| M2 | 首页（豆瓣 / Bangumi / 短剧 / 即将上映 / 继续观看）+ 缓存 + 图片代理 | 完成（卡片点击待 M3/M4 落地） |
| M3 | 搜索（SSE 流式）+ 结果聚合 + 选源 + 历史 | 完成（选源后进详情待 M4） |
| M4 | 详情 + 选集 + ExoPlayer 播放 + 续播 + 进度上报 + 画中画 | 完成（跳片头尾待做） |
| M5 | 收藏 / 历史 / 设置 / 网页版入口 | 待做 |
| M6 | R8、lint、单测补齐、图标与启动页 | 待做 |

弹幕、直播、漫画、观影室、AI、网盘与磁链搜索**不在第一期**，统一走「网页版」入口（带登录 Cookie 的 WebView 页），后续再逐块原生化。

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

产物在 Actions 的 artifacts 里。未配置签名 secrets 时构建 debug APK；配置了完整的
`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
则构建签名 release APK（和 TV 版共用同一套 secrets）。

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
