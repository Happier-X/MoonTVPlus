package com.moontvplus.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.core.platform.openExternalPlayer
import com.moontvplus.phone.player.PlayerUiController

@Composable
actual fun VideoSurface(controller: PlayerUiController, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "桌面端暂不支持内嵌播放",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "当前播放器内核只有 Android 版本。投屏仍可用；\n也可以把这一集交给系统默认播放器打开。",
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                val url = controller.currentEpisodeRequest()?.url.orEmpty()
                openExternalPlayer(url)
            }) {
                Text("用外部播放器打开")
            }
        }
    }
}
