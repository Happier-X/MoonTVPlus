package com.moontvplus.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.phone.player.PlayerUiController

/**
 * 外挂字幕层。
 *
 * 内嵌字幕轨由 Android 的 SubtitleView 渲染；这里只画用户加载的 SRT/VTT/ASS，
 * 这样才能做到字号、颜色、背景和延迟都可调（Media3 不支持字幕延迟）。
 */
@Composable
fun ExternalSubtitleOverlay(controller: PlayerUiController, modifier: Modifier = Modifier) {
    val subtitle by controller.subtitle.collectAsState()
    val playback by controller.playback.collectAsState()
    val cue = subtitle.cueAt(playback.positionMs) ?: return
    val appearance = subtitle.appearance

    Box(
        modifier.fillMaxSize().padding(bottom = (appearance.bottomPaddingPercent * 100).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = cue.text,
            color = Color(appearance.textColorArgb.toInt()),
            fontSize = (18f * appearance.fontScale).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .shadow(2.dp)
                .background(Color(appearance.backgroundColorArgb.toInt()), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
