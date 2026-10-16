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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import kotlinx.coroutines.launch

/** 界面每页条数（库每页 80 条，不与界面页整除，桥接层按全局偏移拼接）。 */
const val EXPLORE_PAGE_SIZE = 18

/**
 * 探索页的可变状态。
 *
 * 由 [AppShell] 持有而不是页面内部 remember：进本子详情页再返回时，搜索结果仍然保留
 * （等价于桌面版把 ExplorePage 实例缓存起来的行为）。
 */
class ExploreState : SearchUiState {
    /** 输入框里的文字（未点搜索前也在）。 */
    override var keywordInput by mutableStateOf("")

    /** 上次搜索用的关键词；翻页 / 换排序时沿用它。 */
    override var keyword by mutableStateOf("")

    override var mode by mutableStateOf(MODE_ALL)
    override var sort by mutableStateOf(SORT_LATEST)
    override var time by mutableStateOf(TIME_ALL)
    override var uiPage by mutableStateOf(1)
    override var total by mutableStateOf(0)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    override var searching by mutableStateOf(false)

    /** 是否已发起过首次搜索：决定搜索区「居中态」还是「置顶态」，此后不再变化。 */
    var searchedOnce by mutableStateOf(false)

    /** 提示语用「资源 id + 可选参数」存，语言切换后自动跟着变。 */
    override var hintRes by mutableStateOf(R.string.explore_hint)
    override var hintArg by mutableStateOf<String?>(null)
    override var hintVisible by mutableStateOf(true)

    override var statusRes by mutableStateOf(R.string.status_ready)
    override var statusKind by mutableStateOf(StatusKind.IDLE)

    /** 加载序号：点「返回」后作废还在跑的搜索，避免旧结果写回界面。 */
    var loadEpoch by mutableStateOf(0)

    override val totalPages: Int get() = maxOf(1, (total + EXPLORE_PAGE_SIZE - 1) / EXPLORE_PAGE_SIZE)

    /**
     * 清空搜索结果、回到开屏态（设置页「清除缓存」用；等价于搜索区的返回按钮）。
     */
    fun reset() {
        loadEpoch += 1
        searching = false
        keywordInput = ""
        keyword = ""
        items = emptyList()
        total = 0
        uiPage = 1
        hintRes = R.string.explore_hint
        hintArg = null
        hintVisible = true
        searchedOnce = false
        statusRes = R.string.status_ready
        statusKind = StatusKind.IDLE
    }
}

/**
 * 探索页（首页）：按关键词搜索本子，网格展示封面与名称。
 *
 * 与桌面版一致的交互：
 * - 搜索方式（全部 / 作品 / 作者 / 标签 / 角色）、排序（最新 / 观看数 / 图片数 / 点赞数）、
 *   日期筛选（今天 / 本周 / 本月 / 全部），且**排序与日期筛选互斥**；
 * - 每页 18 条，上下各一个翻页工具；
 * - 首次搜索后由「居中态」切到「置顶态」，返回按钮清空搜索回到居中态。
 *
 * 主页保持 SFW：榜单不直接展示，只提供跳转按钮，进入对应榜单页才拉取内容。
 */
@Composable
fun ExploreScreen(
    state: ExploreState,
    onOpenAlbum: (String) -> Unit,
    onOpenRanking: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // 空闲预取：屏幕静止后往后预取最多 2 页（列表数据 + 封面），翻页时几乎瞬间出图
    IdleListPrefetch(
        currentPage = state.uiPage,
        totalPages = state.totalPages,
        ready = !state.searching && state.items.isNotEmpty(),
        fetchPage = { page ->
            when (val result = ApiBridge.exploreSearch(
                state.mode, state.keyword, page, state.sort, state.time
            )) {
                is ApiResult.Ok -> result.value.items
                is ApiResult.Err -> null
            }
        },
    )

    /** 回到开屏态：清空关键词与结果，作废还在跑的搜索。 */
    fun resetSearch() {
        state.loadEpoch += 1
        state.searching = false
        state.keywordInput = ""
        state.keyword = ""
        state.items = emptyList()
        state.total = 0
        state.uiPage = 1
        state.hintRes = R.string.explore_hint
        state.hintArg = null
        state.hintVisible = true
        state.searchedOnce = false
        state.statusRes = R.string.status_ready
        state.statusKind = StatusKind.IDLE
    }

    /** 加载指定界面页（沿用当前关键词 / 方式 / 排序 / 时间）。 */
    fun startLoad(page: Int) {
        if (state.searching) return
        val target = page.coerceIn(1, state.totalPages)
        state.searching = true
        state.loadEpoch += 1
        val epoch = state.loadEpoch
        state.items = emptyList()
        state.uiPage = target
        state.hintRes = R.string.status_searching
        state.hintArg = null
        state.hintVisible = true
        state.statusRes = R.string.status_searching
        state.statusKind = StatusKind.IDLE
        // 首次点击搜索即切到置顶布局，与搜索结果无关；此后不再变化
        if (!state.searchedOnce) state.searchedOnce = true

        scope.launch {
            val result = ApiBridge.exploreSearch(state.mode, state.keyword, target, state.sort, state.time)
            if (epoch != state.loadEpoch) return@launch   // 期间点了「返回」：本次作废
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

    /** 按输入框内容重新搜索（回到第 1 页）。 */
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

    /** 选排序：与日期筛选互斥（换排序即取消日期筛选）。 */
    fun selectSort(key: String) {
        state.sort = key
        state.time = TIME_ALL
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    /** 确认日期筛选：固定基于「最新」排序。 */
    fun applyTime(value: String) {
        state.time = value
        if (value != TIME_ALL) state.sort = SORT_LATEST
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    var showDateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (!state.searchedOnce) {
            // 居中态：欢迎语 + 搜索框 + 榜单跳转 + 指引整体居中
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.home_welcome),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SearchBar(state, onSearch = { doSearch() })
            Spacer(Modifier.height(12.dp))
            HomeActions(onOpenRanking = onOpenRanking)
            Spacer(Modifier.height(8.dp))
            SearchHintLine(state)
            StatusLine(state.statusRes, null, state.statusKind)
            Spacer(Modifier.weight(1f))
        } else {
            // 置顶态：返回（清空搜索）+ 搜索框 + 翻页/排序 + 网格
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { resetSearch() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.btn_back),
                    )
                }
            }
            SearchBar(state, onSearch = { doSearch() })
            SearchPagerRow(
                state = state,
                visible = state.keyword.isNotEmpty() && state.total > 0,
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
            SearchPagerRow(
                state = state,
                visible = state.keyword.isNotEmpty() && state.total > 0,
                showSort = false,
                onPrev = { startLoad(state.uiPage - 1) },
                onNext = { startLoad(state.uiPage + 1) },
                onSort = {},
                onOpenDate = {},
            )
            StatusLine(state.statusRes, null, state.statusKind)
        }
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

/** 主页的榜单跳转按钮（主页只做入口，不直接展示榜单内容，保持 SFW）。 */
@Composable
private fun HomeActions(onOpenRanking: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "day" to R.string.ranking_day,
            "week" to R.string.ranking_week,
            "month" to R.string.ranking_month,
        ).forEach { (kind, labelRes) ->
            OutlinedButton(
                onClick = { onOpenRanking(kind) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(labelRes), maxLines = 1)
            }
        }
    }
}