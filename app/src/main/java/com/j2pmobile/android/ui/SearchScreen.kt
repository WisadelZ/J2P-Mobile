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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * 独立搜索页的可变状态（由 [AppShell] 持有）。
 *
 * 与探索页共用同一套控件，但布局不同：本页没有欢迎语与开屏态，进入即按传入的
 * 关键词与搜索方式拉取结果（详情页的标签 / 作者 / 角色跳转都落在这里）。
 */
class SearchState : SearchUiState {
    override var keywordInput by mutableStateOf("")
    override var keyword by mutableStateOf("")
    override var mode by mutableStateOf(MODE_ALL)
    override var sort by mutableStateOf(SORT_LATEST)
    override var time by mutableStateOf(TIME_ALL)
    override var uiPage by mutableStateOf(1)
    override var total by mutableStateOf(0)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    override var searching by mutableStateOf(false)
    override var hintRes by mutableStateOf(R.string.status_searching)
    override var hintArg by mutableStateOf<String?>(null)
    override var hintVisible by mutableStateOf(false)
    override var statusRes by mutableStateOf(R.string.status_ready)
    override var statusKind by mutableStateOf(StatusKind.IDLE)

    /** 每次「打开搜索页」自增，驱动页面重新发起搜索。 */
    var openSeq by mutableStateOf(0)

    private var loadEpoch by mutableStateOf(0)

    override val totalPages: Int get() = maxOf(1, (total + EXPLORE_PAGE_SIZE - 1) / EXPLORE_PAGE_SIZE)

    /** 打开搜索页：设置搜索方式与关键词，并请求重新搜索。 */
    fun open(modeValue: String, keywordValue: String) {
        loadEpoch += 1
        searching = false
        mode = modeValue
        keyword = keywordValue
        keywordInput = keywordValue
        items = emptyList()
        total = 0
        uiPage = 1
        sort = SORT_LATEST
        time = TIME_ALL
        hintRes = R.string.status_searching
        hintArg = null
        hintVisible = true
        statusRes = R.string.status_ready
        statusKind = StatusKind.IDLE
        openSeq += 1
    }

    internal fun nextEpoch(): Int {
        loadEpoch += 1
        return loadEpoch
    }

    internal fun epoch(): Int = loadEpoch
}

/**
 * 独立搜索页：详情页的标签 / 作者 / 角色点击后进入，按对应类型检索。
 *
 * 布局与探索页的置顶态一致（搜索框 + 翻页 + 网格），但保留搜索方式选择，
 * 用户可以临时改用「全部」搜索。返回键回到进入前的页面。
 */
@Composable
fun SearchScreen(
    state: SearchState,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun startLoad(page: Int) {
        if (state.searching) return
        val target = page.coerceIn(1, state.totalPages)
        state.searching = true
        val epoch = state.nextEpoch()
        state.items = emptyList()
        state.uiPage = target
        state.hintRes = R.string.status_searching
        state.hintArg = null
        state.hintVisible = true
        state.statusRes = R.string.status_searching
        state.statusKind = StatusKind.IDLE

        scope.launch {
            val result = ApiBridge.exploreSearch(state.mode, state.keyword, target, state.sort, state.time)
            if (epoch != state.epoch()) return@launch
            state.searching = false
            when (result) {
                is ApiResult.Err -> {
                    state.hintRes = R.string.explore_search_failed
                    state.hintArg = result.message
                    state.hintVisible = true
                    state.statusRes = R.string.status_search_failed
                    state.statusKind = StatusKind.ERR
                }

                is ApiResult.Ok -> {
                    state.total = result.value.total
                    state.items = result.value.items
                    state.statusRes = R.string.status_search_done
                    if (result.value.items.isEmpty()) {
                        state.hintRes = R.string.explore_no_result
                        state.hintArg = null
                        state.hintVisible = true
                        state.statusKind = StatusKind.IDLE
                    } else {
                        state.hintVisible = false
                        state.statusKind = StatusKind.OK
                    }
                }
            }
        }
    }

    fun doSearch() {
        val keyword = state.keywordInput.trim()
        if (keyword.isEmpty()) {
            state.hintRes = R.string.status_search_need_keyword
            state.hintArg = null
            state.hintVisible = true
            state.statusRes = R.string.status_search_need_keyword
            state.statusKind = StatusKind.ERR
            return
        }
        state.keyword = keyword
        state.total = 0
        state.uiPage = 1
        startLoad(1)
    }

    fun selectSort(key: String) {
        state.sort = key
        state.time = TIME_ALL
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    fun applyTime(value: String) {
        state.time = value
        if (value != TIME_ALL) state.sort = SORT_LATEST
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    // 每次打开（关键词 / 方式被重新设置）都重新搜索一次
    LaunchedEffect(state.openSeq) {
        if (state.keyword.isNotEmpty()) startLoad(1)
    }

    var showDateDialog by remember { mutableStateOf(false) }
    val pagerVisible = state.total > 0

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(state, onSearch = { doSearch() })
        SearchPagerRow(
            state = state,
            visible = pagerVisible,
            showSort = true,
            onPrev = { startLoad(state.uiPage - 1) },
            onNext = { startLoad(state.uiPage + 1) },
            onSort = { selectSort(it) },
            onOpenDate = { showDateDialog = true },
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
        // 结果为空时给一句提示，避免一片空白
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

    if (showDateDialog) {
        SearchDateFilterDialog(
            current = state.time,
            onDismiss = { showDateDialog = false },
            onConfirm = { value ->
                showDateDialog = false
                applyTime(value)
            },
        )
    }
}