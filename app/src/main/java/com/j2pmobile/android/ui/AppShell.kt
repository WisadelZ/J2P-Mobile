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
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.QueueBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.ReaderSource
import com.j2pmobile.android.ui.components.CoverCache
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
    var albumId by remember { mutableStateOf("") }
    val current = stack.last()

    /** 关掉阅读会话：清掉状态并让 Python 释放句柄（缓存随之回收）。 */
    fun closeReader() {
        if (reader.source == null) return
        val handle = reader.handle
        reader.reset()
        scope.launch { ApiBridge.readerClose(handle) }
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
        val mode = config.optJSONObject("config")
            ?.optJSONObject("app")
            ?.optString("theme_mode")
        if (mode != null && mode in ThemeMode.all) themeMode = mode
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
                            // 阅读器用本子名当标题，比一个通用的「浏览」有用得多
                            val name = reader.source?.name.orEmpty()
                            if (current == AppRoute.Reader && name.isNotEmpty()) {
                                Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        },
                    )
                }
            },
            bottomBar = {
                // 阅读器整屏都留给画面，底部导航收起（返回键回上一级）
                if (current != AppRoute.Reader) {
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
                    )

                    // 以下页面在第 4 步的后续切片里逐页落地
                    else -> PlaceholderScreen()
                }
            }
        }
    }
}

private fun tabIconRes(tab: AppRoute): Int = when (tab) {
    AppRoute.Download -> R.drawable.ic_download
    AppRoute.Explorer -> R.drawable.ic_folder
    AppRoute.Account -> R.drawable.ic_person
    else -> R.drawable.ic_search
}