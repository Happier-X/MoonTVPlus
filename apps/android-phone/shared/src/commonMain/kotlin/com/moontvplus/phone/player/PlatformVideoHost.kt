package com.moontvplus.phone.player

import com.moontvplus.phone.playback.PlaybackController

/**
 * 视频画面的接入点：真正持有平台播放器的对象实现它。
 *
 * 界面层不认具体实现类型 —— 宿主手上可能是 [PlayerSession]，也可能是外面包了一层的
 * ViewModel / 装饰器。1.9.6 就是把这层关系写成了 `controller as? PlayerSession`，
 * 结果 Android 宿主传进来的是 `PlayerViewModel`，转换失败后画面层静默渲染成一块黑底，
 * 音频正常、进度正常、屏幕全黑。
 *
 * 所以画面绑定统一走这个接口，拿不到控制器时必须显式报错，不允许静默变黑。
 */
interface PlatformVideoHost {

    /**
     * 平台播放控制器（Android 上是 `ExoPlaybackController`）。
     *
     * 桌面端或不支持内嵌播放的实现返回 null。
     */
    val platformPlaybackController: PlaybackController?
}
