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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.PdfMeta
import com.j2pmobile.android.QueueBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.ReaderSource
import com.j2pmobile.android.SourceNode
import com.j2pmobile.android.SourceState
import com.j2pmobile.android.Updater
import com.j2pmobile.android.ui.components.CoverCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用外壳（第 4 步）：
 *
 * - **底部**四个入口（探索 / 下载 / 资源管理器 / 账号），顺序固定；
 * - **顶栏**只在有上一级时显示返回按钮（「返回主页」已移除，底栏就是主页快捷入口），
 *   账号页右上角多一个不带文字的小齿轮进入设置；
 * - 页面跳转逻辑与桌面版一致：二级页压栈，返回逐级弹栈，系统返回键同效。
 *
 * 另外两处与桌面版一致的约定：
 * - 主题取自 conf.yml 的 `app.theme_mode`，切到浅色 / 深色 / 跟随系统；
 * - 探索页状态、下载页表单、任务页筛选都由本层持有，进二级页再返回时都还在。
 *
 * 队列刷新：订阅 [QueueBridge.revision]（Python 侧队列变化时递增），只在下载页 /
 * 任务页时拉一次快照，避免在其它页面白跑 JNI。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(onRecreate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var stack by remember { mutableStateOf(listOf(AppRoute.Explore)) }
    val explore = remember { ExploreState() }
    val download = remember { DownloadState() }
    val queue = remember { QueueState() }
    val account = remember { AccountState() }
    val favorite = remember { FavoriteState() }
    val explorer = remember { ExplorerState() }
    val reader = remember { ReaderState() }
    val updateCenter = remember { UpdateCenter(context, scope) }
    var albumId by remember { mutableStateOf("") }
    val current = stack.last()

    // 阅读器标题点击后的资料弹窗（在线本子 / 本地 PDF / 图片文件夹统一成同一组字段）
    var readerMetaOpen by remember { mutableStateOf(false) }
    var readerMeta by remember { mutableStateOf<PdfMeta?>(null) }
    var readerMetaLoading by remember { mutableStateOf(false) }
    var readerMetaError by remember { mutableStateOf<String?>(null) }

    // 切换加载源弹窗
    var sourceOpen by remember { mutableStateOf(false) }

    /** 关掉阅读会话：清掉状态并让 Python 释放句柄（缓存随之回收）。 */
    fun closeReader() {
        readerMetaOpen = false
        if (reader.source == null) return
        val handle = reader.handle
        reader.reset()
        scope.launch { ApiBridge.readerClose(handle) }
    }

    /** 点标题看文件 / 本子资料：与资源管理器的元数据弹窗同一组字段。 */
    fun openReaderMeta() {
        val handle = reader.handle
        if (handle.isEmpty()) return
        readerMetaOpen = true
        readerMeta = null
        readerMetaError = null
        readerMetaLoading = true
        scope.launch {
            when (val result = ApiBridge.readerMeta(handle)) {
                is ApiResult.Ok -> readerMeta = result.value
                is ApiResult.Err -> readerMetaError = result.message
            }
            readerMetaLoading = false
        }
    }

    /** 返回上一级；离开阅读器时顺手释放会话。 */
    fun popPage() {
        if (stack.size <= 1) return
        val leaving = stack.last()
        stack = stack.dropLast(1)
        if (leaving == AppRoute.Reader) closeReader()
    }

    /** 打开阅读器（由资源管理器 / 本子详情页把已就绪的会话交进来）。 */
    fun openReader(source: ReaderSource) {
        reader.open(source)
        stack = stack + AppRoute.Reader
    }

    // 通知运行时权限（API 33+）：下载前台服务的进度通知需要它。
    // 服务本身在权限缺失时仍能启动（通知只是不显示），所以这里只是首次启动顺带申请一次。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        val config = ApiBridge.loadConfig()
        val app = config.optJSONObject("config")?.optJSONObject("app")
        val mode = app?.optString("theme_mode")
        if (mode != null && mode in ThemeMode.all) themeMode = mode
        // 自动更新：等界面完全起来（并给冷启动的 Python 预热让路）后再检查；
        // 无更新或出错都保持静默，只有真的发现新版本才弹窗。
        if (app?.optBoolean("auto_update", false) == true) {
            delay(AUTO_UPDATE_DELAY_MS)
            val channel = app.optString("update_channel").ifEmpty { Updater.CHANNEL_STABLE }
            updateCenter.check(channel, manual = false)
        }
    }

    // 加载源：界面完全起来后静默测一次所有候选源，把延迟最低的设为当前节点。
    // 失败静默（界面照旧用内置域名顺序），用户打开切换弹窗时还能手动测一次。
    LaunchedEffect(Unit) {
        delay(AUTO_UPDATE_DELAY_MS)
        ApiBridge.sourceRefresh()
    }

    // 队列快照刷新：进入下载 / 任务页时先拉一次，之后跟着队列变更刷新。
    //
    // 【性能要点】这里**不要**在组合函数里读 `QueueBridge.revision`（例如
    // `collectAsState()`）——那等于让整个外壳（Scaffold + 当前页面）每 300ms 重组一次，
    // 下载时操作会明显发滞。用 `collect` 的**副作用**触发刷新：只有真正读了
    // `queue.*` 的组件才会重组。
    LaunchedEffect(current) {
        if (current == AppRoute.Download || current == AppRoute.Tasks) {
            queue.refresh()
            QueueBridge.revision.collect { queue.refresh() }
        }
    }

    // 换了账号（登录 / 切换 / 退出 / 清空）就让收藏列表页作废，下次进入重新拉取
    LaunchedEffect(account.profile?.username) {
        favorite.reset()
    }

    BackHandler(enabled = stack.size > 1) { popPage() }

    Jm2pdfTheme(themeMode) {
        Scaffold(
            topBar = {
                // 阅读器沉浸式：顶栏也收起，画面铺满整屏（点画面可再唤出）
                if (!(current == AppRoute.Reader && reader.immersive)) {
                    TopAppBar(
                        title = {
                            // 阅读器用本子名当标题：字号收小（默认 titleLarge 在窄屏上放不下长标题），
                            // 点一下看文件 / 本子资料
                            val name = reader.source?.name.orEmpty()
                            if (current == AppRoute.Reader && name.isNotEmpty()) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { openReaderMeta() },
                                )
                            } else {
                                Text(stringResource(current.titleRes))
                            }
                        },
                        navigationIcon = {
                            if (stack.size > 1) {
                                IconButton(onClick = { popPage() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = stringResource(R.string.btn_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (current == AppRoute.Account) {
                                IconButton(onClick = { stack = stack + AppRoute.Settings }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings),
                                        contentDescription = stringResource(R.string.settings_title),
                                    )
                                }
                            }
                            if (current == AppRoute.Reader) {
                                // 左边「打开书签」（多个书签的图标），右边「阅读设置」（小齿轮）；
                                // 两者都是独立页面（压栈），返回键逐级退回阅读器
                                IconButton(onClick = { stack = stack + AppRoute.Bookmarks }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bookmarks),
                                        contentDescription = stringResource(R.string.reader_bookmarks_title),
                                    )
                                }
                                IconButton(onClick = { stack = stack + AppRoute.ReaderSettings }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings),
                                        contentDescription = stringResource(R.string.reader_settings_title),
                                    )
                                }
                            }
                            // 切换加载源：资源管理器除外；阅读相关页面只在看在线本子时显示
                            if (showsSourceButton(current, reader)) {
                                IconButton(onClick = { sourceOpen = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bolt),
                                        contentDescription = stringResource(R.string.source_switch),
                                    )
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                // 阅读器整屏都留给画面，底部导航收起（返回键回上一级）；
                // 「阅读设置 / 书签」是阅读器的二级页，同样不显示底部导航
                if (current != AppRoute.Reader &&
                    current != AppRoute.ReaderSettings &&
                    current != AppRoute.Bookmarks
                ) {
                    NavigationBar {
                        AppRoute.tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab,
                                onClick = { stack = listOf(tab) },
                                icon = {
                                    Icon(
                                        painter = painterResource(tabIconRes(tab)),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (current) {
                    AppRoute.Explore -> ExploreScreen(
                        state = explore,
                        onOpenAlbum = { id ->
                            albumId = id
                            stack = stack + AppRoute.Album
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Album -> AlbumScreen(
                        albumId = albumId,
                        queue = queue,
                        // 下载选项只在下载页真正加载过之后才落盘：否则会把默认值覆盖到用户配置上
                        saveDownloadConf = {
                            if (download.loaded) ApiBridge.updateConfig(download.confPatch())
                        },
                        onAddToDownloadList = { id ->
                            val ids = parseIds(download.idsText)
                            if (!ids.contains(id)) {
                                download.idsText = (ids + id).joinToString(", ")
                            }
                        },
                        onOpenReader = { openReader(it) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Download -> DownloadScreen(
                        state = download,
                        queue = queue,
                        onOpenTasks = { stack = stack + AppRoute.Tasks },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Tasks -> TasksScreen(
                        queue = queue,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Account -> AccountScreen(
                        state = account,
                        onOpenAlbum = { id ->
                            albumId = id
                            stack = stack + AppRoute.Album
                        },
                        onOpenFavorites = { stack = stack + AppRoute.Favorite },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Favorite -> FavoriteScreen(
                        state = favorite,
                        onOpenAlbum = { id ->
                            albumId = id
                            stack = stack + AppRoute.Album
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Explorer -> ExplorerScreen(
                        state = explorer,
                        onOpenReader = { openReader(it) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Reader -> ReaderScreen(
                        state = reader,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.ReaderSettings -> ReaderSettingsScreen(
                        state = reader,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Bookmarks -> BookmarksScreen(
                        state = reader,
                        currentPage = {
                            if (reader.vertical) reader.listState.firstVisibleItemIndex
                            else reader.pagerState.currentPage
                        },
                        // 跳页：先记下目标页，再退回阅读器；由 ReaderScreen 挂载时消费
                        onJump = { target ->
                            reader.pendingJump = target
                            stack = stack.dropLast(1)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppRoute.Settings -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { themeMode = it },
                        onRecreate = onRecreate,
                        onClearCache = {
                            // 「清除缓存」只清运行期缓存：不动登录态、配置与下载任务。
                            // 图片缓存在这里清；探索结果、收藏列表、详情预览由各自的
                            // 状态标记作废，下次进入重新拉取。
                            CoverCache.clear()
                            explore.reset()
                            account.preview = null
                            account.previewError = null
                            favorite.reset()
                        },
                        // 传函数而不是布尔值：外壳层不读队列状态，避免跟着队列快照重组
                        isDownloadActive = { queue.hasActive() },
                        onCheckUpdate = { channel -> updateCenter.check(channel, manual = true) },
                    )

                    // 以下页面在第 4 步的后续切片里逐页落地
                    else -> PlaceholderScreen()
                }
            }
        }

        // 阅读器标题点击后的资料弹窗：字段与资源管理器的元数据弹窗完全一致
        if (readerMetaOpen) {
            AlertDialog(
                onDismissRequest = { readerMetaOpen = false },
                title = { Text(stringResource(R.string.panel_meta_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            readerMetaLoading -> Text(
                                text = stringResource(R.string.explorer_reading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            readerMetaError != null -> Text(
                                text = stringResource(R.string.panel_meta_failed, readerMetaError!!),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )

                            readerMeta == null -> Text(
                                text = stringResource(R.string.panel_meta_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            else -> {
                                val value = readerMeta!!
                                ReaderMetaRow(stringResource(R.string.meta_title), value.title)
                                ReaderMetaRow(stringResource(R.string.meta_album_id), value.albumId)
                                ReaderMetaRow(stringResource(R.string.meta_author), value.author)
                                ReaderMetaRow(stringResource(R.string.meta_tags), value.tags)
                                ReaderMetaRow(stringResource(R.string.meta_pages), value.pages)
                                ReaderMetaRow(stringResource(R.string.meta_chapter), value.chapter)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { readerMetaOpen = false }) {
                        Text(stringResource(R.string.btn_confirm))
                    }
                },
            )
        }

        // 更新相关弹窗：检查结果、新版本询问、下载进度、失败与「源不可用」引导
        UpdateDialogs(
            state = updateCenter.state,
            // 「立即更新」先打开「请选择下载路径」，由用户挑源后再开下
            onConfirm = { updateCenter.pickSource() },
            onDismiss = { updateCenter.dismiss() },
            onCancelCheck = { updateCenter.cancelCheck() },
            onStopDownload = { updateCenter.stopDownload() },
            onReinstall = { updateCenter.reinstall() },
            onOpenUrl = { url -> openUrl(context, url) },
            onPickSource = { name -> updateCenter.selectSource(name) },
            onConfirmSource = { updateCenter.confirmSource() },
            onRetest = { updateCenter.retestSources() },
        )

        // 切换加载源弹窗
        if (sourceOpen) {
            SourceDialog(onClose = { sourceOpen = false })
        }
    }
}

/** 自动检查更新的延迟：等界面完全起来、也给冷启动的 Python 预热让路。 */
private const val AUTO_UPDATE_DELAY_MS = 2500L

/**
 * 当前页面是否显示「切换加载源」。
 *
 * 探索 / 下载 / 账号及其所有子页面都有；资源管理器除外（本地文件不联网）。
 * 阅读相关页面只在看**在线本子**时显示 —— 经资源管理器打开的本地漫画不显示。
 */
private fun showsSourceButton(route: AppRoute, reader: ReaderState): Boolean {
    if (route == AppRoute.Explorer) return false
    val readerPage = route == AppRoute.Reader || route == AppRoute.ReaderSettings ||
        route == AppRoute.Bookmarks
    if (readerPage) return reader.source?.online == true
    return true
}

/**
 * 「切换加载源」弹窗：列出可用节点（只显示序号与延迟，不显示域名）、延迟测试、切换、退出。
 *
 * 打开时先用已有快照渲染（界面起来时后台已经测过一轮）；还没有快照就先测一次。
 */
@Composable
private fun SourceDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<SourceNode>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val testingText = stringResource(R.string.source_testing)

    fun apply(result: ApiResult<SourceState>): List<SourceNode> = when (result) {
        is ApiResult.Ok -> {
            nodes = result.value.nodes
            current = result.value.current
            message = ""
            result.value.nodes
        }

        is ApiResult.Err -> {
            message = result.message
            emptyList()
        }
    }

    LaunchedEffect(Unit) {
        if (apply(ApiBridge.sourceState()).isEmpty()) {
            testing = true
            message = testingText
            val refreshed = apply(ApiBridge.sourceRefresh())
            testing = false
            message = context.getString(R.string.source_tested, refreshed.size)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.source_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (nodes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.source_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    nodes.forEach { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        if (apply(ApiBridge.sourceSelect(node.index)).isNotEmpty()) {
                                            message = context.getString(
                                                R.string.source_switched, node.index)
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.source_node, node.index),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.source_delay, node.delay),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            if (node.index == current) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    message = testingText
                    scope.launch {
                        val refreshed = apply(ApiBridge.sourceRefresh())
                        testing = false
                        message = context.getString(R.string.source_tested, refreshed.size)
                    }
                },
            ) { Text(stringResource(R.string.btn_source_test)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.btn_exit)) }
        },
    )
}

/** 用系统浏览器打开链接（设置页与本文件共用同一实现）。 */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        // 打不开链接不影响主流程，静默即可
    }
}

/** 阅读器资料弹窗里的一行「标签 + 值」（与资源管理器的一致，可长按选中复制）。 */
@Composable
private fun ReaderMetaRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun tabIconRes(tab: AppRoute): Int = when (tab) {
    AppRoute.Download -> R.drawable.ic_download
    AppRoute.Explorer -> R.drawable.ic_folder
    AppRoute.Account -> R.drawable.ic_person
    else -> R.drawable.ic_search
}