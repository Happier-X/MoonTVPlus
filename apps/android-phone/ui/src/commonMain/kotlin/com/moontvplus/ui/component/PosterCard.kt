package com.moontvplus.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.PosterItem
import com.moontvplus.ui.theme.SeleneColors

/** Selene 的卡片：宽 120、高 180（1.5 倍）、圆角 8。 */
private val CardWidth = 120.dp
private val CardShape = RoundedCornerShape(8.dp)

@Composable
fun PosterCard(
    item: PosterItem,
    onClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    /** null 表示宽度由父容器决定（网格用），否则固定宽度（横滑列表用）。 */
    width: Dp? = CardWidth,
) {
    val columnModifier = if (width == null) modifier.fillMaxWidth() else modifier.width(width)
    Column(modifier = columnModifier.clickable { onClick(item) }) {
        val posterModifier = if (width == null) {
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
        } else {
            Modifier.fillMaxWidth().height(width * 1.5f)
        }

        Box(
            modifier = posterModifier
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            NetworkImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            // 左上角年份角标
            item.year?.takeIf { it.isNotBlank() }?.let { year ->
                Text(
                    text = year,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SeleneColors.YearChip)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }

            // 右上角：有评分画粉色圆点，否则画绿色集数角标
            val rate = item.rate?.takeIf { it.isNotBlank() }
            val badge = item.badge?.takeIf { it.isNotBlank() }
            when {
                rate != null -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(SeleneColors.Rating),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rate,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                badge != null -> Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SeleneColors.Accent)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }

            // 底部进度条
            val progress = item.progress
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(SeleneColors.Accent),
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = item.subtitle
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 区块标题：左标题 + 右侧绿色「更多」。 */
@Composable
fun SectionHeader(
    title: String,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            TextButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("查看更多 >", fontSize = 13.sp, color = SeleneColors.Accent)
            }
        }
    }
}

/** 首页的一个横向区块：标题 + 海报横滑列表，区块自己带错误态。 */
@Composable
fun HomeSectionRow(
    section: HomeSection,
    onItemClick: (PosterItem) -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section.title, onMore)
        Spacer(Modifier.height(8.dp))

        when {
            section.items.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(CardWidth, CardWidth * 1.5f)
                        .clip(CardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(12.dp))
                Text("加载中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
