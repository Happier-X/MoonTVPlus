package com.moontvplus.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.PosterItem
import com.moontvplus.ui.theme.WebColors

/** 网页版首页横滑海报：实测 96×144，步进 104，圆角 8。 */
val PosterWidth = 96.dp
private val PosterShape = RoundedCornerShape(8.dp)

/** 网页版继续观看卡：180×120（3:2），圆角 8。 */
val ContinueCardWidth = 180.dp

/**
 * 网页版海报卡：封面 2:3、圆角 8，标题在下方居中；
 * 左上角年份/集数角标、右上角评分圆点、封面底部进度条。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: PosterItem,
    onClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    /** null 表示宽度由父容器决定（网格用），否则固定宽度（横滑列表用）。 */
    width: Dp? = PosterWidth,
    titleLines: Int = 1,
    onLongClick: (() -> Unit)? = null,
) {
    val columnModifier = if (width == null) modifier.fillMaxWidth() else modifier.width(width)
    Column(
        modifier = columnModifier.combinedClickable(
            onClick = { onClick(item) },
            onLongClick = onLongClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val posterModifier = if (width == null) {
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
        } else {
            Modifier.fillMaxWidth().height(width * 1.5f)
        }

        Box(
            modifier = posterModifier
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            NetworkImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            // 左上角：年份 / 集数角标（深蓝灰底白字）
            val corner = item.year?.takeIf { it.isNotBlank() } ?: item.badge?.takeIf { it.isNotBlank() }
            corner?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WebColors.YearChip)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            // 右上角：评分圆点；没有评分但有集数角标时用绿色胶囊
            val rate = item.rate?.takeIf { it.isNotBlank() }
            val badge = item.badge?.takeIf { it.isNotBlank() }
            when {
                rate != null -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(WebColors.Rating),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rate,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                badge != null && item.year != null -> Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WebColors.Accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            // 底部进度条
            val progress = item.progress
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(WebColors.Accent),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = item.subtitle
        if (!subtitle.isNullOrBlank() && titleLines > 1) {
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 继续观看卡（网页版 180×120）：封面叠渐变、标题压在图内，
 * 底部一行是时长胶囊与片源角标，封面底部一条进度线，图下是集数文字。
 */
@Composable
fun ContinueCard(
    item: PosterItem,
    onClick: (PosterItem) -> Unit,
) {
    Column(Modifier.width(ContinueCardWidth).clickable { onClick(item) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            NetworkImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.35f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.75f),
                        )
                    ),
            )
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 6.dp, end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.durationText?.takeIf { it.isNotBlank() }?.let { duration ->
                    Text(
                        text = duration,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                item.sourceText?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        text = source,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            val progress = item.progress
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(WebColors.Accent),
                    )
                }
            }
        }
        item.episodeText?.takeIf { it.isNotBlank() }?.let { episode ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = episode,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 区块标题：20sp/700 + 右侧「查看更多 ›」14sp 次要色。 */
@Composable
fun SectionHeader(
    title: String,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onMore() }
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看更多", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 首页的一个横向区块：标题 + 海报横滑列表，区块自己带错误态与骨架。 */
@Composable
fun HomeSectionRow(
    section: HomeSection,
    onItemClick: (PosterItem) -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section.title, onMore)
        // 网页版实测：h2 底到海报顶 20dp
        Spacer(Modifier.height(20.dp))

        when {
            section.items.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(section.items, key = { it.key }) { item ->
                    PosterCard(item, onItemClick)
                }
            }

            section.error != null -> Text(
                text = section.error,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            else -> Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) {
                    Box(
                        Modifier
                            .size(PosterWidth, PosterWidth * 1.5f)
                            .clip(PosterShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}
