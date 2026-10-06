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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.Platform
import com.j2pmobile.android.R
import com.j2pmobile.android.UpdateService
import com.j2pmobile.android.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 更新弹窗当前所处的阶段。 */
enum class UpdateStage { CHECKING, NO_UPDATE, FOUND, PICK_SOURCE, DOWNLOADING, DONE, FAILED, NO_SOURCE }

/** 更新流程的界面状态（由 [UpdateCenter] 驱动，Compose 直接读取）。 */
class UpdateUiState {
    var visible by mutableStateOf(false)
    var stage by mutableStateOf(UpdateStage.CHECKING)
    var latestVersion by mutableStateOf("")
    var sourceName by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var progressText by mutableStateOf("")
    var error by mutableStateOf("")
    var hasAsset by mutableStateOf(false)
    var releasePage by mutableStateOf(Updater.RELEASES_URL)

    /** 更新清单里的网盘入口（「请选择下载路径」与「源不可用」弹窗逐行列出）。 */
    var netdisks by mutableStateOf(emptyList<Updater.Netdisk>())

    /** 「请选择下载路径」弹窗用：候选源（含时延）、当前选中项、是否正在重新测速。 */
    var sourceOptions by mutableStateOf(emptyList<Updater.Candidate>())
    var pickedSource by mutableStateOf("")
    var testing by mutableStateOf(false)
}

/**
 * 更新编排：探测源 → 查询 Release → 提示并下载 → 通知栏进度 → 拉起安装器。
 *
 * 自动更新（启动时触发）与手动检查走同一套逻辑，区别只在「无更新 / 出错」时要静默。
 * 下载进度同时写进通知栏（[UpdateService]）与弹窗。
 */
class UpdateCenter(private val context: Context, private val scope: CoroutineScope) {

    val state = UpdateUiState()

    private data class Pending(
        val release: Updater.Release,
        val source: Updater.Source,
        val asset: Updater.Asset?,
    )

    private sealed class Outcome {
        data class NoSource(
            val netdisks: List<Updater.Netdisk>,
            val releasePage: String,
        ) : Outcome()

        data class Failed(val message: String) : Outcome()
        data class Latest(val version: String) : Outcome()
        data class Found(
            val release: Updater.Release,
            val source: Updater.Source,
            val asset: Updater.Asset?,
            val options: List<Updater.Candidate>,
            val netdisks: List<Updater.Netdisk>,
            val releasePage: String,
        ) : Outcome()
    }

    private var pending: Pending? = null
    private var busy = false

    /** 本次检查拉到的更新清单（镜像 / 网盘 / Release 页）；拉不到时是内置兜底。 */
    private var manifest = Updater.defaultManifest()

    /** 正在跑的检查任务：用户点「取消」时直接取消它。 */
    private var checkJob: Job? = null

    /** 正在跑的下载任务；用户点「停止更新」时取消它。 */
    private var downloadJob: Job? = null

    /** 下载取消标记：下载循环在下一个分片处检查到它就把临时文件删掉并中断。 */
    private var downloadCancelled = false

    /** 重新测速任务（点「重新测速」时跑，用户取消 / 关弹窗时停掉）。 */
    private var retestJob: Job? = null

    /** 用户取消检查后置真：避免「结果刚回来、还没弹窗」的那一次检查又把结果弹出来。 */
    private var checkCancelled = false

