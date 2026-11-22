package com.moontvplus.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.playback.PlayerCapabilities
import com.moontvplus.phone.playback.GestureContext
import com.moontvplus.phone.playback.GestureEffect
import com.moontvplus.phone.playback.PlayerGestureMachine
import com.moontvplus.phone.playback.PlaybackStatus
import com.moontvplus.phone.player.PlayerPanel
import com.moontvplus.phone.player.PlayerUiController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 播放器主界面：Android / 桌面共用。 */
@Composable
fun PlayerScreen(
    controller: PlayerUiController,
    isFullscreen: Boolean,
    isPip: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onPickSubtitleFile: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val playback by controller.playback.collectAsState()
    val preferences by controller.preferences.collectAsState()
    val danmaku by controller.danmaku.collectAsState()
    val capabilities = controller.capabilities

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var hud by remember { mutableStateOf<PlayerHud?>(null) }
    var sliderPosition by remember { mutableStateOf<Float?>(null) }
    var interacting by remember { mutableStateOf(false) }
    val machine = remember { PlayerGestureMachine() }
    // 顶部标题栏 / 底部控制条的像素高度：手势层在这两块区域不抢指针，
    // 否则「拖一下进度条附近」会被当成亮度/音量手势（反过来把子按钮切成手势就更糟）。
    val density = LocalDensity.current
    val topBarPx = with(density) { PlayerBarZones.TopHeight.toPx() }
    val bottomBarPx = with(density) { PlayerBarZones.BottomHeight.toPx() }
    // 面板打开或锁屏时，返回键先关面板/解锁，再交给宿主退出
    PlayerBackHandler(enabled = panel != PlayerPanel.NONE || locked) {
        if (panel != PlayerPanel.NONE) panel = PlayerPanel.NONE else locked = false
    }
    val scope = rememberCoroutineScope()

    val currentLocked by rememberUpdatedState(locked)
    val currentPlaying by rememberUpdatedState(playback.isPlaying)
    val currentPosition by rememberUpdatedState(playback.positionMs)
    val currentDuration by rememberUpdatedState(playback.durationMs)
    val machineRef = rememberUpdatedState(machine)
    val platformRef = rememberUpdatedState(controller.platform)

    // 暂停态（网站版 ArtPlayer 行为）：中央大播放按钮 + 常驻控制栏。
    // 加载/报错/锁屏/画中画都不算「暂停态」。
    val showPausedChrome = !isPip && capabilities.inlinePlayback && !locked &&
        !playback.isPlaying && !state.loading && state.error == null &&
        playback.status != PlaybackStatus.BUFFERING

    /**
     * 播放/暂停。播完（ENDED）后再点播放要像网页 `<video>` 一样从头重播：
     * ExoPlayer 在 ENDED 上调 play() 不会有任何反应。
     */
    fun togglePlayWithRestart() {
        if (playback.isPlaying) {
            controller.pause()
        } else {
            if (playback.status == PlaybackStatus.ENDED) controller.seekTo(0L)
            controller.play()
        }
    }

    fun applyEffects(effects: List<GestureEffect>) {
        effects.forEach { effect ->
            when (effect) {
                GestureEffect.ToggleControls -> controlsVisible = !controlsVisible
                GestureEffect.TogglePlay -> controller.togglePlay()
                is GestureEffect.SeekRelative -> {
                    controller.seekBy(effect.deltaMs)
                    hud = PlayerHud.Seek((currentPosition + effect.deltaMs).coerceAtLeast(0L), effect.deltaMs)
                }
                is GestureEffect.SeekPreview -> hud = PlayerHud.Seek(effect.targetMs, effect.deltaMs)
                is GestureEffect.SeekCommit -> {
                    controller.seekTo(effect.targetMs)
                    hud = null
                }
                is GestureEffect.Brightness -> hud = PlayerHud.Brightness(effect.value)
                is GestureEffect.Volume -> hud = PlayerHud.Volume(effect.value)
                GestureEffect.SpeedBoostStart -> {
                    controller.beginSpeedBoost()
                    hud = PlayerHud.Speed
                }
                GestureEffect.SpeedBoostEnd -> {
                    controller.endSpeedBoost()
                    hud = null
                }
                GestureEffect.LockedTap -> hud = PlayerHud.Locked
            }
        }
    }

    if (!isPip) {
        // 播放中 3.5 秒后自动隐藏控制栏；一旦暂停就立刻恢复显示（对齐网站版）
        LaunchedEffect(showPausedChrome) {
            if (showPausedChrome) controlsVisible = true
        }
        LaunchedEffect(controlsVisible, panel, currentPlaying, locked, interacting) {
            if (controlsVisible && panel == PlayerPanel.NONE && currentPlaying && !locked && !interacting) {
                delay(3_500)
                controlsVisible = false
            }
        }
        LaunchedEffect(hud) {
            if (hud != null) {
                delay(1_400)
                hud = null
            }
        }
        LaunchedEffect(state.toast) {
            if (state.toast != null) {
                delay(2_200)
                controller.clearToast()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(controller, Modifier.fillMaxSize())

        if (capabilities.danmaku) DanmakuOverlay(controller)
        ExternalSubtitleOverlay(controller)

        // 手势层：位于画面之上、控制栏之下，不拦截按钮点击
        if (!isPip && capabilities.inlinePlayback) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 指针作用域不支持 launch：长按/单击延时用组合里的 scope
                        var tapCommit: Job? = null
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            tapCommit?.cancel()
                            // 控制栏区域：不处理手势（也不消费事件），只让按钮/滑块自己响应
                            if (down.position.y <= topBarPx || down.position.y >= size.height - bottomBarPx) {
                                return@awaitEachGesture
                            }
                            val context = GestureContext(
                                locked = currentLocked,
                                playing = currentPlaying,
                                positionMs = currentPosition,
                                durationMs = currentDuration,
                                brightness = platformRef.value?.brightness ?: 0.5f,
                                volume = platformRef.value?.systemVolume ?: 0.5f,
                                seekable = currentDuration > 0L && capabilities.seekBar,
                            )
                            applyEffects(
                                machineRef.value.down(
                                    down.position.x, down.position.y,
                                    size.width.toFloat(), size.height.toFloat(),
                                    System.currentTimeMillis(), context,
                                ),
                            )
                            val longPress = scope.launch {
                                delay(500)
                                applyEffects(machineRef.value.onLongPressElapsed(System.currentTimeMillis()))
                            }
                            var pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (change.pressed) {
                                    applyEffects(machineRef.value.move(change.position.x, change.position.y))
                                    change.consume()
                                } else {
                                    applyEffects(machineRef.value.up(System.currentTimeMillis()))
                                    break
                                }
                            }
                            longPress.cancel()
                            if (machineRef.value.hasPendingTap) {
                                tapCommit = scope.launch {
                                    delay(280)
                                    applyEffects(machineRef.value.commitPendingTap())
                                }
                            }
                        }
                    },
            )
        }

        // 跳过片头/片尾、自动下一集
        if (!isPip) {
            SkipOverlays(controller, state, onSkipHandled = { controlsVisible = true })
        }

        // 暂停态中央大播放按钮：对齐网站版 ArtPlayer 的 `.art-state`
        // （约 80dp、opacity .8、scale 2→1）。加载/报错/锁屏/画中画时不出现。
        if (showPausedChrome) {
            PlayerCenterPlayButton(
                onClick = { togglePlayWithRestart() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 控制栏
        if (controlsVisible && !isPip && !locked) {
            PlayerControls(
                displayTitle = state.displayTitle,
                index = state.index,
                episodeTitle = state.episodeTitle,
                sourceLabel = state.sourceLabel,
                isPlaying = playback.isPlaying,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                sliderPosition = sliderPosition,
                speed = preferences.speed,
                danmakuEnabled = danmaku.enabled,
                hasEpisodes = state.detail != null,
                hasNext = state.hasNext,
                capabilities = capabilities,
                isFullscreen = isFullscreen,
                onBack = onBack,
                onToggleFullscreen = onToggleFullscreen,
                onEnterPip = onEnterPip,
                onToggleLock = { locked = true; controlsVisible = false },
                onTogglePlay = { togglePlayWithRestart() },
                onSliderPosition = { sliderPosition = it; interacting = true },
                onSliderFinished = {
                    sliderPosition?.let { controller.seekTo(it.toLong()) }
                    sliderPosition = null
                    interacting = false
                },
                onCycleSpeed = {
                    val steps = PlayerPreferences.SPEED_STEPS
                    val next = steps.firstOrNull { it > preferences.speed + 0.01f } ?: steps.first()
                    controller.setSpeed(next)
                },
                onToggleDanmaku = { controller.setDanmakuEnabled(!danmaku.enabled) },
                onPlayNext = { controller.playNext() },
                onOpenPanel = { panel = it; controlsVisible = true },
            )
        }

        if (locked && !isPip) {
            androidx.compose.material3.FloatingActionButton(
                onClick = { locked = false; controlsVisible = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                containerColor = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "解锁播放器")
            }
        }

        // HUD：亮度 / 音量 / 拖动进度 / 长按倍速
        hud?.let { PlayerHudView(it) }

        if (!isPip && (state.loading || playback.status == PlaybackStatus.BUFFERING) && state.error == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Color.White)
                if (state.loading) {
                    Text("正在尝试播放第 ${state.index} 集", color = Color.White)
                } else if (state.weakNetwork) {
                    Text(state.bufferingHint ?: "网络较慢，正在缓冲…", color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }

        if (!isPip) state.error?.let { error ->
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(error, color = Color.White, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.retry() }) { Text("重试") }
                    OutlinedButton(onClick = { panel = PlayerPanel.SOURCES; controlsVisible = true }) { Text("手动换源") }
                }
            }
        }

        if (!isPip && panel != PlayerPanel.NONE) {
            PlayerPanelContent(
                panel = panel,
                controller = controller,
                onClose = { panel = PlayerPanel.NONE },
                onPickSubtitleFile = onPickSubtitleFile,
                onOpenPanel = { panel = it },
            )
        }
    }
}

