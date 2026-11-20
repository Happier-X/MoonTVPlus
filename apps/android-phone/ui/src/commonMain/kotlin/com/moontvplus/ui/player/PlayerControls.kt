package com.moontvplus.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.playback.GestureContext
import com.moontvplus.phone.playback.GestureEffect
import com.moontvplus.phone.playback.PlayerGestureMachine
import com.moontvplus.phone.playback.PlaybackStatus
import com.moontvplus.phone.player.PlayerPanel
import com.moontvplus.phone.player.PlayerUiController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val capabilities = controller.capabilities

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var hud by remember { mutableStateOf<PlayerHud?>(null) }
    var sliderPosition by remember { mutableStateOf<Float?>(null) }
    var interacting by remember { mutableStateOf(false) }
    val machine = remember { PlayerGestureMachine() }
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

        // 控制栏
        if (controlsVisible && !isPip && !locked) {
            PlayerControls(
                controller = controller,
                state = state,
                playback = playback,
                isFullscreen = isFullscreen,
                panel = panel,
                sliderPosition = sliderPosition,
                onSliderPosition = { sliderPosition = it; interacting = true },
                onSliderFinished = {
                    sliderPosition?.let { controller.seekTo(it.toLong()) }
                    sliderPosition = null
                    interacting = false
                },
                onBack = onBack,
                onToggleFullscreen = onToggleFullscreen,
                onEnterPip = onEnterPip,
                onToggleLock = { locked = true; controlsVisible = false },
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

/** 控制栏区域吞掉指针事件，避免滑动进度条/横向滚动按钮时误触播放手势。 */
private fun Modifier.consumePlayerTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.all { !it.pressed }) break
        }
    }
}

@Composable
private fun PlayerControls(
    controller: PlayerUiController,
    state: com.moontvplus.phone.player.PlayerScreenState,
    playback: com.moontvplus.phone.playback.PlaybackState,
    isFullscreen: Boolean,
    panel: PlayerPanel,
    sliderPosition: Float?,
    onSliderPosition: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
) {
    val capabilities = controller.capabilities
    val prefs by controller.preferences.collectAsState()
    val duration = playback.durationMs.coerceAtLeast(1L).toFloat()
    val shown = sliderPosition ?: playback.positionMs.coerceAtMost(duration.toLong()).toFloat()

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                ),
            ),
    ) {
        // 顶部
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).consumePlayerTouches(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(Icons.Filled.ArrowBack, "返回", onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildList {
                        add("第 ${state.index} 集")
                        if (state.episodeTitle.isNotBlank()) add(state.episodeTitle)
                        if (state.sourceLabel.isNotBlank()) add(state.sourceLabel)
                    }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (capabilities.pictureInPicture) {
                PlayerIconButton(Icons.Filled.PictureInPictureAlt, "画中画", onEnterPip)
            }
            if (capabilities.screenLock) {
                PlayerIconButton(Icons.Filled.LockOpen, "锁定屏幕", onToggleLock)
            }
            if (capabilities.fullscreenToggle) {
                PlayerIconButton(
                    if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    if (isFullscreen) "退出全屏" else "全屏",
                    onToggleFullscreen,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).consumePlayerTouches()) {
            if (capabilities.inlinePlayback) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerIconButton(
                        if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playback.isPlaying) "暂停" else "播放",
                        { controller.togglePlay() },
                    )
                    Text(
                        formatPlayerTime(shown.toLong()),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = shown,
                        onValueChange = onSliderPosition,
                        onValueChangeFinished = onSliderFinished,
                        valueRange = 0f..duration,
                        enabled = capabilities.seekBar && playback.durationMs > 0L,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text(
                        formatPlayerTime(playback.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(
                // 小屏（320dp）放不下所有入口：横向滚动而不是裁掉按钮
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (capabilities.speedControl) {
                    TextButton(onClick = {
                        val steps = PlayerPreferences.SPEED_STEPS
                        val next = steps.firstOrNull { it > prefs.speed + 0.01f } ?: steps.first()
                        controller.setSpeed(next)
                    }) { Text("%.1fx".format(prefs.speed), color = Color.White) }
                }
                TextButton(onClick = { onOpenPanel(PlayerPanel.EPISODES) }, enabled = state.detail != null) {
                    Text("选集", color = Color.White)
                }
                TextButton(onClick = { onOpenPanel(PlayerPanel.SOURCES) }) { Text("换源", color = Color.White) }
                if (capabilities.trackSelection) {
                    TextButton(onClick = { onOpenPanel(PlayerPanel.TRACKS) }) { Text("清晰度", color = Color.White) }
                }
                if (capabilities.subtitles || capabilities.externalSubtitles) {
                    PlayerIconButton(Icons.Filled.Subtitles, "字幕设置", onClick = { onOpenPanel(PlayerPanel.SUBTITLES) })
                }
                if (capabilities.danmaku) {
                    val enabled by controller.danmaku.collectAsState()
                    TextButton(onClick = { controller.setDanmakuEnabled(!enabled.enabled) }) {
                        Text(if (enabled.enabled) "弹幕开" else "弹幕关", color = Color.White)
                    }
                }
                if (capabilities.casting) PlayerIconButton(Icons.Filled.Cast, "投屏", onClick = { onOpenPanel(PlayerPanel.CAST) })
                if (state.hasNext) {
                    PlayerIconButton(Icons.Filled.SkipNext, "下一集", onClick = { controller.playNext() })
                }
                PlayerIconButton(Icons.Filled.Settings, "播放设置", onClick = { onOpenPanel(PlayerPanel.SETTINGS) })
            }
        }
    }
}
