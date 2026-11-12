package com.moontvplus.phone.feature.detail

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.ui.PosterImage

@Composable
fun DetailScreen(
    source: String,
    videoId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onPlay: (index: Int, startPositionMs: Long) -> Unit,
    viewModel: DetailViewModel = appViewModel {
        DetailViewModel(it.graph.moonApi, it.graph.imageUrlBuilder, it.graph.serverProvider)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(source, videoId) { viewModel.load(source, videoId) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.retry(source, videoId) }) { Text("重试") }
            }
            else -> DetailContent(
                state = state,
                fallbackTitle = fallbackTitle,
                onPlay = onPlay,
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(4.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailViewModel.UiState,
    fallbackTitle: String,
    onPlay: (index: Int, startPositionMs: Long) -> Unit,
) {
    val detail = state.detail ?: return
    val title = detail.title.ifBlank { fallbackTitle }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row {
                    PosterImage(
                        url = state.poster,
                        contentDescription = title,
                        modifier = Modifier
                            .width(110.dp)
                            .aspectRatio(2f / 3f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        val meta = listOfNotNull(
                            detail.year.takeIf { it.isNotBlank() },
                            detail.typeName?.takeIf { it.isNotBlank() },
                            detail.sourceName.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!detail.vodRemarks.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = detail.vodRemarks.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onPlay(state.resumeIndex, state.resumePositionMs) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.hasResume && state.episodeCount > 1) {
                                    "继续观看 第 ${state.resumeIndex} 集"
                                } else {
                                    "播放"
                                }
                            )
                        }
                    }
                }

                if (!detail.desc.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = detail.desc.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选集",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "共 ${state.episodeCount} 集",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        items(state.episodeTitles.size) { index ->
            EpisodeCell(
                index = index + 1,
                title = state.episodeTitles[index],
                isCurrent = state.hasResume && state.resumeIndex == index + 1,
                onClick = {
                    val position = if (state.resumeIndex == index + 1) state.resumePositionMs else 0L
                    onPlay(index + 1, position)
                },
            )
        }
    }
}

@Composable
private fun EpisodeCell(
    index: Int,
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = "第 $index 集",
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
