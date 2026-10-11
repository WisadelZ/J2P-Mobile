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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import kotlinx.coroutines.launch

/** 榜单类型（与 `core.explore.RANKING_KINDS` 一致）。 */
private val RANKING_KINDS = listOf("day", "week", "month")

/**
 * 榜单页的可变状态（由 [AppShell] 持有）。
 *
 * 主页只放跳转按钮（保持 SFW），榜单内容在本页才拉取。
 */
class RankingState : SearchUiState {
    override var keywordInput by mutableStateOf("")
    override var keyword by mutableStateOf("")
    override var mode by mutableStateOf(MODE_ALL)
    override var sort by mutableStateOf(SORT_LATEST)
    override var time by mutableStateOf(TIME_ALL)
    override var uiPage by mutableStateOf(1)
    override var total by mutableStateOf(0)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    override var searching by mutableStateOf(false)
    override var hintRes by mutableStateOf(R.string.ranking_loading)
    override var hintArg by mutableStateOf<String?>(null)
    override var hintVisible by mutableStateOf(true)
    override var statusRes by mutableStateOf(R.string.status_ready)
    override var statusKind by mutableStateOf(StatusKind.IDLE)

    var kind by mutableStateOf("day")
    var openSeq by mutableStateOf(0)

    private var loadEpoch by mutableStateOf(0)

    override val totalPages: Int get() = maxOf(1, (total + EXPLORE_PAGE_SIZE - 1) / EXPLORE_PAGE_SIZE)

    /** 打开榜单页：指定榜单类型并请求重新加载。 */
    fun open(kindValue: String) {
        loadEpoch += 1
        searching = false
        kind = kindValue
        items = emptyList()
        total = 0
        uiPage = 1
        hintRes = R.string.ranking_loading
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

/** 榜单页：日榜 / 周榜 / 月榜，网格展示封面与名称。 */
@Composable
fun RankingScreen(
    state: RankingState,
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
        state.hintRes = R.string.ranking_loading
        state.hintArg = null
        state.hintVisible = true
        state.statusRes = R.string.ranking_loading
        state.statusKind = StatusKind.IDLE

        scope.launch {
            val result = ApiBridge.ranking(state.kind, target)
            if (epoch != state.epoch()) return@launch
            state.searching = false
            when (result) {
                is ApiResult.Err -> {
                    state.hintRes = R.string.ranking_failed
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
                        state.hintRes = R.string.ranking_empty
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

    fun selectKind(kind: String) {
        if (kind == state.kind) return
        state.kind = kind
        state.total = 0
        state.uiPage = 1
        startLoad(1)
    }

    LaunchedEffect(state.openSeq) { startLoad(1) }

    val pagerVisible = state.total > 0

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RANKING_KINDS.forEach { kind ->
                RankingChoice(
                    text = stringResource(rankingLabelRes(kind)),
                    selected = state.kind == kind,
                    onClick = { selectKind(kind) },
                    modifier = Modifier.weight(1f),
                )
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

/** 榜单类型单选：选中即实心，未选中为描边。 */
@Composable
private fun RankingChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text, maxLines = 1) }
    }
}

private fun rankingLabelRes(kind: String): Int = when (kind) {
    "week" -> R.string.ranking_week
    "month" -> R.string.ranking_month
    else -> R.string.ranking_day
}