    /**
     * 当前安装包版本号（取自系统，等于 build.gradle 的 versionName）。
     *
     * 去掉可能存在的 `v` 前缀：文案模板里自带 `v%1$s`，否则会显示成 `vv2.4.4`
     * （「已是最新版本」那句就是走这里，用户实测踩到过）。
     */
    private fun currentVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName.orEmpty().trimStart('v', 'V')
    }.getOrDefault("")

    /** 检查更新。[manual] 为 false 时是启动自动检查：无更新 / 出错都保持静默。 */
    fun check(channel: String, manual: Boolean) {
        // 正在下载：直接回到进度弹窗，不重新检查、也不会再起一次下载
        if (downloadJob?.isActive == true) {
            state.stage = UpdateStage.DOWNLOADING
            state.visible = true
            return
        }
        if (busy) return
        busy = true
        checkCancelled = false
        if (manual) {
            state.stage = UpdateStage.CHECKING
            state.visible = true
        }
        checkJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCheck(channel) }
            busy = false
            when (outcome) {
                is Outcome.NoSource -> {
                    state.netdisks = outcome.netdisks
                    state.releasePage = outcome.releasePage
                    if (manual) show(UpdateStage.NO_SOURCE)
                }

                is Outcome.Failed -> if (manual) {
                    state.error = outcome.message
                    show(UpdateStage.FAILED)
                }

                is Outcome.Latest -> if (manual) {
                    state.latestVersion = outcome.version
                    show(UpdateStage.NO_UPDATE)
                }

                is Outcome.Found -> {
                    pending = Pending(outcome.release, outcome.source, outcome.asset)
                    state.latestVersion = outcome.release.version
                    // 「GitHub Release」跳转行取清单里的发布页地址，保证与网盘链接同源
                    state.releasePage = outcome.releasePage
                    state.netdisks = outcome.netdisks
                    state.hasAsset = outcome.asset != null
                    // 候选源（含时延）在检查阶段就算好了，直接拿来给用户选
                    state.sourceOptions = outcome.options
                    state.pickedSource = outcome.options.firstOrNull()?.name.orEmpty()
                    if (manual) {
                        show(UpdateStage.FOUND)
                    } else {
                        // 自动更新：先浮窗告知新版本号，随后用默认源（时延最低）开始下载
                        show(UpdateStage.DOWNLOADING)
                        delay(800)
                        download()
                    }
                }
            }
        }
    }

    /** 打开「请选择下载路径」弹窗：列出候选源（含时延）与两个手动更新出口。 */
    fun pickSource() {
        if (state.sourceOptions.isEmpty()) return
        if (state.pickedSource.isEmpty()) state.pickedSource = state.sourceOptions.first().name
        state.testing = false
        show(UpdateStage.PICK_SOURCE)
    }

    /** 用户选中某个下载源。 */
    fun selectSource(name: String) {
        state.pickedSource = name
    }

    /** 重新并行测一遍所有源的时延，刷新列表（选中项失效时回落到第一个）。 */
    fun retestSources() {
        if (state.testing) return
        state.testing = true
        retestJob?.cancel()
        retestJob = scope.launch {
            val available = withContext(Dispatchers.IO) {
                runCatching { Updater.probeSources(manifest.sources) }
                    .getOrDefault(emptyList())
            }
            if (checkCancelled || state.stage != UpdateStage.PICK_SOURCE) return@launch
            val asset = pending?.asset
            val options = if (asset == null) emptyList()
            else Updater.downloadOptions(asset.url, available, manifest.sources)
            state.sourceOptions = options
            if (options.none { it.name == state.pickedSource }) {
                state.pickedSource = options.firstOrNull()?.name.orEmpty()
            }
            state.testing = false
        }
    }

    /** 用户按选定的源开始下载（选中的源失败会自动换后面还没试过的源）。 */
    fun confirmSource() {
        if (state.pickedSource.isEmpty()) return
        download()
    }

    /** 用户确认更新（或自动更新进入下载）：开始下载并在通知栏展示进度。 */
    fun download() {
        val item = pending ?: return
        val asset = item.asset ?: return
        state.stage = UpdateStage.DOWNLOADING
        state.visible = true
        state.progress = 0f
        downloadCancelled = false
        retestJob?.cancel()
        // 用户选中的源排最前；它失败会自动换后面的源，最后才是 GitHub 原址
        val options = state.sourceOptions.ifEmpty {
            Updater.downloadOptions(asset.url, emptyList(), manifest.sources)
        }
        val picked = state.pickedSource.ifEmpty { options.firstOrNull()?.name.orEmpty() }
        val ordered = Updater.preferFirst(options, picked)
        state.sourceName = ordered.first().name
        val version = item.release.version
        val downloading = context.getString(R.string.update_notification_downloading, version)
        UpdateService.start(context.getString(R.string.update_notification_title), downloading, 100)

        downloadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(File(Platform.rootDir(), "update"), asset.name)
                    val lastTick = longArrayOf(0L)
                    Updater.downloadFrom(
                        ordered,
                        file,
                        isCancelled = { downloadCancelled },
                    ) { done, total ->
                        val now = System.currentTimeMillis()
                        if (total > 0 && done < total && now - lastTick[0] < 200) return@downloadFrom
                        lastTick[0] = now
                        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                        state.progress = if (total > 0) done.toFloat() / total else 0f
                        state.progressText = humanSize(done, total)
                        UpdateService.update(downloading, percent, 100)
                    }
                    file
                }
            }
            UpdateService.stop()
            if (downloadCancelled) {
                // 用户点了「停止更新」：临时文件已清掉，静默结束
                downloadCancelled = false
                return@launch
            }
            result.onSuccess { file ->
                state.progress = 1f
                state.progressText = humanSize(file.length(), file.length())
                show(UpdateStage.DONE)
                runCatching { Updater.installApk(context, file) }
            }.onFailure { error ->
                state.error = error.message ?: error.javaClass.simpleName
                show(UpdateStage.FAILED)
            }
        }
    }

    /** 安装器没能拉起时，用户可以点「重新安装」再试一次。 */
    fun reinstall() {
        val name = pending?.asset?.name ?: return
        val file = File(File(Platform.rootDir(), "update"), name)
        if (file.isFile) runCatching { Updater.installApk(context, file) }
    }

    /** 收起弹窗：下载中只是隐藏界面、下载继续跑。 */
    fun dismiss() {
        state.visible = false
        if (state.stage == UpdateStage.PICK_SOURCE || state.stage == UpdateStage.FOUND) {
            cancelRetest()
        }
    }

    /** 取消检查更新：立刻收起弹窗，并作废这次结果（不再弹任何后续弹窗）。 */
    fun cancelCheck() {
        checkCancelled = true
        checkJob?.cancel()
        checkJob = null
        busy = false
        cancelRetest()
        state.visible = false
    }

    /** 停止更新：中断下载、清理临时文件并收起弹窗。 */
    fun stopDownload() {
        downloadCancelled = true
        downloadJob?.cancel()
        downloadJob = null
        cancelRetest()
        UpdateService.stop()
        state.visible = false
    }

    private fun cancelRetest() {
        retestJob?.cancel()
        retestJob = null
        state.testing = false
    }

    private fun show(stage: UpdateStage) {
        state.stage = stage
        state.visible = true
    }

    private suspend fun runCheck(channel: String): Outcome {
        return try {
            // 先拉更新清单（镜像 / 网盘 / Release 页）；拉不到就用内置兜底
            manifest = Updater.fetchManifest() ?: Updater.defaultManifest()
            val available = Updater.probeSources(manifest.sources)
            // 探测只用来排序与展示时延：查询会「探测结果 → 官方 API 兜底」逐个真试，
            // 走代理 / VPN 时探测全灭也能查到更新
            var release: Updater.Release? = null
            var source: Updater.Source? = null
            for (candidate in Updater.apiCandidates(available)) {
                try {
                    release = Updater.fetchRelease(candidate, channel)
                    source = candidate
                    break
                } catch (_: Throwable) {
                    // 这个源查不动，换下一个（最后一个是官方兜底）
                }
            }
            if (source == null) return Outcome.NoSource(manifest.netdisks, manifest.releasePage)
            if (release == null) return Outcome.Latest(currentVersion())
            if (!Updater.isNewer(release.version, currentVersion())) {
                return Outcome.Latest(release.version)
            }
            val asset = Updater.pickAsset(release)
            // 候选源（含时延）在这里一次算好，用户点「立即更新」时直接列出来，不必再测
            val options = if (asset == null) emptyList()
            else Updater.downloadOptions(asset.url, available, manifest.sources)
            Outcome.Found(release, source, asset, options, manifest.netdisks, manifest.releasePage)
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun humanSize(done: Long, total: Long): String {
        val head = formatSize(done)
        val tail = if (total > 0) formatSize(total) else "?"
        return "$head / $tail"
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }
}

/**
 * 更新相关弹窗的统一宿主：按 [UpdateUiState.stage] 渲染对应内容。
 *
 * 所有弹窗都限宽（[widthIn]）并限制正文最大高度（内部滚动），避免内容多时撑破屏幕。
 */
@Composable
fun UpdateDialogs(
    state: UpdateUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancelCheck: () -> Unit,
    onStopDownload: () -> Unit,
    onReinstall: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onPickSource: (String) -> Unit,
    onConfirmSource: () -> Unit,
    onRetest: () -> Unit,
) {
    if (!state.visible) return

    when (state.stage) {
        // 检查中：关闭 = 取消本次检查
        UpdateStage.CHECKING -> UpdateShell(
            title = stringResource(R.string.section_update),
            onDismiss = onCancelCheck,
            actions = {
                TextButton(onClick = onCancelCheck) { Text(stringResource(R.string.btn_cancel)) }
            },
        ) {
            Text(stringResource(R.string.update_checking))
        }

        UpdateStage.NO_UPDATE -> UpdateShell(
            title = stringResource(R.string.section_update),
            onDismiss = onDismiss,
            actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
        ) {
            Text(stringResource(R.string.update_no_update_body, state.latestVersion))
        }

        UpdateStage.FOUND -> UpdateShell(
            title = stringResource(R.string.update_found_title),
            onDismiss = onDismiss,
            actions = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                if (state.hasAsset) {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.btn_update_now))
                    }
                } else {
                    TextButton(onClick = { onOpenUrl(state.releasePage) }) {
                        Text(stringResource(R.string.btn_open_release))
                    }
                }
            },
        ) {
            Text(stringResource(R.string.update_found_body, currentVersionText(), state.latestVersion))
        }

        // 选择下载路径：只显示源站名与时延，下方另给网盘 / GitHub Release 两个手动出口
        UpdateStage.PICK_SOURCE -> UpdateShell(
            title = stringResource(R.string.update_pick_source_title),
            onDismiss = onDismiss,
            actions = {
                TextButton(onClick = onRetest, enabled = !state.testing) {
                    Text(stringResource(R.string.btn_retest))
                }
                TextButton(onClick = onConfirmSource) {
                    Text(stringResource(R.string.btn_confirm))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            },
        ) {
            if (state.testing) {
                Text(stringResource(R.string.update_testing))
            } else {
                state.sourceOptions.forEach { option ->
                    SourcePickRow(
                        name = option.name,
                        latencyMs = option.latencyMs,
                        picked = option.name == state.pickedSource,
                        onClick = { onPickSource(option.name) },
                    )
                }
                HorizontalDivider()
                // 网盘 / GitHub Release 跳转行：来自更新清单，逐个列出
                state.netdisks.forEach { netdisk ->
                    ManualPickRow(
                        label = netdisk.name,
                        url = netdisk.url,
                        onOpenUrl = onOpenUrl,
                    )
                }
                ManualPickRow(
                    label = stringResource(R.string.update_release_label),
                    url = state.releasePage,
                    onOpenUrl = onOpenUrl,
                )
            }
        }

        // 下载中：停止更新 = 中断下载；后台更新 = 收起界面、下载照跑（通知栏仍有进度）
        UpdateStage.DOWNLOADING -> UpdateShell(
            title = stringResource(R.string.update_found_title),
            onDismiss = onDismiss,
            actions = {
                TextButton(onClick = onStopDownload) {
                    Text(stringResource(R.string.btn_stop_update))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_background_update))
                }
            },
        ) {
            Text(stringResource(R.string.update_found_version_line, state.latestVersion))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.update_downloading_hint, state.sourceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdateStage.DONE -> UpdateShell(
            title = stringResource(R.string.update_done_title),
            onDismiss = onDismiss,
            actions = {
                TextButton(onClick = onReinstall) { Text(stringResource(R.string.btn_install_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
            },
        ) {
            Text(stringResource(R.string.update_done_body, state.latestVersion))
        }

        UpdateStage.FAILED -> UpdateShell(
            title = stringResource(R.string.update_failed_title),
            onDismiss = onDismiss,
            actions = {
                // 加速源与官方原址都跑不通：给一个「打开发布页」的出口，让用户手动更新
                TextButton(onClick = { onOpenUrl(state.releasePage) }) {
                    Text(stringResource(R.string.btn_open_release))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
            },
        ) {
            Text(stringResource(R.string.update_failed_body, state.error))
        }

        UpdateStage.NO_SOURCE -> UpdateShell(
            title = stringResource(R.string.update_no_source_title),
            onDismiss = onDismiss,
            actions = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
            },
        ) {
            Text(stringResource(R.string.update_no_source_body))
            HorizontalDivider()
            // 网盘 / GitHub Release 跳转行：来自更新清单，逐个列出
            state.netdisks.forEach { netdisk ->
                ManualPickRow(label = netdisk.name, url = netdisk.url, onOpenUrl = onOpenUrl)
            }
            ManualPickRow(
                label = stringResource(R.string.update_release_label),
                url = state.releasePage,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/** 弹窗外壳：统一限宽、正文限高可滚动；[actions] 放操作按钮（右对齐一行）。 */
@Composable
private fun UpdateShell(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        modifier = Modifier.widthIn(max = 360.dp),
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) { actions() }
        },
    )
}

/**
 * 弹窗里展示「当前版本」用：`about_version_value` 自带 `v` 前缀，
 * 而文案模板里已经有 `v%1$s`，这里去掉前缀避免出现 `vv2.4.4`。
 */
@Composable
private fun currentVersionText(): String =
    stringResource(R.string.about_version_value).trimStart('v', 'V')

/** 「请选择下载路径」里一个可选的下载源：左源站名、右时延；点一下即选中。 */
@Composable
private fun SourcePickRow(name: String, latencyMs: Long, picked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(if (picked) "●" else "○", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (picked) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (latencyMs >= 0) "$latencyMs ms" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 手动更新出口（网盘 / GitHub Release）：点了直接跳转；没有链接时只作展示。 */
@Composable
private fun ManualPickRow(label: String, url: String, onOpenUrl: (String) -> Unit) {
    val enabled = url.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onOpenUrl(url) } else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
