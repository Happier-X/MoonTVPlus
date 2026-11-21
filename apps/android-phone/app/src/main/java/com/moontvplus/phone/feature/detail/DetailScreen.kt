package com.moontvplus.phone.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.di.appViewModel
import com.moontvplus.ui.screen.DetailContent
import com.moontvplus.ui.screen.DetailUiState
import com.moontvplus.ui.shell.WebTopBar

@Composable
fun DetailScreen(
    source: String,
    videoId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onPlay: (index: Int, startPositionMs: Long) -> Unit,
    playbackReturn: Int = 0,
    siteName: String = "MoonTVPlus",
    onToggleTheme: (() -> Unit)? = null,
    viewModel: DetailViewModel = appViewModel {
        DetailViewModel(
            it.graph.moonApi,
            it.graph.imageUrlBuilder,
            it.graph.serverProvider,
            it.graph.libraryRepository,
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(source, videoId, playbackReturn) { viewModel.load(source, videoId, force = true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        WebTopBar(title = siteName, onBack = onBack, onToggleTheme = onToggleTheme)
        if (state.loading && state.detail == null) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            DetailContent(
                state = state.toDetailUiState(fallbackTitle),
                onPlay = { index ->
                    val episode = index + 1
                    val position = if (state.resumeIndex == episode) state.resumePositionMs else 0L
                    onPlay(episode, position)
                },
                onToggleFavorite = { viewModel.toggleFavorite(source, videoId) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 原生详情状态映射到共享的详情视图状态（两边共用一套 UI）。 */
private fun DetailViewModel.UiState.toDetailUiState(fallbackTitle: String): DetailUiState {
    val detail = detail
    val meta = listOfNotNull(
        detail?.year?.takeIf { it.isNotBlank() },
        detail?.typeName?.takeIf { it.isNotBlank() },
        detail?.sourceName?.takeIf { it.isNotBlank() },
        detail?.vodRemarks?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    return DetailUiState(
        loading = loading && detail == null,
        error = error,
        title = detail?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
        posterUrl = poster,
        meta = meta,
        desc = detail?.desc,
        episodes = episodeTitles.ifEmpty { detail?.episodes.orEmpty() },
        favorite = favorite,
        favoriteBusy = favoriteBusy,
        resumeIndex = (resumeIndex - 1).coerceAtLeast(0),
        resumePositionMs = resumePositionMs,
        hasResume = hasResume,
    )
}
