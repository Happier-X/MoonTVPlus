package com.moontvplus.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.player.PlayerPanel
import com.moontvplus.phone.player.PlayerUiController
import com.moontvplus.phone.playback.TrackType

@Composable
fun PlayerPanelContent(
    panel: PlayerPanel,
    controller: PlayerUiController,
    onClose: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
) {
    val title = when (panel) {
        PlayerPanel.EPISODES -> "选集"
        PlayerPanel.SOURCES -> "换源"
        PlayerPanel.SPEED -> "播放倍速"
        PlayerPanel.TRACKS -> "清晰度与音轨"
        PlayerPanel.SUBTITLES -> "字幕"
        PlayerPanel.DANMAKU -> "弹幕设置"
        PlayerPanel.CAST -> "投屏"
        PlayerPanel.SETTINGS -> "播放设置"
        PlayerPanel.NONE -> return
    }
    PlayerPanelScaffold(title = title, onClose = onClose) {
        when (panel) {
            PlayerPanel.EPISODES -> EpisodesPanel(controller)
            PlayerPanel.SOURCES -> SourcesPanel(controller)
            PlayerPanel.SPEED -> SpeedPanel(controller)
            PlayerPanel.TRACKS -> TracksPanel(controller)
            PlayerPanel.SUBTITLES -> SubtitlesPanel(controller, onPickSubtitleFile)
            PlayerPanel.DANMAKU -> DanmakuPanel(controller)
            PlayerPanel.CAST -> CastPanel(controller)
            PlayerPanel.SETTINGS -> SettingsPanel(controller, onOpenPanel)
            PlayerPanel.NONE -> Unit
        }
    }
}

