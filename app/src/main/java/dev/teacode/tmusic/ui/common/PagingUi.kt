package dev.teacode.tmusic.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
fun LoadMoreEffect(
    listState: LazyListState,
    itemCount: Int,
    canLoadMore: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
) {
    val shouldLoadMore by remember(listState, itemCount, canLoadMore, isLoading) {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            canLoadMore && !isLoading && itemCount > 0 && lastVisibleIndex >= itemCount - 4
        }
    }

    LaunchedEffect(shouldLoadMore, itemCount) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }
}
