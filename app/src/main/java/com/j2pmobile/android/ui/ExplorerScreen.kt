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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.LibraryEntry
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.PdfMeta
import com.j2pmobile.android.R
import com.j2pmobile.android.ReaderSource
import kotlinx.coroutines.launch

/** 搜索方式与排序字段（与 `core/library.py` 的常量一致）。 */
private val SEARCH_MODES = listOf("all", "name", "author", "tag", "id")
private val SORT_KEYS = listOf("name", "time", "size", "pages")
private const val DEFAULT_MODE = "all"
private const val DEFAULT_SORT = "name"

/** 各排序字段的默认方向（名称升序，其余降序），与 `library.SORT_DESC_DEFAULT` 一致。 */
private val SORT_DESC_DEFAULT = mapOf(
    "name" to false,
    "time" to true,
    "size" to true,
    "pages" to true,
)

/**
 * 资源管理器的可变状态（由 [AppShell] 持有）。
 *
 * 搜索词、搜索方式与排序都留在状态里，切到详情页再回来、或切走再回来都不会丢；
 * 勾选集合也保留，但每次重新扫描后会把已消失的路径剔除。
 */
class ExplorerState {

    var dir by mutableStateOf("")
    var entries by mutableStateOf<List<LibraryEntry>>(emptyList())
    var total by mutableStateOf(0)
    var shown by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)

    /** 本次结果是否读过 PDF 元数据（用于提示「正在读取元数据…」）。 */
    var readingMetadata by mutableStateOf(false)

    var keywordInput by mutableStateOf("")
    var keyword by mutableStateOf("")
    var mode by mutableStateOf(DEFAULT_MODE)
    var sortKey by mutableStateOf(DEFAULT_SORT)
    var sortDesc by mutableStateOf(false)

    /** 已勾选的路径（PDF 或图片文件夹）。 */
    var checked by mutableStateOf<Set<String>>(emptySet())

    var statusRes by mutableStateOf(R.string.status_ready)
    var statusArg by mutableStateOf<String?>(null)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    fun isDefaultSort(): Boolean = sortKey == DEFAULT_SORT && !sortDesc

    /** 勾选里的 PDF 路径（用于「元数据」按钮）。 */
    fun checkedPdfs(): List<String> = checked.filter { it.lowercase().endsWith(".pdf") }
}

/**
 * 资源管理器：按漫画归组管理下载目录里的 PDF 与图片文件夹。
 *
 * 与桌面版一致：搜索（全部 / 名称 / 作者 / 标签 / 本子 ID）、排序（名称 / 时间 / 大小 / 页数 +
 * 升降序 + 取消排序）、勾选后可打开 / 删除（移入回收站）、PDF 可看漫画元数据。
 *
 * 安卓端的适配：
 * - **元数据改为弹窗**：桌面版是右侧 250dp 侧栏，手机竖屏放不下，改成「元数据」按钮 + 对话框
 *   （勾选恰好一个 PDF 时可点），字段与桌面版完全一致；
 * - **浏览**：勾选一项后点「浏览」在应用内阅读（PDF 逐页取图、图片文件夹逐张显示），
 *   空的图片文件夹只提示不打开；在线浏览只在本子详情页提供；
 * - 四个操作按钮排成两行两列（手机上四联排会挤成一团）；
 * - 目录路径只读展示（固定为应用外部私有目录）。
 */