@Composable
private fun EpisodesPanel(controller: PlayerUiController) {
    val state by controller.state.collectAsState()
    if (state.episodeOptions.isEmpty()) {
        Text("当前片源没有可选择的剧集", color = Color.White, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.heightIn(max = 430.dp).fillMaxWidth()) {
        items(state.episodeOptions.chunked(5)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                row.forEach { episode ->
                    Button(
                        onClick = { controller.goTo(episode.index); },
                        enabled = !episode.isCurrent,
                        modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                    ) {
                        Text(
                            if (episode.isCurrent) "·${episode.index}·" else episode.index.toString(),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesPanel(controller: PlayerUiController) {
    val state by controller.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextButton(onClick = { controller.findSources() }, enabled = !state.findingSources) {
            Text(if (state.findingSources) "查找中…" else "重新查找片源")
        }
    }
    when {
        state.sourceError != null -> Text(state.sourceError.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
        state.sourceOptions.isEmpty() -> Text("没有找到其他片源", color = Color.White, modifier = Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.heightIn(max = 350.dp)) {
            items(state.sourceOptions, key = { it.key }) { source ->
                TextButton(
                    onClick = { controller.chooseSource(source.key) },
                    enabled = !source.isCurrent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        source.label + if (source.isCurrent) "（当前）" else "",
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedPanel(controller: PlayerUiController) {
    val preferences by controller.preferences.collectAsState()
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(PlayerPreferences.SPEED_STEPS) { speed ->
            TextButton(
                onClick = { controller.setSpeed(speed) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "${speed}x${if (speed == preferences.speed) "（当前）" else ""}",
                    color = if (speed == preferences.speed) Color.White else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TracksPanel(controller: PlayerUiController) {
    val playback by controller.playback.collectAsState()
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        item { PlayerSmallLabel("清晰度（视频轨）") }
        items(playback.videoTracks, key = { it.id }) { track ->
            TextButton(
                onClick = { controller.selectTrack(TrackType.VIDEO, track.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 自适应码率下 Media3 可能同时标记多条视频/音轨为选中：只标第一条为当前
                Text(
                    track.displayLabel + when {
                        track.id == playback.selectedVideoTrackId -> "（当前）"
                        track.isSelected -> "（自适应）"
                        else -> ""
                    },
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (playback.videoTracks.isEmpty()) {
            item { Text("当前片源只有一种清晰度", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        item { PlayerSmallLabel("音轨") }
        items(playback.audioTracks, key = { it.id }) { track ->
            TextButton(
                onClick = { controller.selectTrack(TrackType.AUDIO, track.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    track.displayLabel + when {
                        track.id == playback.selectedAudioTrackId -> "（当前）"
                        track.isSelected -> "（自适应）"
                        else -> ""
                    },
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (playback.audioTracks.isEmpty()) {
            item { Text("当前片源只有一条音轨（内嵌在视频里）", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        item { PlayerSmallLabel("内嵌字幕轨") }
        item {
            TextButton(onClick = { controller.selectTrack(TrackType.TEXT, null) }, modifier = Modifier.fillMaxWidth()) {
                Text("关闭内嵌字幕", color = Color.White, modifier = Modifier.fillMaxWidth())
            }
        }
        items(playback.textTracks, key = { it.id }) { track ->
            val externalSelected by controller.subtitle.collectAsState()
            TextButton(
                onClick = {
                    controller.selectExternalSubtitle(null)
                    controller.selectTrack(TrackType.TEXT, track.id)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val selected = track.isSelected && externalSelected.activeExternalId == null
                Text(track.displayLabel + if (selected) "（当前）" else "", color = Color.White, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SubtitlesPanel(controller: PlayerUiController, onPickSubtitleFile: () -> Unit) {
    val subtitle by controller.subtitle.collectAsState()
    val appearance = subtitle.appearance
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        item { PlayerSmallLabel("外挂字幕") }
        items(subtitle.external, key = { it.id }) { track ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { controller.selectExternalSubtitle(if (track.id == subtitle.activeExternalId) null else track.id) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        track.label + if (track.id == subtitle.activeExternalId) "（使用中）" else "",
                        color = Color.White,
                    )
                }
                TextButton(onClick = { controller.removeExternalSubtitle(track.id) }) { Text("移除", color = Color.White) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickSubtitleFile, modifier = Modifier.weight(1f)) { Text("从文件添加") }
            }
        }
        item { UrlSubtitleInput(controller) }
        item { PlayerSmallLabel("外观") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.setSubtitleAppearance(appearance.copy(fontScale = appearance.fontScale - 0.1f)) }) { Text("−", color = Color.White) }
                Text("%.1f".format(appearance.fontScale), color = Color.White)
                TextButton(onClick = { controller.setSubtitleAppearance(appearance.copy(fontScale = appearance.fontScale + 0.1f)) }) { Text("＋", color = Color.White) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("文字颜色", color = Color.White, modifier = Modifier.weight(1f))
                listOf(0xFFFFFFFFL, 0xFFFFD54FL, 0xFF80DEEAF, 0xFFA5D6A7L).forEach { color ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(24.dp)
                            .background(Color(color.toInt()))
                            .clickable { controller.setSubtitleAppearance(appearance.copy(textColorArgb = color)) },
                    )
                }
            }
        }
        item {
            Text("背景不透明度", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
            Slider(
                value = ((appearance.backgroundColorArgb shr 24) and 0xFF) / 255f,
                onValueChange = { alpha ->
                    val rgb = appearance.backgroundColorArgb and 0xFFFFFFL
                    controller.setSubtitleAppearance(
                        appearance.copy(backgroundColorArgb = ((alpha * 255).toLong() shl 24) or rgb),
                    )
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字幕延迟", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.setSubtitleDelay(appearance.delayMs - 500) }) { Text("−0.5s", color = Color.White) }
                Text("${appearance.delayMs / 1000.0}s", color = Color.White)
                TextButton(onClick = { controller.setSubtitleDelay(appearance.delayMs + 500) }) { Text("+0.5s", color = Color.White) }
            }
        }
        if (subtitle.message != null) {
            item { Text(subtitle.message.orEmpty(), color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun UrlSubtitleInput(controller: PlayerUiController) {
    var url by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("粘贴 .srt / .vtt / .ass 地址") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { if (url.isNotBlank()) { controller.addSubtitle(url.trim()); url = "" } }) {
            Text("加载", color = Color.White)
        }
    }
}

@Composable
private fun DanmakuPanel(controller: PlayerUiController) {
    val state by controller.danmaku.collectAsState()
    val preferences by controller.preferences.collectAsState()
    val settings = preferences.danmaku
    fun update(block: (com.moontvplus.phone.core.storage.DanmakuPreferences) -> com.moontvplus.phone.core.storage.DanmakuPreferences) {
        controller.updateDanmakuPreferences { prefs -> prefs.copy(danmaku = block(prefs.danmaku)) }
    }
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        item { SwitchRow("显示弹幕", state.enabled, { controller.setDanmakuEnabled(it) }) }
        item { SwitchRow("滚动弹幕", settings.scrollEnabled, { value -> update { it.copy(scrollEnabled = value) } }) }
        item { SwitchRow("顶部弹幕", settings.topEnabled, { value -> update { it.copy(topEnabled = value) } }) }
        item { SwitchRow("底部弹幕", settings.bottomEnabled, { value -> update { it.copy(bottomEnabled = value) } }) }
        item { PlayerSmallLabel("透明度") }
        item {
            Slider(
                value = settings.opacity,
                onValueChange = { value -> update { it.copy(opacity = value) } },
                valueRange = 0.1f..1f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item { PlayerSmallLabel("速度") }
        item {
            Slider(
                value = settings.speed,
                onValueChange = { value -> update { it.copy(speed = value) } },
                valueRange = 0.5f..2f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item { PlayerSmallLabel("字号") }
        item {
            Slider(
                value = settings.fontScale,
                onValueChange = { value -> update { it.copy(fontScale = value) } },
                valueRange = 0.6f..1.8f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item { PlayerSmallLabel("显示区域") }
        item {
            Slider(
                value = settings.areaPercent,
                onValueChange = { value -> update { it.copy(areaPercent = value) } },
                valueRange = 0.2f..0.9f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item { BlockedWordsInput(controller, settings.blockedWords) }
        item { PlayerSmallLabel("弹弹play 凭据（未配置时弹幕不会加载，但不影响播放）") }
        item { DandanplayCredentials(controller, settings) }
        item {
            Text(
                when {
                    !state.configured -> "弹幕未配置"
                    state.loading -> "弹幕加载中…"
                    state.matched != null -> run {
                        val matched = state.matched
                        "已匹配：${matched?.animeTitle} · ${matched?.episodeTitle}（${state.count} 条）"
                    }
                    else -> state.message ?: "尚未加载弹幕"
                },
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            TextButton(onClick = { controller.refreshDanmaku() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("重新拉取弹幕", color = Color.White)
            }
        }
    }
}

@Composable
private fun BlockedWordsInput(controller: PlayerUiController, words: List<String>) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("屏蔽词", color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("输入单词后添加") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val word = text.trim()
                if (word.isNotEmpty()) {
                    controller.updateDanmakuPreferences { prefs ->
                        prefs.copy(danmaku = prefs.danmaku.copy(blockedWords = prefs.danmaku.blockedWords + word))
                    }
                    text = ""
                }
            }) { Text("添加", color = Color.White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { word ->
                FilterChip(
                    selected = true,
                    onClick = {
                        controller.updateDanmakuPreferences { prefs ->
                            prefs.copy(danmaku = prefs.danmaku.copy(blockedWords = prefs.danmaku.blockedWords - word))
                        }
                    },
                    label = { Text(word) },
                )
            }
        }
    }
}

@Composable
private fun DandanplayCredentials(controller: PlayerUiController, settings: com.moontvplus.phone.core.storage.DanmakuPreferences) {
    var appId by remember(settings.appId) { mutableStateOf(settings.appId) }
    var secret by remember(settings.appSecret) { mutableStateOf(settings.appSecret) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppId") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        TextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("AppSecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(onClick = {
            controller.updateDanmakuPreferences { prefs ->
                prefs.copy(danmaku = prefs.danmaku.copy(appId = appId.trim(), appSecret = secret.trim()))
            }
            controller.refreshDanmaku()
        }) { Text("保存并重新加载") }
    }
}

@Composable
private fun CastPanel(controller: PlayerUiController) {
    val state by controller.cast.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Button(onClick = { controller.searchCastDevices() }, enabled = !state.searching) {
            Text(if (state.searching) "搜索中…" else "搜索设备")
        }
        if (state.connected != null) {
            TextButton(onClick = { controller.disconnectCast() }) { Text("断开", color = Color.White) }
        }
    }
    when {
        state.connected != null -> {
            val device = state.connected!!
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("正在投屏到 ${device.name}", color = Color.White)
                Text("状态：${state.transport}", color = Color.White.copy(alpha = 0.7f))
                Text(state.castingTitle, color = Color.White.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.castPlay() }) { Text("播放") }
                    Button(onClick = { controller.castPause() }) { Text("暂停") }
                    Button(onClick = { controller.castStop() }) { Text("停止") }
                }
                if (state.durationMs > 0L) {
                    Slider(
                        value = state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat()),
                        onValueChange = { controller.castSeek(it.toLong()) },
                        valueRange = 0f..state.durationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${formatPlayerTime(state.positionMs)} / ${formatPlayerTime(state.durationMs)}", color = Color.White)
                }
            }
        }
        state.devices.isEmpty() -> Text(
            state.message ?: "点击「搜索设备」查找同一 Wi-Fi 下的 DLNA 电视/盒子",
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(16.dp),
        )
        else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(state.devices, key = { it.id }) { device ->
                TextButton(onClick = { controller.connectCast(device) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(device.name, color = Color.White)
                        Text(device.modelName, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    state.message?.let {
        Text(it, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun SettingsPanel(controller: PlayerUiController, onOpenPanel: (PlayerPanel) -> Unit) {
    val state by controller.state.collectAsState()
    val preferences by controller.preferences.collectAsState()
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        item { SwitchRow("自动连播下一集", preferences.autoNext, { controller.persistAutoNext(it) }) }
        item { SwitchRow("自动跳过片头", state.autoSkipIntro, { controller.setAutoSkipIntro(it) }, "需要本集已记忆片头时间点") }
        item { SwitchRow("自动跳过片尾", state.autoSkipOutro, { controller.setAutoSkipOutro(it) }, "到达片尾起点后进入下一集") }
        item { PlayerSmallLabel("本集片头片尾（只影响这一集，按当前位置记忆）") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.markIntroHere() }, modifier = Modifier.weight(1f)) { Text("标记片头结束", color = Color.White) }
                OutlinedButton(onClick = { controller.markOutroHere() }, modifier = Modifier.weight(1f)) { Text("标记片尾开始", color = Color.White) }
            }
        }
        item {
            TextButton(onClick = { controller.clearSkipMarks() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("清除本集记录", color = Color.White)
            }
        }
        item { PlayerSmallLabel("更多设置") }
        item {
            TextButton(onClick = { onOpenPanel(PlayerPanel.SUBTITLES) }, modifier = Modifier.fillMaxWidth()) {
                Text("字幕外观与延迟", color = Color.White, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            TextButton(onClick = { onOpenPanel(PlayerPanel.DANMAKU) }, modifier = Modifier.fillMaxWidth()) {
                Text("弹幕、屏蔽词与凭据", color = Color.White, modifier = Modifier.fillMaxWidth())
            }
        }
        if (controller.capabilities.casting) {
            item {
                TextButton(onClick = { onOpenPanel(PlayerPanel.CAST) }, modifier = Modifier.fillMaxWidth()) {
                    Text("投屏", color = Color.White, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!controller.capabilities.inlinePlayback) {
            item {
                Text(
                    "桌面端没有内嵌播放器，画面、进度条、字幕与弹幕均不可用；投屏入口仍然可用。",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** 自动连播开关走独立方法，避免面板直接改偏好文件。 */
private fun PlayerUiController.persistAutoNext(enabled: Boolean) {
    // 会话里保存了 autoNext 偏好；这里复用 preference 更新入口
    updateDanmakuPreferences { prefs -> prefs.copy(autoNext = enabled) }
}
