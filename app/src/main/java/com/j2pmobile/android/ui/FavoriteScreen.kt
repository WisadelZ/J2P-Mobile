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

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.FavoriteFolder
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import kotlinx.coroutines.launch

/** 「全部」收藏夹的 id（与 `core/favorite.py` 的 FOLDER_ALL 一致）。 */
private const val FOLDER_ALL = "0"

/**
 * 收藏列表页的可变状态（由 [AppShell] 持有，从本子详情页返回时页码与筛选仍保留）。
 *
 * 换账号时由 AppShell 调 [reset]，下次进入重新拉取。
 */
class FavoriteState {

    var uiPage by mutableStateOf(1)
    var total by mutableStateOf(0)
    var pageSize by mutableStateOf(20)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    var folders by mutableStateOf<List<FavoriteFolder>>(emptyList())

    var folderId by mutableStateOf(FOLDER_ALL)

    /** 当前筛选的收藏夹名；为空表示「全部」。 */
    var folderName by mutableStateOf("")

    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)

    var hintRes by mutableStateOf(R.string.favorite_loading)
    var hintArg by mutableStateOf<String?>(null)
    var hintVisible by mutableStateOf(true)

    var statusRes by mutableStateOf(R.string.status_ready)
    var statusArg by mutableStateOf<String?>(null)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    val totalPages: Int get() = maxOf(1, (total + pageSize - 1) / pageSize)

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    /** 回到「未加载」：换了账号或清了缓存时调用。 */
    fun reset() {
        uiPage = 1
        total = 0
        items = emptyList()
        folders = emptyList()
        folderId = FOLDER_ALL
        folderName = ""
        loading = false
        loaded = false
        hintRes = R.string.favorite_loading
        hintArg = null
        hintVisible = true
    }
}

/**
 * 收藏列表页：网格展示账号的收藏，可按收藏夹筛选。
 *
 * 与桌面版一致：上下各一个翻页工具、顶部与翻页同排一个收藏夹筛选菜单、每页 20 条
 * （收藏接口每页也是 20 条）。
 *
 * 安卓端按既定交互调整：**不做点击封面放大**，点封面或名称都进本子详情页。
 */
@Composable
fun FavoriteScreen(
    state: FavoriteState,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    /** 加载指定页；筛选与页码都由 state 持有。 */
    fun startLoad(page: Int) {
        if (state.loading) return
        val target = page.coerceIn(1, state.totalPages)
        state.loading = true
        state.uiPage = target
        state.items = emptyList()
        state.hintRes = R.string.favorite_loading
        state.hintArg = null
        state.hintVisible = true
        state.setStatus(R.string.favorite_loading, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.favoritePage(target, state.folderId)) {
                is ApiResult.Ok -> {
                    state.pageSize = result.value.pageSize
                    state.total = result.value.total
                    state.items = result.value.items
                    if (result.value.folders.isNotEmpty()) state.folders = result.value.folders
                    if (result.value.items.isEmpty()) {
                        state.hintRes = R.string.favorite_empty
                        state.hintArg = null
                        state.hintVisible = true
                        state.setStatus(R.string.status_favorite_loaded, StatusKind.IDLE)
                    } else {
                        state.hintVisible = false
                        state.loaded = true
                        state.setStatus(R.string.status_favorite_loaded, StatusKind.OK)
                    }
                }

                is ApiResult.Err -> {
                    state.hintRes = R.string.favorite_load_failed
                    state.hintArg = result.message
                    state.hintVisible = true
                    state.setStatus(R.string.status_favorite_failed, StatusKind.ERR)
                    LogBridge.append("ERR", result.message)
                }
            }
            state.loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (!state.loaded && !state.loading) startLoad(state.uiPage)
    }

    Column(modifier = modifier.fillMaxSize()) {
        FavoritePagerRow(
            state = state,
            showFolderMenu = true,
            onPrev = { startLoad(state.uiPage - 1) },
            onNext = { startLoad(state.uiPage + 1) },
            onSelectFolder = { id, name ->
                if (id != state.folderId) {
                    state.folderId = id
                    state.folderName = if (id == FOLDER_ALL) "" else name
                    state.total = 0
                    startLoad(1)
                }
            },
        )
        if (state.hintVisible) {
            val arg = state.hintArg
            Text(
                text = if (arg != null) stringResource(state.hintRes, arg) else stringResource(state.hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
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
        FavoritePagerRow(
            state = state,
            showFolderMenu = false,
            onPrev = { startLoad(state.uiPage - 1) },
            onNext = { startLoad(state.uiPage + 1) },
            onSelectFolder = { _, _ -> },
        )
        StatusLine(state.statusRes, state.statusArg, state.statusKind)
    }
}

/** 翻页工具：上方那一排额外带收藏夹筛选菜单（与桌面版一致）。 */
@Composable
private fun FavoritePagerRow(
    state: FavoriteState,
    showFolderMenu: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelectFolder: (String, String) -> Unit,
) {
    // 上方那排即使当前筛选没有结果也要留着，否则无法切回「全部」
    if (showFolderMenu) {
        if (state.total <= 0 && state.folders.isEmpty()) return
    } else if (state.total <= 0) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = !state.loading && state.uiPage > 1) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = stringResource(R.string.btn_prev_page),
            )
        }
        Text(
            text = stringResource(
                R.string.explore_page_info,
                state.uiPage,
                state.totalPages,
                state.total,
            ),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = !state.loading && state.uiPage < state.totalPages) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.btn_next_page),
            )
        }
        if (showFolderMenu) {
            FolderMenu(state, onSelectFolder)
        }
    }
}

/** 收藏夹筛选菜单：「全部」+ 账号里的收藏夹，当前项打勾。 */
@Composable
private fun FolderMenu(state: FavoriteState, onSelect: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.favorite_folder_all)
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.folderName.ifEmpty { allLabel },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                trailingIcon = { if (state.folderId == FOLDER_ALL) CheckIcon() },
                onClick = {
                    expanded = false
                    onSelect(FOLDER_ALL, allLabel)
                },
            )
            state.folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    trailingIcon = { if (state.folderId == folder.id) CheckIcon() },
                    onClick = {
                        expanded = false
                        onSelect(folder.id, folder.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(16.dp),
    )
}