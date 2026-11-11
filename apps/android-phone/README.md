# MoonTVPlus Android（手机版）

极简 WebView 壳工程：把站点装成一个普通 Android App，打开的是手机版首页（不是 `/tv`）。

和 `apps/android-tv` 的区别：

| | TV 版 | 手机版 |
|---|---|---|
| 打开页面 | `BASE_URL/tv` | `BASE_URL/` |
| 屏幕方向 | 锁横屏 | 跟随设备旋转 |
| 服务器地址 | 打包时写死 | App 内可改，并记住 |
| 启动器 | TV 桌面 | 普通桌面（支持长按图标 → 服务器设置） |

## 首次启动

第一次打开会让你填服务器地址（不带 `/tv`），例如：

```text
https://tv.example.com
http://192.168.1.10:3000
```

保存后直接加载。之后想改地址：

- 长按桌面图标 → 「服务器设置」
- 或者长按返回键（三键导航）
- 页面打不开时，错误页上也有「修改服务器地址」

## 构建

GitHub Actions：`Build Android Phone APK`，手动运行，参数：

- `base_url`：可选。填了就作为默认地址（用户仍可在 App 内改）
- `app_name`：桌面显示名称，默认 `MoonTVPlus`
- `version_name` / `version_code`
- `min_sdk`：默认 23（Android 6+）

产物在 Actions 的 artifacts 里。未配置签名 secrets 时构建 debug APK；配置了完整的
`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
则构建签名 release APK（和 TV 版共用同一套 secrets）。

本地构建：

```bash
cd apps/android-phone
gradle assembleDebug -PBASE_URL="https://tv.example.com"
```

## 已处理的移动端细节

- 视频全屏（`onShowCustomView`）：进入沉浸式，退出恢复
- 文件选择（头像、字幕等 `<input type=file>`）
- 非 http(s) 链接交给系统：`nplayer://`、`iina://`、`potplayer://`、`magnet:` 等
- 混合内容、明文 HTTP 允许（自建站常见）
- UA 追加 `MoonTVPlusAndroid`
- 返回键：先退全屏 → 再网页后退 → 双击退出
- 断网/地址错误时有重试页，不会白屏
