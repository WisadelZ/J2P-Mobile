/*
 * Copyright (C) 2026 WisadelZ
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.j2pmobile.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import kotlinx.coroutines.launch

/**
 * 浏览历史完整页的可变状态（由 [AppShell] 持有）。
 *
 * 只记录浏览过的本子（不记录 tag）；关键词是本地过滤（按名称 / 作者 / ID），
 * 搜索框控件与探索页一致，但不带搜索方式选择。
 */
class HistoryState : SearchUiState {
    override var keywordInput by mutableStateOf("")
    override var keyword by mutableStateOf("")
    override var mode by mutableStateOf(MODE_ALL)
    override var sort by mutableStateOf(SORT_LATEST)
    override var time by mutableStateOf(TIME_ALL)
    override var uiPage by mutableStateOf(1)
    override var total by mutableStateOf(0)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    override var searching by mutableStateOf(false)
    override var hintRes by mutableStateOf(R.string.history_loading)
    override var hintArg by mutableStateOf<String?>(null)
    override var hintVisible by mutableStateOf(true)
    override var statusRes by mutableStateOf(R.string.status_ready)
    override var statusKind by mutableStateOf(StatusKind.IDLE)

    var loaded by mutableStateOf(false)

    override val totalPages: Int get() = maxOf(1, (total + EXPLORE_PAGE_SIZE - 1) / EXPLORE_PAGE_SIZE)

    fun reset() {
        uiPage = 1
        total = 0
        items = emptyList()
        keywordInput = ""
        keyword = ""
        loaded = false
        searching = false
        hintRes = R.string.history_loading
        hintArg = null
        hintVisible = true
    }
}

/** 浏览历史完整页：网格展示浏览过的本子，支持本地搜索与翻页。 */
@Composable
fun HistoryScreen(
    state: HistoryState,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun startLoad(page: Int) {
        if (state.searching) return
        val target = page.coerceIn(1, state.totalPages)
        state.searching = true
        state.uiPage = target
        state.items = emptyList()
        state.hintRes = R.string.history_loading
        state.hintArg = null
        state.hintVisible = true
        state.statusRes = R.string.history_loading
        state.statusKind = StatusKind.IDLE
        scope.launch {
            when (val result = ApiBridge.historyPage(target, state.keyword)) {
                is ApiResult.Ok -> {
                    state.total = result.value.total
                    state.items = result.value.items
                    state.loaded = true
                    state.statusRes = R.string.status_history_loaded
                    if (result.value.items.isEmpty()) {
                        state.hintRes = R.string.history_empty
                        state.hintArg = null
                        state.hintVisible = true
                        state.statusKind = StatusKind.IDLE
                    } else {
                        state.hintVisible = false
                        state.statusKind = StatusKind.OK
                    }
                }

                is ApiResult.Err -> {
                    state.hintRes = R.string.history_load_failed
                    state.hintArg = result.message
                    state.hintVisible = true
                    state.statusRes = R.string.status_error_hint
                    state.statusKind = StatusKind.ERR
                }
            }
            state.searching = false
        }
    }

    fun doSearch() {
        state.keyword = state.keywordInput.trim()
        state.total = 0
        state.uiPage = 1
        startLoad(1)
    }

    LaunchedEffect(Unit) {
        if (!state.loaded && !state.searching) startLoad(1)
    }

    val pagerVisible = state.total > 0

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(state, onSearch = { doSearch() }, showMode = false)
        SearchPagerRow(
            state = state,
            visible = pagerVisible,
            showSort = false,
            onPrev = { startLoad(state.uiPage - 1) },
            onNext = { startLoad(state.uiPage + 1) },
            onSort = {},
            onOpenDate = {},
        )
        SearchHintLine(state)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                AlbumCard(item = item, onClick = { onOpenAlbum(item.id) })
            }
        }
        SearchPagerRow(
            state = state,
            visible = pagerVisible,
            showSort = false,
            onPrev = { startLoad(state.uiPage - 1) },
            onNext = { startLoad(state.uiPage + 1) },
            onSort = {},
            onOpenDate = {},
        )
        StatusLine(state.statusRes, null, state.statusKind)
    }
}