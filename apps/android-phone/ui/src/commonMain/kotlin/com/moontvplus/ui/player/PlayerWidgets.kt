package com.moontvplus.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 播放器里的时间格式：一小时以内 mm:ss，超过则 h:mm:ss。 */
fun formatPlayerTime(positionMs: Long): String {
    val total = positionMs.coerceAtLeast(0L) / 1000L
    val seconds = total % 60L
    val minutes = (total / 60L) % 60L
    val hours = total / 3600L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    // 网站版控制条按钮是 46dp 见方、图标 24dp；IconButton 的 48dp 最小尺寸会把
    // 320dp 屏挤爆，所以这里用 Box 自己画，点击事件仍然是按钮自己的。
    Box(
        modifier = modifier
            .size(46.dp)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 设置面板里的一行：图标 + 标题 + 右侧值 + 箭头，高度对齐 ArtPlayer 的 44dp 行。 */
@Composable
fun PlayerMenuRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        if (value != null) {
            Text(value, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 统一的面板外壳：对齐 ArtPlayer 的设置面板 ——
 * 右下角浮层、宽 300dp、最高 300dp、黑 85% 底、圆角 6dp，点外面关闭。
 */
@Composable
fun PlayerPanelScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.85f),
            modifier = modifier
                .padding(end = 10.dp, bottom = 96.dp)
                .width(300.dp)
                .heightIn(max = 300.dp)
                .clickable { },
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭面板",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
fun PlayerSmallLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 11.sp,
        color = Color.White.copy(alpha = 0.6f),
    )
}
