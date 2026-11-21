package com.moontvplus.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moontvplus.ui.model.FilterCategory
import com.moontvplus.ui.model.FilterOption
import com.moontvplus.ui.theme.WebColors
import androidx.compose.ui.graphics.Color

/**
 * 网页版的筛选胶囊条：一行可横滑的维度按钮，点开后在下方展开 3 列选项面板。
 *
 * 与网页一致：有选中值时按钮文字换成选项名并变绿，展开面板里选中项高亮。
 */
@Composable
fun FilterBar(
    categories: List<FilterCategory>,
    values: Map<String, String>,
    onChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    var openKey by remember(categories) { mutableStateOf<String?>(null) }
    val openCategory = categories.firstOrNull { it.key == openKey }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            categories.forEach { category ->
                val value = values[category.key] ?: category.defaultValue
                val active = value != category.defaultValue
                val expanded = openKey == category.key
                FilterPill(
                    text = if (active) category.labelOf(value) else category.label,
                    active = active,
                    expanded = expanded,
                    onClick = { openKey = if (expanded) null else category.key },
                )
            }
            if (trailing != null) {
                trailing()
            }
        }

        if (openCategory != null) {
            FilterPanel(
                category = openCategory,
                selected = values[openCategory.key] ?: openCategory.defaultValue,
                onPick = { option ->
                    onChange(openCategory.key, option.value)
                    openKey = null
                },
            )
        }
    }
}

@Composable
private fun FilterPill(
    text: String,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val color = when {
        active -> WebColors.AccentHover
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) WebColors.Accent.copy(alpha = 0.12f)
                else if (expanded) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/** 展开的选项面板：白底卡片 + 3 列选项，最多 280dp 高，超出滚动。 */
@Composable
private fun FilterPanel(
    category: FilterCategory,
    selected: String,
    onPick: (FilterOption) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            category.options.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { option ->
                        val active = option.value == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (active) WebColors.Accent.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable { onPick(option) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 13.sp,
                                color = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 分类页顶部：「筛选」小标题 + 胶囊条，和网页版外层卡片一致。 */
@Composable
fun FilterSection(
    categories: List<FilterCategory>,
    values: Map<String, String>,
    onChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (isDarkSurface()) MaterialTheme.colorScheme.surface else Color.White.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            ),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                FilterBar(categories = categories, values = values, onChange = onChange)
            }
        }
    }
}

@Composable
private fun isDarkSurface(): Boolean =
    MaterialTheme.colorScheme.background == WebColors.BackgroundDark

/** 胶囊小标签，用于「N 项」「N 个源」之类的计数角标。 */
@Composable
fun CountChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 11.sp, color = color)
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
    )
}

/** 网页版 CapsuleSwitch：灰底胶囊容器 + 选中项白色药丸。 */
@Composable
fun CapsuleTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
