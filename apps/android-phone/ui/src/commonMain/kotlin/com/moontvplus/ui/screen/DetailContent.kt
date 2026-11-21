package com.moontvplus.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.NetworkImage
import com.moontvplus.ui.theme.WebColors

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
 * 详情页：对齐网页版播放页下方的信息区 ——
 * 海报 + 标题 + 元信息 + 收藏，绿色播放按钮，简介，6 列选集。
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // 头部：海报 + 标题/元信息/收藏
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                NetworkImage(
                    url = state.posterUrl,
                    contentDescription = state.title,
                    modifier = Modifier
                        .width(96.dp)
                        .height(144.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
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
                            maxLines = 3,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (state.favorite) WebColors.Accent.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = !state.favoriteBusy) { onToggleFavorite() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            tint = if (state.favorite) WebColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(16.dp).height(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.favorite) "已收藏" else "收藏",
                            fontSize = 12.sp,
                            color = if (state.favorite) WebColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 播放主按钮
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onPlay(state.resumeIndex.coerceAtLeast(0)) },
                enabled = state.episodes.isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WebColors.Brand,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(44.dp),
            ) {
                Text(
                    text = if (state.hasResume) {
                        "继续观看 第 ${state.resumeIndex + 1} 集"
                    } else {
                        "播放"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
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
                Spacer(Modifier.height(16.dp))
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 选集
            if (state.episodes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选集",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "1-${state.episodes.size}",
                        fontSize = 12.sp,
                        color = WebColors.Accent,
                    )
                }
                Spacer(Modifier.height(12.dp))
                EpisodeGrid(
                    episodes = state.episodes,
                    activeIndex = if (state.hasResume) state.resumeIndex else -1,
                    onSelect = onPlay,
                )
            }
        }
    }
}

/** 6 列固定网格（与网页版选集一致），避免在 scroll 里嵌滚动容器。 */
@Composable
private fun EpisodeGrid(
    episodes: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        episodes.chunked(6).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { column, name ->
                    val index = rowIndex * 6 + column
                    val active = index == activeIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) WebColors.Accent
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
