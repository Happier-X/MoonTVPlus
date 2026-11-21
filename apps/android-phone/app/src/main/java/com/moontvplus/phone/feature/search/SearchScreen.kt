package com.moontvplus.phone.feature.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.ui.PosterImage
import com.moontvplus.phone.di.appViewModel
import com.moontvplus.ui.shell.WebTopBar
import com.moontvplus.ui.theme.WebColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    initialQuery: String? = null,
    onBack: () -> Unit,
    onOpenDetail: (source: String, videoId: String, title: String) -> Unit,
    siteName: String = "MoonTVPlus",
    viewModel: SearchViewModel = appViewModel {
        SearchViewModel(it.graph.searchRepository, it.graph.imageUrlBuilder)
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

        // 影视搜索胶囊（点一下就用当前关键词搜）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = state.query.isNotBlank()) { viewModel.submit() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(14.dp).height(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "影视搜索",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

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
                    count = state.results.size,
                    scanned = state.scanned,
                    totalSources = state.totalSources,
                    running = state.running,
                    onRefresh = { viewModel.submit() },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.results, key = { it.key }) { item ->
                        ResultCard(item = item, onClick = { viewModel.openPicker(item.key) })
                    }
                }
            }
        }
    }

    val picker = state.picker
    if (picker != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.openPicker(null) }) {
            SourcePickerSheet(
                item = picker,
                onPick = { result ->
                    viewModel.openPicker(null)
                    onOpenDetail(result.source, result.id, result.title.ifBlank { picker.title })
                },
            )
        }
    }
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
            if (item.sourceCount > 1) {
                Text(
                    text = "${item.sourceCount} 个源",
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WebColors.Accent)
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
    }
}

@Composable
private fun SourcePickerSheet(item: AggregatedResult, onPick: (SearchResult) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = item.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "共 ${item.sourceCount} 个可用来源，选一个开始播放",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        item.sources.forEachIndexed { index, result ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(result) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.sourceName.ifBlank { result.source },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val extra = listOfNotNull(
                        result.vodRemarks?.takeIf { it.isNotBlank() },
                        if (result.episodes.isNotEmpty()) "${result.episodes.size} 集" else null,
                        result.year.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (extra.isNotEmpty()) {
                        Text(
                            text = extra,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "播放",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = WebColors.Brand,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
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
