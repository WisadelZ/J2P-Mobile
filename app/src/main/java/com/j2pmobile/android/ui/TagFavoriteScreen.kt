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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.TagFolder
import com.j2pmobile.android.TagItem
import kotlinx.coroutines.launch

/** 「全部」收藏夹的 id（与 `core/collection.py` 的 FOLDER_ALL 一致）。 */
private const val TAG_FOLDER_ALL = "0"

/**
 * tag 收藏完整页的可变状态（由 [AppShell] 持有）。
 */
class TagFavoriteState {
    var uiPage by mutableStateOf(1)
    var total by mutableStateOf(0)
    var pageSize by mutableStateOf(10)
    var items by mutableStateOf<List<TagItem>>(emptyList())
    var folders by mutableStateOf<List<TagFolder>>(emptyList())

    var folderId by mutableStateOf(TAG_FOLDER_ALL)

    /** 当前筛选的收藏夹名；为空表示「全部」。 */
    var folderName by mutableStateOf("")

    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)

    var hintRes by mutableStateOf(R.string.tag_favorite_loading)
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

    /** 回到「未加载」：清缓存或从详情页操作后回来时调用。 */
    fun reset() {
        uiPage = 1
        total = 0
        items = emptyList()
        folders = emptyList()
        folderId = TAG_FOLDER_ALL
        folderName = ""
        loading = false
        loaded = false
        hintRes = R.string.tag_favorite_loading
        hintArg = null
        hintVisible = true
    }
}

/**
 * tag 收藏完整页：每行 1 个贯穿整行的 tag，右侧三个无边框按钮（移动收藏夹 / 跳转搜索 / 删除）。
 *
 * 翻页工具与收藏夹筛选同上；每页 10 个，最多记录 100 个（超出由 Python 侧覆盖最早的）。
 */
@Composable
fun TagFavoriteScreen(
    state: TagFavoriteState,
    onJumpSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun startLoad(page: Int) {
        if (state.loading) return
        val target = page.coerceIn(1, state.totalPages)
        state.loading = true
        state.uiPage = target
        state.items = emptyList()
        state.hintRes = R.string.tag_favorite_loading
        state.hintArg = null
        state.hintVisible = true
        state.setStatus(R.string.tag_favorite_loading, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.tagPage(target, state.folderId)) {
                is ApiResult.Ok -> {
                    state.pageSize = result.value.pageSize
                    state.total = result.value.total
                    state.items = result.value.items
                    state.folders = result.value.folders
                    if (result.value.items.isEmpty()) {
                        state.hintRes = R.string.tag_favorite_empty
                        state.hintArg = null
                        state.hintVisible = true
                        state.setStatus(R.string.status_tag_loaded, StatusKind.IDLE)
                    } else {
                        state.hintVisible = false
                        state.loaded = true
                        state.setStatus(R.string.status_tag_loaded, StatusKind.OK)
                    }
                }

                is ApiResult.Err -> {
                    state.hintRes = R.string.tag_favorite_load_failed
                    state.hintArg = result.message
                    state.hintVisible = true
                    state.setStatus(R.string.status_tag_failed, StatusKind.ERR)
                    LogBridge.append("ERR", result.message)
                }
            }
            state.loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (!state.loaded && !state.loading) startLoad(state.uiPage)
    }

    var moveTarget by remember { mutableStateOf<TagItem?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部：翻页 + 收藏夹筛选 + 新建收藏夹
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { startLoad(state.uiPage - 1) }, enabled = !state.loading && state.uiPage > 1) {
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
            IconButton(onClick = { startLoad(state.uiPage + 1) }, enabled = !state.loading && state.uiPage < state.totalPages) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.btn_next_page),
                )
            }
            TagFolderMenu(
                state = state,
                onSelect = { id, name ->
                    if (id != state.folderId) {
                        state.folderId = id
                        state.folderName = if (id == TAG_FOLDER_ALL) "" else name
                        state.total = 0
                        startLoad(1)
                    }
                },
            )
            IconButton(onClick = { newFolderOpen = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.tag_new_folder),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
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
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.name }) { tag ->
                TagRow(
                    tag = tag,
                    onJumpSearch = { onJumpSearch(tag.name) },
                    onMove = { moveTarget = tag },
                    onDelete = {
                        scope.launch {
                            when (val result = ApiBridge.tagRemove(tag.name)) {
                                is ApiResult.Ok -> {
                                    state.setStatus(R.string.status_tag_removed, StatusKind.OK, tag.name)
                                    startLoad(state.uiPage)
                                }

                                is ApiResult.Err -> state.setStatus(
                                    R.string.status_tag_remove_failed, StatusKind.ERR, result.message
                                )
                            }
                        }
                    },
                )
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        )
        StatusLine(state.statusRes, state.statusArg, state.statusKind)
    }

    // 移动收藏夹：弹窗选择目标收藏夹
    moveTarget?.let { target ->
        val allLabel = stringResource(R.string.favorite_folder_all)
        var selected by remember(target) { mutableStateOf(target.folderId) }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(stringResource(R.string.tag_move_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(TagFolder(TAG_FOLDER_ALL, allLabel, true)) + state.folders
                    options.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = folder.id }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == folder.id, onClick = { selected = folder.id })
                            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetFolder = selected
                    moveTarget = null
                    scope.launch {
                        when (val result = ApiBridge.tagMove(target.name, targetFolder)) {
                            is ApiResult.Ok -> {
                                state.setStatus(R.string.status_tag_moved, StatusKind.OK, target.name)
                                startLoad(state.uiPage)
                            }

                            is ApiResult.Err -> state.setStatus(
                                R.string.status_tag_move_failed, StatusKind.ERR, result.message
                            )
                        }
                    }
                }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    // 新建收藏夹
    if (newFolderOpen) {
        AlertDialog(
            onDismissRequest = { newFolderOpen = false },
            title = { Text(stringResource(R.string.tag_new_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tag_folder_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.trim().isNotEmpty(),
                    onClick = {
                        val name = newFolderName.trim()
                        newFolderOpen = false
                        newFolderName = ""
                        scope.launch {
                            when (val result = ApiBridge.tagFolderCreate(name)) {
                                is ApiResult.Ok ->
                                    state.setStatus(R.string.status_tag_folder_created, StatusKind.OK, name)

                                is ApiResult.Err -> state.setStatus(
                                    R.string.status_tag_folder_failed, StatusKind.ERR, result.message
                                )
                            }
                            startLoad(1)
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newFolderOpen = false
                    newFolderName = ""
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 一行 tag：贯穿整行的文本框（点它跳「全部」搜索）+ 右侧三个无边框按钮。 */
@Composable
private fun TagRow(
    tag: TagItem,
    onJumpSearch: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(onClick = onJumpSearch)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMove) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = stringResource(R.string.btn_move_folder),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onJumpSearch) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.btn_search),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.btn_delete_tag),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 收藏夹筛选菜单：「全部」+ 本地收藏夹，当前项打勾。 */
@Composable
private fun TagFolderMenu(state: TagFavoriteState, onSelect: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.favorite_folder_all)
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
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
                trailingIcon = { if (state.folderId == TAG_FOLDER_ALL) TagCheckIcon() },
                onClick = {
                    expanded = false
                    onSelect(TAG_FOLDER_ALL, allLabel)
                },
            )
            state.folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    trailingIcon = { if (state.folderId == folder.id) TagCheckIcon() },
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
private fun TagCheckIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(16.dp),
    )
}