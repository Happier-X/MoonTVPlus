package com.moontvplus.phone.feature.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.model.AcgItem
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.model.AcgSource
import com.moontvplus.phone.core.ui.PosterImage
import com.moontvplus.phone.di.appViewModel
import com.moontvplus.ui.component.CapsuleTabs
import com.moontvplus.ui.component.FilterBar
import com.moontvplus.ui.shell.WebTopBar
import com.moontvplus.ui.theme.WebColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    initialQuery: String? = null,
    onBack: () -> Unit,
    onPlay: (source: String, videoId: String, title: String) -> Unit,
    siteName: String = "MoonTVPlus",
    onToggleTheme: (() -> Unit)? = null,
    viewModel: SearchViewModel = appViewModel {
        SearchViewModel(it.graph.searchRepository, it.graph.imageUrlBuilder, it.graph.settingsStore)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank() && initialQuery != state.query) {
            viewModel.onQueryChange(initialQuery)
            viewModel.submit(initialQuery)
        }
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // 网页版顶栏：左侧返回、中间站点名
        WebTopBar(
            title = siteName,
            onBack = onBack,
            onToggleTheme = onToggleTheme,
        )

        // 搜索输入框：48dp、圆角 8、浅灰底
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp).height(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) {
                        Text(
                            text = "搜索电影、电视剧...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(WebColors.Brand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                if (state.query.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.onQueryChange("") },
                        modifier = Modifier.width(28.dp).height(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(16.dp).height(16.dp),
                        )
                    }
                }
            }
        }

        // 网页版 Tab 胶囊：影视搜索 / 动漫磁力
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CapsuleTabs(
                options = SearchTab.entries.map { it.label },
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> viewModel.selectTab(SearchTab.entries[index]) },
            )
        }

        when (state.tab) {
            SearchTab.VIDEO -> {
                if (state.running) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = WebColors.Accent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.totalSources > 0) {
                                    "已扫描 ${state.scanned}/${state.totalSources} 个源"
                                } else {
                                    "正在搜索…"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "停止",
                                fontSize = 12.sp,
                                color = WebColors.Brand,
                                modifier = Modifier.clickable { viewModel.stop() }.padding(4.dp),
                            )
                        }
                    }
                }

                if (state.error != null) {
                    Text(
                        text = state.error.orEmpty(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                if (state.results.isEmpty() && !state.running) {
                    HistoryBlock(
                        history = state.history,
                        onPick = { keyword ->
                            viewModel.onQueryChange(keyword)
                            viewModel.submit(keyword)
                        },
                        onRemove = viewModel::removeHistory,
                        onClear = { state.history.forEach(viewModel::removeHistory) },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        SearchResultHeader(
                            count = state.visibleResults.size,
                            scanned = state.scanned,
                            totalSources = state.totalSources,
                            running = state.running,
                            onRefresh = { viewModel.submit() },
                        )
                        if (state.results.isNotEmpty()) {
                            FilterBar(
                                categories = state.filterCategories,
                                values = state.filters,
                                onChange = viewModel::setFilter,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                trailing = {
                                    YearOrderButton(state.yearOrder, viewModel::cycleYearOrder)
                                    AdvancedMenu(
                                        aggregate = state.aggregate,
                                        displayMode = state.displayMode,
                                        onAggregate = viewModel::setAggregate,
                                        onDisplayMode = viewModel::setDisplayMode,
                                    )
                                },
                            )
                        }
                        if (state.visibleResults.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "未找到相关结果",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (state.displayMode == "list") {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.visibleResults, key = { it.key }) { item ->
                                    ResultListRow(item = item, onClick = {
                                        val best = item.primary
                                        onPlay(best.source, best.id, item.title.ifBlank { best.title })
                                    })
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(56.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.visibleResults, key = { it.key }) { item ->
                                    ResultCard(item = item, onClick = {
                                        val best = item.primary
                                        onPlay(best.source, best.id, item.title.ifBlank { best.title })
                                    })
                                }
                            }
                        }
                    }
                }
            }

            SearchTab.ACG -> AcgTabBody(state = state, viewModel = viewModel)
        }
    }
}

/** 「年份」排序按钮：无 → 降序 → 升序，和网页版的图标切换一致。 */
@Composable
private fun YearOrderButton(order: String, onClick: () -> Unit) {
    val active = order != "none"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) WebColors.Accent.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "年份排序",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = when (order) {
                "desc" -> "降序"
                "asc" -> "升序"
                else -> "默认"
            },
            fontSize = 13.sp,
            color = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 动漫磁力：四个源的胶囊 + 资源卡片列表（复制磁链 / 打开详情）。 */
