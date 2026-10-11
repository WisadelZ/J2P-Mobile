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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumDetail
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.CommentItem
import com.j2pmobile.android.FavoriteFolder
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.PreviewPageMeta
import com.j2pmobile.android.R
import com.j2pmobile.android.ReaderSource
import com.j2pmobile.android.TagFolder
import com.j2pmobile.android.ui.components.CoverImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 左侧封面尺寸（3:4，与站点 `_3x4` 封面一致）。 */
private val COVER_WIDTH = 110.dp
private val COVER_HEIGHT = 147.dp

/** 预览缩略图的显示宽度、高度上限，以及解码时用的目标像素宽（够 3x 密度用）。 */
private val THUMB_WIDTH = 120.dp
private val THUMB_MAX_HEIGHT = 200.dp
private const val THUMB_TARGET_PX = 400

/** 放大查看时的暗底（与桌面版 0.78 的黑底同义）。 */
private val ZOOM_SCRIM = Color(0xCC000000)

/**
 * 本子详情页：大封面 + 本子信息 + 操作按钮 + 「前几页」预览。
 *
 * 入口来自探索页（点封面或名称）；信息与预览都按 ID 现取。
 *
 * 与桌面版一致：进页面自动取前几页预览，点预览图放大、放大后可在已取到的几张之间翻页；
 * 「收藏」未收藏时弹窗选收藏夹、已收藏时点一下取消；「下载」直接入队，「加入下载列表」
 * 把 ID 追加到下载页的输入框。
 *
 * 安卓端差异：
 * - **预览图在手机屏幕上改为横向滚动**排布（桌面版是一排 5 张按窗口宽度铺开）；
 * - 「浏览」走在线浏览（按需取页、逐页解密），打开期间按钮禁用并提示「正在打开浏览…」；
 * - 预览字节逐页取、缩略图按目标宽度降采样解码，放大时才解全尺寸，避免一次占几十 MB。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    albumId: String,
    queue: QueueState,
    saveDownloadConf: suspend () -> Unit,
    onAddToDownloadList: (String) -> Unit,
    /** 标签 / 作者 / 角色点击：跳「对应类型」的搜索（mode, keyword）。 */
    onOpenTypedSearch: (String, String) -> Unit,
    onOpenReader: (ReaderSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<AlbumDetail?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var favorited by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    var likesText by remember { mutableStateOf("") }
    var likeLoading by remember { mutableStateOf(false) }
    var browseLoading by remember { mutableStateOf(false) }

    var metas by remember { mutableStateOf<List<PreviewPageMeta>>(emptyList()) }
    var previewBytes by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var previewThumbs by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(true) }
    var previewError by remember { mutableStateOf<String?>(null) }
    /** 「预览前五页」开关：关掉时详情页不拉预览、也不显示预览条。 */
    var previewEnabled by remember { mutableStateOf(true) }

    /** 放大查看的当前页索引；-1 表示未打开。 */
    var zoomIndex by remember { mutableStateOf(-1) }

    var folderDialogOpen by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
    var foldersLoading by remember { mutableStateOf(false) }
    var foldersError by remember { mutableStateOf<String?>(null) }
    var selectedFolder by remember { mutableStateOf("0") }

    // ---- tag 收藏（长按标签弹出：与「本子收藏」同款选收藏夹 / 新建收藏夹交互）
    var collectTag by remember { mutableStateOf<String?>(null) }
    var tagFolders by remember { mutableStateOf<List<TagFolder>>(emptyList()) }
    var tagFolderLoading by remember { mutableStateOf(false) }
    var tagFolderError by remember { mutableStateOf<String?>(null) }
    var tagFolderSel by remember { mutableStateOf("") }
    var newTagFolderName by remember { mutableStateOf("") }

    // ---- 网站评论
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentPage by remember { mutableStateOf(1) }
    var commentTotal by remember { mutableStateOf(0) }
    var commentCount by remember { mutableStateOf(0) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentError by remember { mutableStateOf<String?>(null) }
    var commentInput by remember { mutableStateOf("") }
    var commentPosting by remember { mutableStateOf(false) }

    var statusRes by remember { mutableStateOf(R.string.status_ready) }
    var statusArg by remember { mutableStateOf<String?>(null) }
    var statusKind by remember { mutableStateOf(StatusKind.IDLE) }

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    // 详情 + 预览：进页面各取一次
    LaunchedEffect(albumId) {
        loading = true
        loadError = null
        // 一进详情页就记一条浏览历史（先只带 ID，标题 / 作者拿到后再补写一次）：
        // 取详情要联网，等它回来才记的话，用户会觉得「看了半天才计入」。
        ApiBridge.historyAdd(albumId)
        when (val result = ApiBridge.albumDetail(albumId)) {
            is ApiResult.Ok -> {
                detail = result.value
                favorited = result.value.favorited
                liked = result.value.liked
                likesText = result.value.likes
                commentCount = result.value.commentCount
                // 补全标题与作者（本地收藏文件；不按账号隔离）
                ApiBridge.historyAdd(albumId, result.value.title, result.value.author)
            }

            is ApiResult.Err -> loadError = result.message
        }
        loading = false

        // 「预览前五页」开关：关掉就整段跳过（详情已经加载好了）
        previewEnabled = ApiBridge.loadConfig()
            .optJSONObject("config")?.optJSONObject("app")
            ?.optBoolean("preview_pages", true) ?: true
        if (!previewEnabled) {
            previewLoading = false
            return@LaunchedEffect
        }

        previewLoading = true
        previewError = null
        metas = emptyList()
        previewBytes = emptyList()
        previewThumbs = emptyList()
        when (val result = ApiBridge.albumPreview(albumId)) {
            is ApiResult.Ok -> {
                metas = result.value
                // 逐页取字节：单页失败就当作整段预览失败（与桌面版一次取回再判断的语义一致）
                for (index in result.value.indices) {
                    when (val page = ApiBridge.albumPreviewPage(albumId, index)) {
                        is ApiResult.Ok -> {
                            val thumb = withContext(Dispatchers.IO) {
                                decodeSampled(page.value.data, THUMB_TARGET_PX)
                            }
                            previewBytes = previewBytes + page.value.data
                            previewThumbs = previewThumbs + thumb
                        }

                        is ApiResult.Err -> {
                            previewError = page.message
                            metas = emptyList()
                            previewBytes = emptyList()
                            previewThumbs = emptyList()
                            break
                        }
                    }
                }
            }

            is ApiResult.Err -> previewError = result.message
        }
        previewLoading = false
    }

    /** 加载评论：首页替换、后续页追加。 */
    fun loadComments(page: Int, append: Boolean) {
        if (commentsLoading) return
        commentsLoading = true
        commentError = null
        scope.launch {
            when (val result = ApiBridge.albumComments(albumId, page)) {
                is ApiResult.Ok -> {
                    commentPage = result.value.page
                    commentTotal = result.value.total
                    if (result.value.commentCount > 0) commentCount = result.value.commentCount
                    comments = if (append) comments + result.value.items else result.value.items
                }

                is ApiResult.Err -> commentError = result.message
            }
            commentsLoading = false
        }
    }

    // 评论与详情并行拉取：评论慢或失败都不影响详情展示
    LaunchedEffect(albumId) { loadComments(1, append = false) }

    /** 点赞 / 取消点赞：以服务端返回的最终状态为准。 */
    fun toggleLike() {
        if (likeLoading) return
        likeLoading = true
        scope.launch {
            when (val result = ApiBridge.albumLike(albumId)) {
                is ApiResult.Ok -> {
                    liked = result.value.liked
                    if (result.value.likes.isNotEmpty()) likesText = result.value.likes
                    setStatus(
                        if (result.value.liked) R.string.status_like_ok else R.string.status_like_removed,
                        StatusKind.OK,
                    )
                }

                is ApiResult.Err -> setStatus(R.string.status_like_failed, StatusKind.ERR, result.message)
            }
            likeLoading = false
        }
    }

    /** 长按标签：打开「收藏标签」弹窗并拉取本地收藏夹。 */
    fun openTagCollect(tag: String) {
        collectTag = tag
        newTagFolderName = ""
        tagFolderLoading = true
        tagFolderError = null
        tagFolders = emptyList()
        tagFolderSel = ""
        scope.launch {
            when (val result = ApiBridge.tagFolders()) {
                is ApiResult.Ok -> {
                    tagFolders = result.value
                    tagFolderSel = result.value.firstOrNull { it.isDefault }?.id
                        ?: result.value.firstOrNull()?.id.orEmpty()
                }

                is ApiResult.Err -> tagFolderError = result.message
            }
            tagFolderLoading = false
        }
    }

    /** 发表评论：未登录或服务端拒绝时原样回传提示。 */
    fun postComment() {
        val content = commentInput.trim()
        if (content.isEmpty() || commentPosting) return
        commentPosting = true
        scope.launch {
            when (val result = ApiBridge.albumCommentPost(albumId, content)) {
                is ApiResult.Ok -> {
                    if (result.value.success) {
                        commentInput = ""
                        setStatus(R.string.status_comment_posted, StatusKind.OK)
                        loadComments(1, append = false)
                    } else {
                        setStatus(R.string.status_comment_failed, StatusKind.ERR, result.value.msg)
                    }
                }

                is ApiResult.Err -> setStatus(
                    R.string.status_comment_failed, StatusKind.ERR, result.message
                )
            }
            commentPosting = false
        }
    }

    // 放大层开着时，返回键先关它
    BackHandler(enabled = zoomIndex >= 0) { zoomIndex = -1 }

    /** 收藏夹弹窗：先打开（带加载态），再异步取列表。 */
    fun openFolderDialog() {
        folderDialogOpen = true
        foldersLoading = true
        foldersError = null
        folders = emptyList()
        selectedFolder = "0"
        scope.launch {
            when (val result = ApiBridge.favoriteFolders()) {
                is ApiResult.Ok -> folders = result.value
                is ApiResult.Err -> foldersError = result.message
            }
            foldersLoading = false
        }
    }

    /** 收藏按钮：已收藏＝取消，未收藏＝选收藏夹加入。 */
    fun toggleFavorite() {
        if (!favorited) {
            openFolderDialog()
            return
        }
        scope.launch {
            setStatus(R.string.status_favorite_removing, StatusKind.IDLE)
            when (val result = ApiBridge.favoriteRemove(albumId)) {
                is ApiResult.Ok -> {
                    favorited = false
                    setStatus(R.string.status_favorite_removed, StatusKind.OK)
                }

                is ApiResult.Err -> setStatus(
                    R.string.status_favorite_remove_failed, StatusKind.ERR, result.message
                )
            }
        }
    }

    /**
     * 在线浏览：先取本子信息（要拿到总页数），就绪后再开阅读器。
     *
     * 取页全在阅读器里按需进行，这里只负责把会话建起来。
     */
    fun browseOnline() {
        if (browseLoading) return
        browseLoading = true
        scope.launch {
            setStatus(R.string.status_browse_opening, StatusKind.IDLE)
            when (val result = ApiBridge.readerOpenOnline(albumId)) {
                is ApiResult.Ok -> onOpenReader(result.value)
                is ApiResult.Err -> setStatus(
                    R.string.browse_open_failed, StatusKind.ERR, result.message
                )
            }
            browseLoading = false
        }
    }

    /** 直接下载：先落盘下载选项，再入队。 */
    fun downloadNow() {
        scope.launch {
            saveDownloadConf()
            when (val result = ApiBridge.queueEnqueue(listOf(albumId))) {
                is ApiResult.Ok -> {
                    if (result.value.added.isNotEmpty()) {
                        setStatus(
                            R.string.status_task_enqueued, StatusKind.OK,
                            result.value.added.size.toString(),
                        )
                    } else {
                        setStatus(
                            R.string.status_task_duplicated, StatusKind.ERR,
                            result.value.duplicated.joinToString(", "),
                        )
                    }
                    queue.refresh()
                }

                is ApiResult.Err -> setStatus(
                    R.string.status_error_hint, StatusKind.ERR, result.message
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------- 封面 + 信息 ----------------
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverImage(
                    url = detail?.coverUrl.orEmpty(),
                    modifier = Modifier
                        .width(COVER_WIDTH)
                        .height(COVER_HEIGHT)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> Text(
                            text = stringResource(R.string.album_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        loadError != null -> Text(
                            text = stringResource(R.string.album_load_failed, loadError!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        detail != null -> AlbumInfo(
                            detail = detail!!,
                            likesText = likesText,
                            onOpenTypedSearch = onOpenTypedSearch,
                            onCollectTag = { openTagCollect(it) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // ---------------- 操作按钮（两行两列，适配手机窄屏） ----------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { detail?.let { openUrl(context, it.url) } },
                    enabled = !detail?.url.isNullOrEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_open_site), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { downloadNow() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_download_now), maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAddToDownloadList(albumId)
                        setStatus(R.string.status_added_queue, StatusKind.OK, albumId)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_playlist_add),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_add_to_queue), maxLines = 1)
                }
                Button(
                    onClick = { toggleFavorite() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(
                            if (favorited) R.drawable.ic_star else R.drawable.ic_star_border
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_favorite), maxLines = 1)
                }
            }
            // 点赞：与本子收藏是两回事，单独一行，图标随状态切换
            OutlinedButton(
                onClick = { toggleLike() },
                enabled = !likeLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (likeLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(
                            if (liked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                        ),
                        contentDescription = null,
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (liked) R.string.btn_liked else R.string.btn_like),
                    maxLines = 1,
                )
            }
            // 在线浏览独占一行：这是详情页最常用的动作，按钮做大一点
            OutlinedButton(
                onClick = { browseOnline() },
                enabled = !browseLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (browseLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_browse),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_browse), maxLines = 1)
            }
            // 标签能力说明：圆角方框看着可点，这里把「点一下搜索、长按收藏」讲清楚
            Text(
                text = stringResource(R.string.album_tag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------- 预览前几页 ----------------
            if (previewEnabled) {
                PreviewStrip(
                    metas = metas,
                    thumbs = previewThumbs,
                    loading = previewLoading,
                    error = previewError,
                    onOpen = { zoomIndex = it },
                )
            }

            // ---------------- 网站评论 ----------------
            HorizontalDivider()
            CommentSection(
                comments = comments,
                total = commentTotal,
                loadedPage = commentPage,
                count = commentCount,
                loading = commentsLoading,
                error = commentError,
                input = commentInput,
                posting = commentPosting,
                onInputChange = { commentInput = it },
                onPost = { postComment() },
                onLoadMore = { loadComments(commentPage + 1, append = true) },
            )

            StatusLine(statusRes, statusArg, statusKind)
        }

        // ---------------- 放大查看 ----------------
        val index = zoomIndex
        if (index >= 0 && index < previewBytes.size) {
            PreviewZoomOverlay(
                bytes = previewBytes[index],
                index = index,
                count = previewBytes.size,
                onPrev = { if (index > 0) zoomIndex = index - 1 },
                onNext = { if (index < previewBytes.size - 1) zoomIndex = index + 1 },
                onClose = { zoomIndex = -1 },
            )
        }
    }

    // ---------------- 收藏夹弹窗 ----------------
    if (folderDialogOpen) {
        val allFolders = listOf(
            FavoriteFolder("0", stringResource(R.string.favorite_folder_all))
        ) + folders
        AlertDialog(
            onDismissRequest = { folderDialogOpen = false },
            title = { Text(stringResource(R.string.favorite_choose_title)) },
            text = {
                when {
                    foldersLoading -> Text(
                        text = stringResource(R.string.favorite_choose_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    foldersError != null -> Text(
                        text = stringResource(R.string.favorite_choose_failed, foldersError!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        allFolders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFolder = folder.id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedFolder == folder.id,
                                    onClick = { selectedFolder = folder.id },
                                )
                                Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !foldersLoading && foldersError == null,
                    onClick = {
                        folderDialogOpen = false
                        scope.launch {
                            setStatus(R.string.status_favorite_adding, StatusKind.IDLE)
                            when (val result = ApiBridge.favoriteAdd(albumId, selectedFolder)) {
                                is ApiResult.Ok -> {
                                    favorited = true
                                    setStatus(R.string.status_favorite_added, StatusKind.OK)
                                }

                                is ApiResult.Err -> setStatus(
                                    R.string.status_favorite_add_failed,
                                    StatusKind.ERR,
                                    result.message,
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderDialogOpen = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    // ---------------- 收藏标签弹窗（长按标签进入，交互与本子收藏类似） ----------------
    collectTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { collectTag = null },
            title = { Text(stringResource(R.string.tag_collect_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tag_collect_tag, tag),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        tagFolderLoading -> Text(
                            text = stringResource(R.string.favorite_choose_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        tagFolderError != null -> Text(
                            text = stringResource(R.string.favorite_choose_failed, tagFolderError!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> tagFolders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { tagFolderSel = folder.id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = tagFolderSel == folder.id,
                                    onClick = { tagFolderSel = folder.id },
                                )
                                Text(
                                    text = if (folder.isDefault) {
                                        stringResource(R.string.favorite_folder_default)
                                    } else {
                                        folder.name
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTagFolderName,
                            onValueChange = { newTagFolderName = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.tag_folder_name_hint)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = newTagFolderName.trim().isNotEmpty(),
                            onClick = {
                                val name = newTagFolderName.trim()
                                scope.launch {
                                    when (val result = ApiBridge.tagFolderCreate(name)) {
                                        is ApiResult.Ok -> {
                                            newTagFolderName = ""
                                            tagFolders = tagFolders + result.value
                                            tagFolderSel = result.value.id
                                        }

                                        is ApiResult.Err -> setStatus(
                                            R.string.status_tag_folder_failed,
                                            StatusKind.ERR,
                                            result.message,
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.tag_new_folder))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tagFolderLoading && tagFolderError == null && tagFolderSel.isNotEmpty(),
                    onClick = {
                        val folderId = tagFolderSel
                        collectTag = null
                        scope.launch {
                            when (val result = ApiBridge.tagAdd(tag, folderId)) {
                                is ApiResult.Ok ->
                                    setStatus(R.string.status_tag_added, StatusKind.OK, tag)

                                is ApiResult.Err -> setStatus(
                                    R.string.status_tag_add_failed, StatusKind.ERR, result.message
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { collectTag = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/**
 * 本子信息：标题 / ID / 作者 / 标签 / 角色 / 点赞与观看 / 页数与章节。
 *
 * 作者 / 标签 / 角色都渲染成可点击的圆角标签（高度固定、长度自适应）：点击跳「对应类型」的
 * 搜索；长按标签则收藏该标签（选收藏夹或新建收藏夹，与本子收藏同款交互）。
 * 「角色」位于「标签」下一行、「点赞数 / 观看数」上一行。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AlbumInfo(
    detail: AlbumDetail,
    likesText: String,
    onOpenTypedSearch: (String, String) -> Unit,
    onCollectTag: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(stringResource(R.string.meta_title), detail.title)
                InfoRow(stringResource(R.string.meta_album_id), detail.id)
            }
        }
        ChipRow(
            label = stringResource(R.string.meta_author),
            values = detail.authorList.ifEmpty { listOf(detail.author).filter { it.isNotEmpty() } },
            onClick = { onOpenTypedSearch("author", it) },
            onLongClick = null,
        )
        ChipRow(
            label = stringResource(R.string.meta_tags),
            values = detail.tagList.ifEmpty { listOf(detail.tags).filter { it.isNotEmpty() } },
            onClick = { onOpenTypedSearch("tag", it) },
            onLongClick = onCollectTag,
        )
        ChipRow(
            label = stringResource(R.string.meta_actors),
            values = detail.actorList,
            onClick = { onOpenTypedSearch("actor", it) },
            onLongClick = null,
        )
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Box(Modifier.weight(1f)) {
                        InfoRow(stringResource(R.string.meta_likes), likesText)
                    }
                    Box(Modifier.weight(1f)) {
                        InfoRow(stringResource(R.string.meta_views), detail.views)
                    }
                }
                Row {
                    Box(Modifier.weight(1f)) {
                        InfoRow(stringResource(R.string.meta_pages), detail.pages.toString())
                    }
                    Box(Modifier.weight(1f)) {
                        InfoRow(stringResource(R.string.meta_chapters), detail.chapters.toString())
                    }
                }
            }
        }
    }
}

/** 一行可点击标签：小标题 + 自动换行的圆角标签。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ChipRow(
    label: String,
    values: List<String>,
    onClick: (String) -> Unit,
    onLongClick: ((String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (values.isEmpty()) {
            Text(text = "—", style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.forEach { value ->
                    TagChip(
                        text = value,
                        onClick = { onClick(value) },
                        onLongClick = onLongClick?.let { click -> { click(value) } },
                    )
                }
            }
        }
    }
}

/** 自适应长度、高度固定的圆角方框：明示可点击；长按做次要动作（收藏标签）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(text: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 网站评论区：标题 + 发表框 + 列表（可「加载更多」）。 */
@Composable
private fun CommentSection(
    comments: List<CommentItem>,
    total: Int,
    loadedPage: Int,
    count: Int,
    loading: Boolean,
    error: String?,
    input: String,
    posting: Boolean,
    onInputChange: (String) -> Unit,
    onPost: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (count > 0) {
                stringResource(R.string.comments_title_count, count)
            } else {
                stringResource(R.string.comments_title)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                placeholder = {
                    Text(
                        text = stringResource(R.string.comment_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onPost, enabled = !posting && input.trim().isNotEmpty()) {
                Text(stringResource(R.string.btn_post_comment), maxLines = 1)
            }
        }
        when {
            error != null -> Text(
                text = stringResource(R.string.comment_load_failed, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            loading && comments.isEmpty() -> Text(
                text = stringResource(R.string.comment_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            comments.isEmpty() -> Text(
                text = stringResource(R.string.comment_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> comments.forEach { CommentRow(it, 0) }
        }
        if (!loading && comments.isNotEmpty() && total > loadedPage) {
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.comment_load_more))
            }
        }
    }
}

/** 单条评论（含递归的回复）；剧透内容默认遮蔽，点一下才展开。 */
@Composable
private fun CommentRow(comment: CommentItem, depth: Int) {
    var revealed by remember(comment.id) { mutableStateOf(!comment.spoiler) }
    Column(
        modifier = Modifier.padding(start = (depth * 16).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = comment.user,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            if (comment.created.isNotEmpty()) {
                Text(
                    text = comment.created,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (comment.spoiler && !revealed) {
            Text(
                text = stringResource(R.string.comment_spoiler_content),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { revealed = true },
            )
        } else {
            Text(text = comment.content, style = MaterialTheme.typography.bodySmall)
        }
        if (comment.likes > 0) {
            Text(
                text = stringResource(R.string.comment_likes, comment.likes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        comment.replies.forEach { CommentRow(it, depth + 1) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 点赞 / 观看 / 页数取不到时给一个占位符（与桌面版一致）
            text = value.ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 预览缩略图：手机屏幕改成一排横向滚动（桌面版是一排 5 张按窗口宽铺开）。 */
@Composable
private fun PreviewStrip(
    metas: List<PreviewPageMeta>,
    thumbs: List<Bitmap?>,
    loading: Boolean,
    error: String?,
    onOpen: (Int) -> Unit,
) {
    when {
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.preview_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error != null -> Text(
            text = stringResource(R.string.preview_failed, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        metas.isEmpty() -> Unit

        else -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            metas.forEachIndexed { index, meta ->
                val ratio = if (meta.width > 0) meta.height.toFloat() / meta.width else 4f / 3f
                val height = (THUMB_WIDTH.value * ratio)
                    .coerceAtMost(THUMB_MAX_HEIGHT.value)
                    .coerceAtLeast(1f)
                val bitmap = thumbs.getOrNull(index)
                Box(
                    modifier = Modifier
                        .width(THUMB_WIDTH)
                        .height(height.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = bitmap != null) { onOpen(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 预览图放大层：暗底铺满内容区，右上关闭，两侧在已取到的预览图之间翻页。
 *
 * 图片按当前页**现解全尺寸**（只在放大期间占内存），关闭后随重组释放。
 */
@Composable
private fun PreviewZoomOverlay(
    bytes: ByteArray,
    index: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bytes) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Throwable) {
                null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ZOOM_SCRIM)) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.btn_close),
                tint = Color.White,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, enabled = index > 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.btn_prev_page),
                    tint = Color.White,
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = index < count - 1) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.btn_next_page),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 按目标宽度降采样解码（`inSampleSize` 取 2 的幂）。
 *
 * 预览原图约 1280×1793，直接解全尺寸每张约 9 MB；缩略图只需要几百像素宽，
 * 降采样后每张不到 1 MB，5 张也不会把内存顶爆。
 */
private fun decodeSampled(bytes: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    return try {
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (_: Throwable) {
        null
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isEmpty()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        LogBridge.append("ERR", "无法打开链接：$url")
    }
}