@Composable
private fun BoxScope.SkipOverlays(
    controller: PlayerUiController,
    state: com.moontvplus.phone.player.PlayerScreenState,
    onSkipHandled: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.showSkipIntroButton) {
            TextButton(
                onClick = { controller.skipIntro(); onSkipHandled() },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                ),
            ) { Text("跳过片头") }
        }
        if (state.showSkipOutroButton) {
            TextButton(
                onClick = { controller.skipOutro(); onSkipHandled() },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                ),
            ) { Text("跳过片尾 · 下一集") }
        }
    }
    val countdown = state.autoNext
    if (countdown.visible) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
                .background(Color.Black.copy(alpha = 0.72f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${countdown.remainingSeconds} 秒后播放下一集", color = Color.White)
            LinearProgressIndicator(
                progress = { countdown.progress },
                modifier = Modifier.size(width = 60.dp, height = 3.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
            TextButton(onClick = { controller.cancelAutoNext() }) { Text("取消", color = Color.White) }
        }
    }
}

private sealed interface PlayerHud {
    data class Brightness(val value: Float) : PlayerHud
    data class Volume(val value: Float) : PlayerHud
    data class Seek(val targetMs: Long, val deltaMs: Long) : PlayerHud
    data object Speed : PlayerHud
    data object Locked : PlayerHud
}

@Composable
private fun BoxScope.PlayerHudView(hud: PlayerHud) {
    val (label, value) = when (hud) {
        is PlayerHud.Brightness -> "亮度" to hud.value
        is PlayerHud.Volume -> "音量" to hud.value
        is PlayerHud.Seek -> "${formatPlayerTime(hud.targetMs)}（${if (hud.deltaMs >= 0) "+" else ""}${hud.deltaMs / 1000} 秒）" to null
        PlayerHud.Speed -> "2× 倍速" to null
        PlayerHud.Locked -> "已锁定，点击右上角解锁" to null
    }
    Column(
        Modifier
            .align(Alignment.Center)
            .background(Color.Black.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (value != null) {
            LinearProgressIndicator(
                progress = { value },
                modifier = Modifier.height(3.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
        }
    }
}


/** 1.25 必须显示为 1.25x，不能四舍五入成 1.3x；整数去掉小数位。 */
internal fun formatPlayerSpeed(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0 -> "${whole}x"
        fraction % 10 == 0 -> "$whole.${fraction / 10}x"
        else -> "$whole.${fraction.toString().padStart(2, '0')}x"
    }
}

/** 底部控制条上的文字按钮：比 Material 默认内边距更紧，320dp 屏也能多放两个入口。 */
@Composable
private fun PlayerBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 不用 TextButton：Material3 的 defaultMinSize(58dp) 会在 320dp 屏上把「换源」挤断
    Box(
        modifier = modifier
            .height(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * 顶部标题栏 / 底部控制条占用的高度。
 *
 * 手势层（亮度、音量、拖动进度）在这两块区域里不处理指针：控制栏自己的按钮和滑块
 * 需要收到原始事件。1.9.7 曾在控制栏父级上整体消费指针，结果所有按钮「看得见点不动」。
 */
internal object PlayerBarZones {
    val TopHeight = 72.dp
    val BottomHeight = 96.dp
}

/**
 * 暂停态的中央播放按钮，对齐网站版 ArtPlayer 的 `.art-state`：
 * 约 80dp 的圆形、opacity 0.8、出现时从 scale 2 收拢到 1。
 */
@Composable
fun PlayerCenterPlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 2f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "player-center-play-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 0.8f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "player-center-play-alpha",
    )
    Box(
        modifier = modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(PlayerControlTags.CENTER_PLAY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放视频",
            tint = Color.White,
            modifier = Modifier.size(52.dp),
        )
    }
}

/** 控制栏的测试锚点：Robolectric 点击测试按这些 tag 找控件。 */
object PlayerControlTags {
    const val TOP_BAR = "player-top-bar"
    const val BOTTOM_BAR = "player-bottom-bar"
    const val BACK = "player-control-back"
    const val PIP = "player-control-pip"
    const val LOCK = "player-control-lock"
    const val PLAY_PAUSE = "player-control-play"
    const val SPEED = "player-control-speed"
    const val EPISODES = "player-control-episodes"
    const val SOURCES = "player-control-sources"
    const val TRACKS = "player-control-tracks"
    const val SUBTITLES = "player-control-subtitles"
    const val DANMAKU = "player-control-danmaku"
    const val CAST = "player-control-cast"
    const val NEXT = "player-control-next"
    const val SETTINGS = "player-control-settings"
    const val FULLSCREEN = "player-control-fullscreen"
    const val CENTER_PLAY = "player-center-play"
}

/**
 * 播放器控制栏（顶部标题 + 进度条 + 单行控制条）。
 *
 * 刻意只接收数据与回调、不依赖控制器：这样 Robolectric 的点击测试能直接渲染它，
 * 一旦有人在父级重新加回「整体消费指针」，测试会立刻失败。
 */
@Composable
fun PlayerControls(
    displayTitle: String,
    index: Int,
    episodeTitle: String,
    sourceLabel: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    sliderPosition: Float?,
    speed: Float,
    danmakuEnabled: Boolean,
    hasEpisodes: Boolean,
    hasNext: Boolean,
    capabilities: PlayerCapabilities,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleLock: () -> Unit,
    onTogglePlay: () -> Unit,
    onSliderPosition: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onPlayNext: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
) {
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    val shown = sliderPosition ?: positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()

    // 注意：这个 Column 只是布局/渐变，不能加 pointerInput 去消费整块事件 —— 那会吞掉
    // 下面每个按钮的点击（1.9.7 的回归）。空白处的点击应该漏给手势层去切换控制栏。
    Column(Modifier.fillMaxSize()) {
        // 顶部：半透明渐变 + 轻量图标，样式对齐网站版
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.22f), Color.Transparent),
                    ),
                )
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp)
                .testTag(PlayerControlTags.TOP_BAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                Icons.Filled.ArrowBack,
                "返回",
                onBack,
                modifier = Modifier.testTag(PlayerControlTags.BACK),
            )
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    displayTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildList {
                        add("第 $index 集")
                        if (episodeTitle.isNotBlank()) add(episodeTitle)
                        if (sourceLabel.isNotBlank()) add(sourceLabel)
                    }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (capabilities.pictureInPicture) {
                PlayerIconButton(
                    Icons.Filled.PictureInPictureAlt,
                    "画中画",
                    onEnterPip,
                    modifier = Modifier.testTag(PlayerControlTags.PIP),
                )
            }
            if (capabilities.screenLock) {
                PlayerIconButton(
                    Icons.Filled.LockOpen,
                    "锁定屏幕",
                    onToggleLock,
                    modifier = Modifier.testTag(PlayerControlTags.LOCK),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部：渐变 + 进度条 + 单行控制条
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f)),
                    ),
                )
                .padding(start = 10.dp, end = 10.dp, bottom = 2.dp)
                .testTag(PlayerControlTags.BOTTOM_BAR),
        ) {
            if (capabilities.inlinePlayback) {
                // 进度条一行：当前时间 ── 滑块 ── 总时长（网站版的 progress 层）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatPlayerTime(shown.toLong()),
                        color = Color.White,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = shown,
                        onValueChange = onSliderPosition,
                        onValueChangeFinished = onSliderFinished,
                        valueRange = 0f..duration,
                        enabled = capabilities.seekBar && durationMs > 0L,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp).height(22.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text(
                        formatPlayerTime(durationMs),
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PlayerIconButton(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    onTogglePlay,
                    modifier = Modifier.testTag(PlayerControlTags.PLAY_PAUSE),
                )
                // 小屏（320dp）放不下所有入口：中间这一段横向滚动，播放键/设置/全屏固定可见
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (capabilities.speedControl) {
                        PlayerBarTextButton(
                            text = formatPlayerSpeed(speed),
                            onClick = onCycleSpeed,
                            modifier = Modifier.testTag(PlayerControlTags.SPEED),
                        )
                    }
                    PlayerBarTextButton(
                        text = "选集",
                        onClick = { onOpenPanel(PlayerPanel.EPISODES) },
                        enabled = hasEpisodes,
                        modifier = Modifier.testTag(PlayerControlTags.EPISODES),
                    )
                    PlayerBarTextButton(
                        text = "换源",
                        onClick = { onOpenPanel(PlayerPanel.SOURCES) },
                        modifier = Modifier.testTag(PlayerControlTags.SOURCES),
                    )
                    if (capabilities.trackSelection) {
                        PlayerBarTextButton(
                            text = "清晰度",
                            onClick = { onOpenPanel(PlayerPanel.TRACKS) },
                            modifier = Modifier.testTag(PlayerControlTags.TRACKS),
                        )
                    }
                    if (capabilities.subtitles || capabilities.externalSubtitles) {
                        PlayerIconButton(
                            Icons.Filled.Subtitles,
                            "字幕设置",
                            onClick = { onOpenPanel(PlayerPanel.SUBTITLES) },
                            modifier = Modifier.testTag(PlayerControlTags.SUBTITLES),
                        )
                    }
                    if (capabilities.danmaku) {
                        PlayerBarTextButton(
                            text = if (danmakuEnabled) "弹幕开" else "弹幕关",
                            onClick = onToggleDanmaku,
                            modifier = Modifier.testTag(PlayerControlTags.DANMAKU),
                        )
                    }
                    if (capabilities.casting) {
                        PlayerIconButton(
                            Icons.Filled.Cast,
                            "投屏",
                            onClick = { onOpenPanel(PlayerPanel.CAST) },
                            modifier = Modifier.testTag(PlayerControlTags.CAST),
                        )
                    }
                    if (hasNext) {
                        PlayerIconButton(
                            Icons.Filled.SkipNext,
                            "下一集",
                            onClick = onPlayNext,
                            modifier = Modifier.testTag(PlayerControlTags.NEXT),
                        )
                    }
                }
                PlayerIconButton(
                    Icons.Filled.Settings,
                    "播放设置",
                    onClick = { onOpenPanel(PlayerPanel.SETTINGS) },
                    modifier = Modifier.testTag(PlayerControlTags.SETTINGS),
                )
                if (capabilities.fullscreenToggle) {
                    PlayerIconButton(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        if (isFullscreen) "退出全屏" else "全屏",
                        onToggleFullscreen,
                        modifier = Modifier.testTag(PlayerControlTags.FULLSCREEN),
                    )
                }
            }
        }
    }
}