@Composable
fun ExplorerScreen(
    state: ExplorerState,
    onOpenReader: (ReaderSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var metaDialogOpen by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf<PdfMeta?>(null) }
    var metaError by remember { mutableStateOf<String?>(null) }
    var metaLoading by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }

    /** 扫描 + 过滤 + 排序（沿用当前关键词 / 方式 / 排序）。 */
    fun reload() {
        if (state.loading) return
        state.loading = true
        // 需要元数据的场景（按作者等搜索 / 按页数排序）会慢一些，先给个提示
        state.readingMetadata = needsMetadata(state)
        scope.launch {
            when (val result = ApiBridge.libraryList(
                state.keyword, state.mode, state.sortKey, state.sortDesc,
            )) {
                is ApiResult.Ok -> {
                    state.dir = result.value.dir
                    state.entries = result.value.entries
                    state.total = result.value.total
                    state.shown = result.value.shown
                    // 过滤后不可见的项必须取消勾选，否则「删除」会误伤看不见的项
                    val alive = result.value.entries
                        .flatMap { listOf(it.pdf, it.folder) }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    state.checked = state.checked.intersect(alive)
                    state.loaded = true
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.explorer_scan_failed, StatusKind.ERR, result.message
                )
            }
            state.loading = false
            state.readingMetadata = false
        }
    }

    // 首次进入扫一次；之后由刷新按钮 / 搜索 / 排序触发
    LaunchedEffect(Unit) {
        if (!state.loaded && !state.loading) reload()
    }

    /** 打开元数据弹窗（只对单个 PDF 可用）。 */
    fun openMeta() {
        val pdf = state.checkedPdfs().singleOrNull()
        if (pdf == null) {
            state.setStatus(R.string.explorer_need_selection, StatusKind.ERR)
            return
        }
        metaDialogOpen = true
        meta = null
        metaError = null
        metaLoading = true
        scope.launch {
            when (val result = ApiBridge.libraryPdfMeta(pdf)) {
                is ApiResult.Ok -> meta = result.value
                is ApiResult.Err -> metaError = result.message
            }
            metaLoading = false
        }
    }

    /** 浏览选中的 PDF 或图片文件夹；空的图片文件夹只提示，不打开阅读器。 */
    fun browse() {
        val path = state.checked.singleOrNull()
        if (path == null) {
            state.setStatus(R.string.explorer_need_selection, StatusKind.ERR)
            return
        }
        scope.launch {
            when (val result = ApiBridge.readerOpenLocal(path)) {
                is ApiResult.Ok -> {
                    val source = result.value
                    if (source == null) {
                        state.setStatus(R.string.explorer_no_images, StatusKind.ERR)
                    } else {
                        onOpenReader(source)
                    }
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.browse_failed, StatusKind.ERR, result.message
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---------------- 目录 + 刷新 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.explorer_dir, state.dir),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reload() }, enabled = !state.loading) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.btn_refresh),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---------------- 搜索栏 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeMenu(state, onPick = {
                state.mode = it
                reload()
            })
            TextField(
                value = state.keywordInput,
                onValueChange = { state.keywordInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.explorer_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    state.keyword = state.keywordInput.trim()
                    reload()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = {
                state.keywordInput = ""
                state.keyword = ""
                reload()
            }, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.btn_clear),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = {
                state.keyword = state.keywordInput.trim()
                reload()
            }, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.btn_search),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---------------- 排序 / 计数 ----------------
        ExplorerToolbar(state, onReload = { reload() })

        HorizontalDivider()

        // ---------------- 列表 ----------------
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.entries.isEmpty()) {
                Text(
                    // 区分「下载目录本来就是空的」与「只是没匹配上搜索条件」
                    text = if (state.keyword.isNotEmpty()) {
                        stringResource(R.string.explorer_no_match)
                    } else {
                        stringResource(R.string.explorer_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    state.entries.forEach { entry ->
                        item(key = entry.name) {
                            EntryGroup(
                                entry = entry,
                                checked = state.checked,
                                onToggle = { path ->
                                    state.checked = if (path in state.checked) {
                                        state.checked - path
                                    } else {
                                        state.checked + path
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 操作（两行两列，手机上四联排太挤） ----------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val paths = state.checked.toList()
                    if (paths.isEmpty()) {
                        state.setStatus(R.string.explorer_need_selection, StatusKind.ERR)
                        return@OutlinedButton
                    }
                    scope.launch {
                        var last: String? = null
                        paths.forEach { path ->
                            when (val result = ApiBridge.openPath(path)) {
                                is ApiResult.Ok -> Unit
                                is ApiResult.Err -> last = result.message
                            }
                        }
                        if (last == null) {
                            state.setStatus(
                                R.string.status_opened, StatusKind.OK,
                                paths.joinToString(", ") { it.substringAfterLast('/') },
                            )
                        } else {
                            state.setStatus(R.string.status_open_failed, StatusKind.ERR, last)
                        }
                    }
                },
                enabled = state.checked.size == 1,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_open_selected), maxLines = 1)
            }
            OutlinedButton(
                onClick = { browse() },
                enabled = state.checked.size == 1,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_browse),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_browse), maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openMeta() },
                enabled = state.checkedPdfs().size == 1,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.panel_meta_title), maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    if (state.checked.isEmpty()) {
                        state.setStatus(R.string.explorer_need_selection, StatusKind.ERR)
                    } else {
                        deleteDialogOpen = true
                    }
                },
                enabled = state.checked.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_delete_selected), maxLines = 1)
            }
        }

        if (state.readingMetadata) {
            Text(
                text = stringResource(R.string.explorer_reading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StatusLine(state.statusRes, state.statusArg, state.statusKind)
    }

    // ---------------- 元数据弹窗 ----------------
    if (metaDialogOpen) {
        AlertDialog(
            onDismissRequest = { metaDialogOpen = false },
            title = { Text(stringResource(R.string.panel_meta_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        metaLoading -> Text(
                            text = stringResource(R.string.explorer_reading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        metaError != null -> Text(
                            text = stringResource(R.string.panel_meta_failed, metaError!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        meta == null -> Text(
                            text = stringResource(R.string.panel_meta_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> {
                            val value = meta!!
                            MetaRow(stringResource(R.string.meta_title), value.title)
                            MetaRow(stringResource(R.string.meta_album_id), value.albumId)
                            MetaRow(stringResource(R.string.meta_author), value.author)
                            MetaRow(stringResource(R.string.meta_tags), value.tags)
                            MetaRow(stringResource(R.string.meta_pages), value.pages)
                            MetaRow(stringResource(R.string.meta_chapter), value.chapter)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { metaDialogOpen = false }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
        )
    }

    // ---------------- 删除确认 ----------------
    if (deleteDialogOpen) {
        val paths = state.checked.toList()
        AlertDialog(
            onDismissRequest = { deleteDialogOpen = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.delete_dialog_body,
                        paths.size,
                        paths.joinToString("\n") { it.substringAfterLast('/') },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialogOpen = false
                    scope.launch {
                        when (val result = ApiBridge.libraryDelete(paths)) {
                            is ApiResult.Ok -> {
                                state.setStatus(
                                    R.string.status_deleted, StatusKind.OK,
                                    result.value.toString(),
                                )
                                state.checked = emptySet()
                                reload()
                            }

                            is ApiResult.Err -> {
                                state.setStatus(
                                    R.string.status_delete_failed, StatusKind.ERR, result.message
                                )
                                LogBridge.append("ERR", result.message)
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.btn_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogOpen = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 是否需要读 PDF 元数据：按页数排序，或「按作者 / 标签 / ID / 全部」搜索且有关键词。 */
private fun needsMetadata(state: ExplorerState): Boolean {
    if (state.sortKey == "pages") return true
    return state.mode != "name" && state.keyword.isNotEmpty()
}

/** 搜索方式下拉（与探索页同款）。 */
@Composable
private fun ModeMenu(state: ExplorerState, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(modeLabelRes(state.mode)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SEARCH_MODES.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(modeLabelRes(key))) },
                    onClick = {
                        expanded = false
                        onPick(key)
                    },
                )
            }
        }
    }
}

/** 排序菜单 + 升降序 + 取消排序 + 计数。 */
@Composable
private fun ExplorerToolbar(state: ExplorerState, onReload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            ToolBox(onClick = { expanded = true }) {
                Text(
                    text = stringResource(sortLabelRes(state.sortKey)),
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
                SORT_KEYS.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabelRes(key))) },
                        trailingIcon = {
                            if (key == state.sortKey) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            if (key != state.sortKey) {
                                state.sortKey = key
                                // 换字段时套用该字段的默认方向（名称升序，其余降序）
                                state.sortDesc = SORT_DESC_DEFAULT[key] ?: false
                                onReload()
                            }
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = {
                state.sortDesc = !state.sortDesc
                onReload()
            },
            enabled = !state.loading,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (state.sortDesc) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward
                ),
                contentDescription = stringResource(
                    if (state.sortDesc) R.string.btn_order_to_asc else R.string.btn_order_to_desc
                ),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = {
                state.sortKey = DEFAULT_SORT
                state.sortDesc = false
                onReload()
            },
            enabled = !state.loading && !state.isDefaultSort(),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_restart),
                contentDescription = stringResource(R.string.btn_sort_reset),
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = if (state.keyword.isNotEmpty()) {
                stringResource(R.string.explorer_count_filtered, state.shown, state.total)
            } else {
                stringResource(R.string.explorer_count, state.total)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 工具控件的小方框（与探索页 / 任务页一致）。 */
@Composable
private fun ToolBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 一条扫描结果：同一本漫画可能同时有图片文件夹与 PDF。
 *
 * - 只有一种时直接平铺成一行（桌面版同样不再多套一层折叠菜单）；
 * - 两种都有时给出组标题 + 两条缩进的行（桌面版用折叠菜单，手机上一目了然更重要）。
 */
@Composable
private fun EntryGroup(
    entry: LibraryEntry,
    checked: Set<String>,
    onToggle: (String) -> Unit,
) {
    val pdfOnly = entry.pdf.isNotEmpty() && entry.folder.isEmpty()
    val folderOnly = entry.folder.isNotEmpty() && entry.pdf.isEmpty()
    if (pdfOnly || folderOnly) {
        val path = if (pdfOnly) entry.pdf else entry.folder
        EntryRow(
            path = path,
            title = entry.name,
            typeRes = if (pdfOnly) R.string.explorer_type_pdf else R.string.explorer_type_folder,
            iconRes = if (pdfOnly) R.drawable.ic_file_pdf else R.drawable.ic_folder,
            checked = path in checked,
            onToggle = { onToggle(path) },
            indent = false,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        )
        if (entry.folder.isNotEmpty()) {
            EntryRow(
                path = entry.folder,
                title = entry.folder.substringAfterLast('/'),
                typeRes = R.string.explorer_type_folder,
                iconRes = R.drawable.ic_folder,
                checked = entry.folder in checked,
                onToggle = { onToggle(entry.folder) },
                indent = true,
            )
        }
        if (entry.pdf.isNotEmpty()) {
            EntryRow(
                path = entry.pdf,
                title = entry.pdf.substringAfterLast('/'),
                typeRes = R.string.explorer_type_pdf,
                iconRes = R.drawable.ic_file_pdf,
                checked = entry.pdf in checked,
                onToggle = { onToggle(entry.pdf) },
                indent = true,
            )
        }
    }
}

/** 一条可勾选的行：复选框 + 文件名 + 类型。整行可点，点哪都能切勾选。 */
@Composable
private fun EntryRow(
    path: String,
    title: String,
    typeRes: Int,
    iconRes: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    indent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(start = if (indent) 18.dp else 0.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(typeRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 元数据弹窗里的一行「标签 + 值」（可长按选中复制）。 */
@Composable
private fun MetaRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun modeLabelRes(key: String): Int = when (key) {
    "name" -> R.string.explorer_mode_name
    "author" -> R.string.explorer_mode_author
    "tag" -> R.string.explorer_mode_tag
    "id" -> R.string.explorer_mode_id
    else -> R.string.explorer_mode_all
}

private fun sortLabelRes(key: String): Int = when (key) {
    "time" -> R.string.explorer_sort_time
    "size" -> R.string.explorer_sort_size
    "pages" -> R.string.explorer_sort_pages
    else -> R.string.explorer_sort_name
}