@Composable
private fun AcgTabBody(state: SearchViewModel.UiState, viewModel: SearchViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copiedKey by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CapsuleTabs(
                options = AcgSource.entries.map { it.label },
                selectedIndex = state.acgSource.ordinal,
                onSelect = { index -> viewModel.setAcgSource(AcgSource.entries[index]) },
            )
        }

        when {
            state.acgLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.acgError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.acgError,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }

            state.acgItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.query.isBlank()) "先输入关键词再搜索" else "未找到相关资源",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.acgItems, key = { it.key }) { item ->
                    AcgCard(
                        item = item,
                        copied = copiedKey == item.key,
                        onCopy = {
                            clipboard.setText(AnnotatedString(item.downloadUrl))
                            copiedKey = item.key
                        },
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(item.link.ifBlank { item.downloadUrl }))
                                )
                            }
                        },
                    )
                }
                if (state.acgSource.supportsPaging && state.acgItems.isNotEmpty()) {
                    item {
                        LaunchedEffect(state.acgItems.size) { viewModel.loadMoreAcg() }
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.acgLoadingMore) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(20.dp).height(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcgCard(item: AcgItem, copied: Boolean, onCopy: () -> Unit, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val published = formatPubDate(item.pubDate)
        if (published.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = published,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.images.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.images.take(3).forEach { image ->
                    PosterImage(
                        url = image,
                        contentDescription = null,
                        modifier = Modifier
                            .height(80.dp)
                            .width(80.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcgAction(if (copied) "已复制" else "复制磁链", onCopy)
            AcgAction("详情", onOpen)
        }
    }
}

/** RSS 里的 RFC822 时间转成本地时间，和网页版 toLocaleString 一致。 */
private fun formatPubDate(raw: String): String {
    if (raw.isBlank()) return ""
    val parsed = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH).parse(raw)
    }.getOrNull() ?: return raw
    return java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.CHINA).format(parsed)
}

@Composable
private fun AcgAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 「搜索结果 聚合结果 N 组」+ 右侧刷新。 */
@Composable
private fun SearchResultHeader(
    count: Int,
    scanned: Int,
    totalSources: Int,
    running: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "搜索结果",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "聚合结果 ${count} 组",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (totalSources > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (running) "源 $scanned/$totalSources" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "刷新",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onRefresh() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ResultCard(item: AggregatedResult, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            PosterImage(
                url = item.poster,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            item.year.takeIf { it.isNotBlank() }?.let { year ->
                Text(
                    text = year,
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WebColors.YearChip)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.year.takeIf { it.isNotBlank() },
            item.typeLabel,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SourceTags(item.sources, compact = true)
    }
}

/** 网页播放源的胶囊标签：最多 3 个，其余收进「+N」，点标签不会触发播放。 */
@Composable
private fun SourceTags(sources: List<SearchResult>, compact: Boolean) {
    val names = sources.map { it.sourceName.ifBlank { it.source } }.distinct().filter { it.isNotBlank() }
    if (names.isEmpty()) return
    var expanded by remember(names) { mutableStateOf(false) }
    val shown = names.take(3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { name ->
            SourceChip(text = name, compact = compact, onClick = {})
        }
        if (names.size > shown.size) {
            Box {
                SourceChip(
                    text = "+" + (names.size - shown.size),
                    compact = compact,
                    active = true,
                    onClick = { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    names.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, fontSize = 13.sp, maxLines = 1) },
                            onClick = { expanded = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(
    text: String,
    compact: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Text(
        text = text,
        fontSize = if (compact) 9.sp else 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .widthIn(max = if (compact) 58.dp else 150.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (active) WebColors.Accent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 列表视图：标题、年份、类型、集数、简介、来源标签。 */
@Composable
private fun ResultListRow(item: AggregatedResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        PosterImage(
            url = item.poster,
            contentDescription = item.title,
            modifier = Modifier
                .width(64.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                item.year.takeIf { it.isNotBlank() }?.let { add(it) }
                item.typeLabel?.let { add(it) }
                if (item.episodeCount > 0) add(item.episodeCount.toString() + " 集")
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            item.primary.desc?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SourceTags(item.sources, compact = false)
        }
    }
}

/** 网页版「高级」下拉：聚合开关 + 卡片/列表。 */
@Composable
private fun AdvancedMenu(
    aggregate: Boolean,
    displayMode: String,
    onAggregate: (Boolean) -> Unit,
    onDisplayMode: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "高级",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(220.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("聚合", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = aggregate, onCheckedChange = onAggregate)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    "显示方式",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                ) {
                    DisplayModeButton("卡片", Icons.Filled.Menu, displayMode == "card", Modifier.weight(1f)) {
                        onDisplayMode("card")
                    }
                    DisplayModeButton("列表", Icons.AutoMirrored.Filled.List, displayMode == "list", Modifier.weight(1f)) {
                        onDisplayMode("list")
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp).height(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (active) WebColors.AccentHover else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryBlock(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "搜索历史",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        if (history.isEmpty()) {
            Text(
                text = "输入片名开始搜索，支持电影、剧集、综艺和短剧",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { keyword ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPick(keyword) }
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = keyword,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { onRemove(keyword) },
                        modifier = Modifier.width(22.dp).height(22.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(13.dp).height(13.dp),
                        )
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "清空",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onClear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
