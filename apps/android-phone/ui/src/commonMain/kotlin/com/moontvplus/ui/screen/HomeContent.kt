package com.moontvplus.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.component.ContinueCard
import com.moontvplus.ui.component.ContinueCardWidth
import com.moontvplus.ui.component.HomeSectionRow
import com.moontvplus.ui.component.SectionHeader
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.PosterItem

/** 首页视图状态。 */
data class HomeUiState(
    val loading: Boolean = false,
    val continueWatching: HomeSection? = null,
    val sections: List<HomeSection> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
}

/**
 * 首页：继续观看 + 热门电影 / 剧集 / 番剧 / 综艺 / 短剧 / 即将上映。
 * 结构与网页版移动端首页一致：16dp 内边距、24dp 区块间距、20sp 区块标题。
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onItemClick: (PosterItem) -> Unit,
    onMore: (HomeSection) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.loading && state.sections.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (state.sections.isEmpty() && state.error != null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.error,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
        // 网页版首页两个区块之间实测 72dp（海报底 + 标题 → 下一个 h2）
        verticalArrangement = Arrangement.spacedBy(72.dp),
    ) {
        state.sections.forEach { section ->
            if (section.items.isEmpty() && section.error == null) return@forEach
            if (section.id == CONTINUE_SECTION_ID) {
                ContinueSection(
                    section = section,
                    onItemClick = onItemClick,
                    onOpenHistory = onOpenHistory,
                )
            } else {
                HomeSectionRow(section, onItemClick, onMore = { onMore(section) })
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private const val CONTINUE_SECTION_ID = "continue"

/** 继续观看：横向 180×120 卡片，标题右侧进播放记录。 */
@Composable
private fun ContinueSection(
    section: HomeSection,
    onItemClick: (PosterItem) -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section.title, onMore = onOpenHistory)
        Spacer(Modifier.height(12.dp))
        if (section.items.isEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) {
                    Box(
                        Modifier
                            .width(ContinueCardWidth)
                            .aspectRatio(3f / 2f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(section.items, key = { it.key }) { item ->
                    ContinueCard(item, onItemClick)
                }
            }
        }
    }
}
