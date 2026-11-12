package com.moontvplus.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.NetworkImage
import com.moontvplus.ui.theme.SeleneColors

/** 详情页视图状态。选集下标 0 起，与服务端 `episodes` 一致。 */
data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val posterUrl: String? = null,
    val meta: String = "",
    val desc: String? = null,
    val episodes: List<String> = emptyList(),
    val favorite: Boolean = false,
    val favoriteBusy: Boolean = false,
    val resumeIndex: Int = 0,
    /** 续播位置（毫秒），只有 [hasResume] 为 true 时有意义 */
    val resumePositionMs: Long = 0L,
    val hasResume: Boolean = false,
    val playHint: String? = null,
)

/**
 * 详情页：海报 + 元信息 + 收藏 + 播放按钮 + 选集。
 * 结构参照 Selene 的详情/选集两个区块合在一屏。
 */
@Composable
fun DetailContent(
    state: DetailUiState,
    onPlay: (index: Int) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                state.playHint?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        state.error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.error,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // 头部：海报 + 标题/元信息/收藏
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                NetworkImage(
                    url = state.posterUrl,
                    contentDescription = state.title,
                    modifier = Modifier
                        .width(120.dp)
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.meta.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.meta,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onToggleFavorite,
                        enabled = !state.favoriteBusy,
                    ) {
                        Text(
                            text = if (state.favorite) "已收藏" else "收藏",
                            fontSize = 13.sp,
                            color = if (state.favorite) SeleneColors.Accent else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 播放主按钮
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onPlay(state.resumeIndex.coerceAtLeast(0)) },
                enabled = state.episodes.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(46.dp),
            ) {
                Text(
                    text = if (state.hasResume) {
                        "继续观看 第 ${state.resumeIndex + 1} 集"
                    } else {
                        "播放"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            state.playHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 简介
            val desc = state.desc
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 选集
            if (state.episodes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "选集（共 ${state.episodes.size} 集）",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                EpisodeGrid(
                    episodes = state.episodes,
                    activeIndex = if (state.hasResume) state.resumeIndex else -1,
                    onSelect = onPlay,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeGrid(
    episodes: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
) {
    // 外层已经是 scroll，选集用流式布局铺开，避免嵌套滚动
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        episodes.forEachIndexed { index, name ->
            val active = index == activeIndex
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (active) SeleneColors.Accent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (active) SeleneColors.Accent else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
