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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.ImportedPdf
import com.j2pmobile.android.LibraryEntry
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.PdfMeta
import com.j2pmobile.android.R
import com.j2pmobile.android.ReaderSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

/** 资源管理器的两种视图（按钮图标展示的就是当前这一种）。 */
private const val VIEW_LIST = "list"
private const val VIEW_GRID = "grid"

/** 类型筛选：全部 / 仅 PDF / 仅文件夹。 */
private const val FILTER_ALL = "all"
private const val FILTER_PDF = "pdf"
private const val FILTER_FOLDER = "folder"

/** 方格封面：目标宽度与可当封面的图片扩展名。 */
private const val COVER_TARGET_PX = 240
private val COVER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

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

    /** 列表 / 方格视图。 */
    var viewMode by mutableStateOf(VIEW_LIST)

    /** 全部 / 仅 PDF / 仅文件夹。 */
    var typeFilter by mutableStateOf(FILTER_ALL)

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
    val context = LocalContext.current

    var metaDialogOpen by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf<PdfMeta?>(null) }
    var metaError by remember { mutableStateOf<String?>(null) }
    var metaLoading by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

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

    /**
     * 导入外部 PDF：系统文件选择器选一个 PDF → Kotlin 先把内容写进应用缓存 →
     * Python 复制进下载目录、分配新的八位本子 ID 并写入 PDF 元数据。
     */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        importing = true
        state.setStatus(R.string.status_importing, StatusKind.IDLE)
        scope.launch {
            when (val result = copyAndImport(context, uri)) {
                is ApiResult.Ok -> {
                    // 状态行只收一个参数，这里先把两段的文案拼好再交给它
                    state.setStatus(
                        R.string.status_plain,
                        StatusKind.OK,
                        context.getString(
                            R.string.status_import_ok, result.value.name, result.value.albumId
                        ),
                    )
                    reload()
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_pdf_import_failed, StatusKind.ERR, result.message
                )
            }
            importing = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---------------- 目录 + 导入 / 刷新 ----------------
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
            // 导入 PDF：选一个外部 PDF，复制进下载目录并分配新的本子 ID
            IconButton(
                onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                enabled = !importing,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.explorer_import_pdf),
                    modifier = Modifier.size(20.dp),
                )
            }
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

        // ---------------- 列表 / 方格 ----------------
        val visible = remember(state.entries, state.typeFilter) {
            when (state.typeFilter) {
                FILTER_PDF -> state.entries.filter { it.pdf.isNotEmpty() }
                FILTER_FOLDER -> state.entries.filter { it.folder.isNotEmpty() }
                else -> state.entries
            }
        }
        val toggle: (String) -> Unit = { path ->
            state.checked = if (path in state.checked) state.checked - path
            else state.checked + path
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                Text(
                    // 区分「下载目录本来就是空的」与「只是没匹配上搜索/筛选条件」
                    text = if (state.keyword.isNotEmpty() || state.typeFilter != FILTER_ALL) {
                        stringResource(R.string.explorer_no_match)
                    } else {
                        stringResource(R.string.explorer_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else if (state.viewMode == VIEW_GRID) {
                // 方格视图：同一本漫画的图片文件夹与 PDF 各占一格、并排出现
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    visible.forEach { entry ->
                        if (entry.folder.isNotEmpty() && showsKind(state.typeFilter, folder = true)) {
                            item(key = entry.folder) {
                                GridCell(
                                    path = entry.folder,
                                    title = entry.name,
                                    typeRes = R.string.explorer_type_folder,
                                    isFolder = true,
                                    selected = entry.folder in state.checked,
                                    onToggle = { toggle(entry.folder) },
                                )
                            }
                        }
                        if (entry.pdf.isNotEmpty() && showsKind(state.typeFilter, folder = false)) {
                            item(key = entry.pdf) {
                                GridCell(
                                    path = entry.pdf,
                                    title = entry.name,
                                    typeRes = R.string.explorer_type_pdf,
                                    isFolder = false,
                                    selected = entry.pdf in state.checked,
                                    onToggle = { toggle(entry.pdf) },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    visible.forEach { entry ->
                        item(key = entry.name) {
                            EntryGroup(
                                entry = entry,
                                filter = state.typeFilter,
                                checked = state.checked,
                                onToggle = toggle,
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
        // 视图切换：图标展示当前视图（列表 / 方格），点一下切换
        IconButton(
            onClick = {
                state.viewMode = if (state.viewMode == VIEW_GRID) VIEW_LIST else VIEW_GRID
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (state.viewMode == VIEW_GRID) R.drawable.ic_grid_view
                    else R.drawable.ic_list
                ),
                contentDescription = stringResource(
                    if (state.viewMode == VIEW_GRID) R.string.explorer_view_grid
                    else R.string.explorer_view_list
                ),
                modifier = Modifier.size(18.dp),
            )
        }
        // 类型筛选：全部 → 仅 PDF → 仅文件夹 → 全部
        IconButton(
            onClick = {
                state.typeFilter = when (state.typeFilter) {
                    FILTER_ALL -> FILTER_PDF
                    FILTER_PDF -> FILTER_FOLDER
                    else -> FILTER_ALL
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(filterIconRes(state.typeFilter)),
                contentDescription = stringResource(filterLabelRes(state.typeFilter)),
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

/** 类型筛选按钮的图标（图标即当前状态）。 */
private fun filterIconRes(filter: String): Int = when (filter) {
    FILTER_PDF -> R.drawable.ic_file_pdf
    FILTER_FOLDER -> R.drawable.ic_folder
    else -> R.drawable.ic_apps
}

/** 类型筛选按钮的提示文案。 */
private fun filterLabelRes(filter: String): Int = when (filter) {
    FILTER_PDF -> R.string.explorer_filter_pdf
    FILTER_FOLDER -> R.string.explorer_filter_folder
    else -> R.string.explorer_filter_all
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
 * 方格视图的一个格子：封面 + 标题 + 左上角「文件夹 / PDF」角标。
 *
 * 点一下即选中 / 取消（高亮边框），与列表视图的勾选框等价。
 * 封面直接取本地内容：图片文件夹用第一张图，PDF 用系统 `PdfRenderer` 渲染第一页。
 */
@Composable
private fun GridCell(
    path: String,
    title: String,
    typeRes: Int,
    isFolder: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    var cover by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (cover == null) {
            cover = withContext(Dispatchers.IO) { loadExplorerCover(path, isFolder) }
        }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onToggle)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val image = cover
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (isFolder) R.drawable.ic_folder else R.drawable.ic_file_pdf
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
            // 左上角角标：标出这一格是「文件夹」还是「PDF」
            Text(
                text = stringResource(typeRes),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 取封面：图片文件夹用第一张图；PDF 用系统 PdfRenderer 渲染第一页。 */
private fun loadExplorerCover(path: String, isFolder: Boolean): ImageBitmap? = try {
    val file = File(path)
    val bitmap = if (isFolder) {
        val first = file.listFiles()
            ?.sortedBy { it.name }
            ?.firstOrNull { it.isFile && it.extension.lowercase() in COVER_IMAGE_EXTS }
        first?.let { decodeSampledBitmap(it.readBytes(), COVER_TARGET_PX) }
    } else {
        renderPdfFirstPage(file, COVER_TARGET_PX)
    }
    bitmap?.asImageBitmap()
} catch (_: Throwable) {
    null
}

private fun renderPdfFirstPage(file: File, targetPx: Int): Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) {
                null
            } else {
                renderer.openPage(0).use { page ->
                    val width = targetPx
                    val height = maxOf(1, targetPx * page.height / page.width)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }
} catch (_: Throwable) {
    null
}

/** 按目标宽度抽样解码（封面只当缩略图用，不必原尺寸）。 */
private fun decodeSampledBitmap(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
    filter: String,
    checked: Set<String>,
    onToggle: (String) -> Unit,
) {
    val showFolder = entry.folder.isNotEmpty() && showsKind(filter, folder = true)
    val showPdf = entry.pdf.isNotEmpty() && showsKind(filter, folder = false)
    if (showFolder != showPdf) {
        // 只剩一种格式时直接平铺，不再多套一层标题
        val path = if (showFolder) entry.folder else entry.pdf
        EntryRow(
            path = path,
            title = entry.name,
            typeRes = if (showFolder) R.string.explorer_type_folder else R.string.explorer_type_pdf,
            iconRes = if (showFolder) R.drawable.ic_folder else R.drawable.ic_file_pdf,
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
        if (showFolder) {
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
        if (showPdf) {
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

/**
 * 当前筛选下是否显示某一类格式。
 *
 * 一本漫画常常 PDF 与图片文件夹都在，「仅 PDF / 仅文件夹」必须落到**单个条目**上，
 * 只按「整本有没有该格式」过滤的话，两种筛选看起来会和「全部」一模一样。
 */
private fun showsKind(filter: String, folder: Boolean): Boolean = when (filter) {
    FILTER_PDF -> !folder
    FILTER_FOLDER -> folder
    else -> true
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

/**
 * 把所选 SAF Uri 的内容写到应用缓存，再交给 Python 复制进下载目录并写元数据。
 *
 * 为什么不直接把 Uri 交给 Python：Python 打不开 `content://`，而下载目录是应用私有外部目录，
 * 在 Kotlin 侧用缓存落地一次再由 Python 复制，比让两端都去处理 SAF 简单得多。
 * 临时文件无论如何都会在结束时删掉。
 */
private suspend fun copyAndImport(context: Context, uri: Uri): ApiResult<ImportedPdf> =
    withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ApiResult.Err("无法读取所选文件")
            ApiBridge.importPdf(temp.absolutePath, queryDisplayName(context, uri))
        } catch (error: Throwable) {
            ApiResult.Err(error.message ?: error.toString())
        } finally {
            temp.delete()
        }
    }

/** 从 SAF Uri 取显示文件名（取不到就给一个兜底名，Python 侧还会再补 .pdf 后缀）。 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = ""
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index).orEmpty()
        }
    } catch (_: Throwable) {
        name = ""
    }
    return name.ifEmpty { "imported.pdf" }
}