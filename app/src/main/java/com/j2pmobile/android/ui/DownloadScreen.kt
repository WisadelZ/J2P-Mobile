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

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumSearchData
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.CoverImage
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 本子 ID 的分隔规则（与桌面 / Python `utils.helpers.parse_ids` 一致）。 */
private val ID_SEPARATOR = Regex("[\\s,;，；、]+")

/** 把输入框里的 ID 文本切成去重后的列表（保持输入顺序）。 */
fun parseIds(text: String): List<String> =
    text.split(ID_SEPARATOR).filter { it.isNotEmpty() }.distinct()

/**
 * 下载页的可变状态（由 [AppShell] 持有）。
 *
 * 表单值（ID、下载选项、邮件推送）与状态提示都放在这里，切到任务页再回来不会丢。
 */
class DownloadState {

    var loaded by mutableStateOf(false)

    /** 待入队的本子 ID 文本。 */
    var idsText by mutableStateOf("")

    var downloadDir by mutableStateOf("")

    var searchId by mutableStateOf("")
    var searching by mutableStateOf(false)
    var result by mutableStateOf<AlbumSearchData?>(null)

    // ---- 下载选项 / 邮件推送（落地到 conf.yml）
    var toPdf by mutableStateOf(true)
    var threadImage by mutableStateOf("30")
    var threadPhoto by mutableStateOf("16")
    var taskConcurrency by mutableStateOf("2")

    var mailEnable by mutableStateOf(false)
    var mailServer by mutableStateOf("smtp.qq.com")
    var mailPort by mutableStateOf("465")
    var mailSender by mutableStateOf("")
    var mailPassword by mutableStateOf("")
    var mailReceiver by mutableStateOf("")
    var mailSubject by mutableStateOf("")
    var mailBody by mutableStateOf("")

    var statusRes by mutableStateOf(R.string.status_ready)
    var statusArg by mutableStateOf<String?>(null)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    /** 从 `load_config` 的结果填充表单（只做一次）。 */
    fun applyConfig(config: JSONObject) {
        val app = config.optJSONObject("app")
        if (app != null) {
            downloadDir = app.optString("download_dir")
            toPdf = app.optBoolean("to_pdf", true)
            threadImage = app.optInt("thread_image", 30).toString()
            threadPhoto = app.optInt("thread_photo", 16).toString()
            taskConcurrency = app.optInt("task_concurrency", 2).toString()
        }
        val mail = config.optJSONObject("mail")
        if (mail != null) {
            mailEnable = mail.optBoolean("enable", false)
            mailServer = mail.optString("server", "smtp.qq.com")
            mailPort = mail.optInt("port", 465).toString()
            mailSender = mail.optString("sender")
            mailPassword = mail.optString("password")
            mailReceiver = mail.optString("receiver")
            mailSubject = mail.optString("subject")
            mailBody = mail.optString("body")
        }
        loaded = true
    }

    /**
     * 下载选项 + 邮件推送的配置补丁。
     *
     * 队列线程在下载时实时从 conf.yml 取配置，所以入队前必须把这份补丁落盘
     * （与桌面版 `enqueue_ids` 里的 `save_conf` 同一目的）。
     */
    fun confPatch(): JSONObject {
        val app = JSONObject()
            .put("download_dir", downloadDir.ifEmpty { "./download" })
            .put("to_pdf", toPdf)
            .put("thread_image", threadImage.toIntOrNull() ?: 30)
            .put("thread_photo", threadPhoto.toIntOrNull() ?: 16)
            .put("task_concurrency", (taskConcurrency.toIntOrNull() ?: 2).coerceIn(1, 4))
        val mail = JSONObject()
            .put("enable", mailEnable)
            .put("server", mailServer.ifEmpty { "smtp.qq.com" })
            .put("port", mailPort.toIntOrNull() ?: 465)
            .put("sender", mailSender)
            .put("password", mailPassword)
            .put("receiver", mailReceiver)
            .put("subject", mailSubject)
            .put("body", mailBody)
        return JSONObject().put("app", app).put("mail", mail)
    }
}

/**
 * 下载页：本子 ID 输入、ID 搜索、下载选项、邮件推送与下载日志。
 *
 * 与桌面版一致的部分：字段语义、折叠分组、状态提示、搜索结果框（含封面与官网链接）。
 * 安卓端的三处适配：
 * - **下载目录只读展示**（固定为应用外部私有目录，不做 SAF 选择）；
 * - **封面由 Kotlin 原生取**（Python 只回传 URL）；
 * - 顶部的「任务中心」入口用一个按钮，而不是桌面版的顶栏图标。
 */
@Composable
fun DownloadScreen(
    state: DownloadState,
    queue: QueueState,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showOptions by remember { mutableStateOf(false) }
    var showMail by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.loaded) return@LaunchedEffect
        val result = ApiBridge.loadConfig()
        if (!result.optBoolean("ok")) {
            state.setStatus(R.string.status_error_hint, StatusKind.ERR, result.optString("error"))
            return@LaunchedEffect
        }
        result.optJSONObject("config")?.let { state.applyConfig(it) }
    }

    /** 搜索本子 ID。 */
    fun doSearch() {
        val albumId = state.searchId.trim()
        if (albumId.isEmpty()) {
            state.setStatus(R.string.status_search_need_id, StatusKind.ERR)
            return
        }
        state.searching = true
        state.result = null
        state.setStatus(R.string.status_searching, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.albumSearch(albumId)) {
                is ApiResult.Ok -> {
                    state.searching = false
                    state.result = result.value
                    state.setStatus(R.string.status_search_done, StatusKind.OK)
                }

                is ApiResult.Err -> {
                    state.searching = false
                    state.setStatus(R.string.status_search_failed, StatusKind.ERR)
                    LogBridge.append("ERR", result.message)
                }
            }
        }
    }

    /** 把搜索结果的本子 ID 追加到输入框。 */
    fun addSearched() {
        val result = state.result
        if (result == null) {
            state.setStatus(R.string.status_add_no_result, StatusKind.ERR)
            return
        }
        val ids = parseIds(state.idsText)
        if (ids.contains(result.id)) {
            state.setStatus(R.string.status_add_exists, StatusKind.IDLE, result.id)
            return
        }
        state.idsText = (ids + result.id).joinToString(", ")
        state.setStatus(R.string.status_add_ok, StatusKind.OK, result.id)
    }

    /** 加入下载列表：先落盘下载选项与邮件推送，再入队，最后清空输入框。 */
    fun enqueue() {
        val ids = parseIds(state.idsText)
        if (ids.isEmpty()) {
            state.setStatus(R.string.status_need_ids, StatusKind.ERR)
            return
        }
        if (state.mailEnable && (state.mailSender.isBlank() || state.mailPassword.isBlank())) {
            state.setStatus(R.string.status_mail_incomplete, StatusKind.ERR)
            return
        }
        scope.launch {
            val saved = ApiBridge.updateConfig(state.confPatch())
            if (!saved.optBoolean("ok")) {
                state.setStatus(R.string.status_error_hint, StatusKind.ERR, saved.optString("error"))
                return@launch
            }
            when (val result = ApiBridge.queueEnqueue(ids)) {
                is ApiResult.Ok -> {
                    val added = result.value.added
                    if (added.isNotEmpty()) {
                        state.idsText = ""
                        state.setStatus(R.string.status_task_enqueued, StatusKind.OK, added.size.toString())
                    } else {
                        state.setStatus(
                            R.string.status_task_duplicated,
                            StatusKind.ERR,
                            result.value.duplicated.joinToString(", "),
                        )
                    }
                    queue.refresh()
                }

                is ApiResult.Err -> {
                    state.setStatus(R.string.status_error_hint, StatusKind.ERR, result.message)
                    LogBridge.append("ERR", result.message)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 任务中心入口 ----------------
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenTasks) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_tasks))
            }
        }

        // ---------------- 本子 ID ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.idsText,
                onValueChange = { state.idsText = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.label_ids)) },
                placeholder = { Text(stringResource(R.string.hint_ids)) },
                maxLines = 3,
            )
            TextButton(
                onClick = { state.idsText = "" },
                enabled = state.idsText.isNotEmpty(),
            ) {
                Text(stringResource(R.string.btn_clear))
            }
        }

        // ---------------- 下载目录（只读） ----------------
        Column {
            Text(
                text = stringResource(R.string.label_download_dir),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.downloadDir,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        HorizontalDivider()

        // ---------------- 搜索 ID ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchId,
                onValueChange = { state.searchId = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.label_search_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            )
            TextButton(onClick = { doSearch() }, enabled = !state.searching) {
                Text(stringResource(R.string.btn_search))
            }
        }

        // 搜索结果框：文字 + 封面缩略图（3:4）
        val result = state.result
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                if (result != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (result.tags.isNotEmpty()) {
                                stringResource(
                                    R.string.search_info_tags,
                                    result.id, result.pages, result.chapters, result.name, result.tags,
                                )
                            } else {
                                stringResource(
                                    R.string.search_info,
                                    result.id, result.pages, result.chapters, result.name,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (result.url.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.search_link),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { openUrl(context, result.url) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (state.searching) stringResource(R.string.status_searching) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CoverImage(
                url = result?.coverUrl.orEmpty(),
                modifier = Modifier
                    .width(78.dp)
                    .height(104.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { addSearched() }, enabled = result != null) {
                Text(stringResource(R.string.btn_add))
            }
        }

        // ---------------- 生成 PDF + 开始下载 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.toPdf, onCheckedChange = { state.toPdf = it })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.switch_to_pdf), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { enqueue() }) {
                Text(stringResource(R.string.btn_start))
            }
        }
        StatusLine(state.statusRes, state.statusArg, state.statusKind)

        // ---------------- 下载选项（折叠） ----------------
        CollapsibleSection(
            title = stringResource(R.string.tile_download_options),
            expanded = showOptions,
            onToggle = { showOptions = !showOptions },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = state.threadImage,
                    onValueChange = { state.threadImage = it },
                    labelRes = R.string.label_thread_image,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.threadPhoto,
                    onValueChange = { state.threadPhoto = it },
                    labelRes = R.string.label_thread_photo,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.taskConcurrency,
                    onValueChange = { state.taskConcurrency = it },
                    labelRes = R.string.label_task_concurrency,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------------- 邮件推送（折叠） ----------------
        CollapsibleSection(
            title = stringResource(R.string.tile_mail),
            expanded = showMail,
            onToggle = { showMail = !showMail },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.mailEnable, onCheckedChange = { state.mailEnable = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.switch_mail_enable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextFieldRow(
                        value = state.mailServer,
                        onValueChange = { state.mailServer = it },
                        labelRes = R.string.label_mail_server,
                        modifier = Modifier.weight(2f),
                    )
                    TextFieldRow(
                        value = state.mailPort,
                        onValueChange = { state.mailPort = it },
                        labelRes = R.string.label_mail_port,
                        numeric = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextFieldRow(
                    value = state.mailSender,
                    onValueChange = { state.mailSender = it },
                    labelRes = R.string.label_mail_sender,
                )
                TextFieldRow(
                    value = state.mailPassword,
                    onValueChange = { state.mailPassword = it },
                    labelRes = R.string.label_mail_password,
                    password = true,
                )
                TextFieldRow(
                    value = state.mailReceiver,
                    onValueChange = { state.mailReceiver = it },
                    labelRes = R.string.label_mail_receiver,
                )
                TextFieldRow(
                    value = state.mailSubject,
                    onValueChange = { state.mailSubject = it },
                    labelRes = R.string.label_mail_subject,
                )
                TextFieldRow(
                    value = state.mailBody,
                    onValueChange = { state.mailBody = it },
                    labelRes = R.string.label_mail_body,
                )
            }
        }

        // ---------------- 下载日志（折叠，含队列概览） ----------------
        CollapsibleSection(
            title = stringResource(R.string.tile_download_log),
            expanded = showLog,
            onToggle = { showLog = !showLog },
        ) {
            DownloadLogPanel(queue)
        }
    }
}

/**
 * 下载日志面板（队列概览 + 日志文本 + 复制 / 清空）。
 *
 * 单独拆成一个组件是为了**只在展开时才订阅日志**：若在外层读 [`LogBridge.text`]，
 * 每 300ms 一次的合并刷新都会把整张下载页（十几个输入框 + 下面这段最长 400 行的
 * 文本）重组并重新排版一遍，收起日志时也一样、白付这份开销。
 */
@Composable
private fun DownloadLogPanel(queue: QueueState) {
    val context = LocalContext.current
    val logText by LogBridge.text.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QueueOverview(queue)
        Row {
            TextButton(onClick = { copyText(context, logText) }) {
                Text(stringResource(R.string.shell_btn_copy_log))
            }
            TextButton(onClick = { LogBridge.clear() }) {
                Text(stringResource(R.string.shell_btn_clear_log))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                .padding(6.dp)
        ) {
            // SelectionContainer：长按可选中；「复制全部」是整段复制的保底出口
            SelectionContainer {
                Text(
                    text = logText,
                    color = Color(0xFFB9C6D2),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** 队列概览：各状态计数 + 一条粗略的整体进度（与桌面版下载日志上方的概览一致）。 */
@Composable
private fun QueueOverview(queue: QueueState) {
    val stats = queue.stats
    val busy = stats.waiting > 0 || stats.running > 0
    val text = when {
        busy -> stringResource(
            R.string.tasks_overview, stats.total, stats.waiting, stats.running, stats.failed
        )

        stats.total > 0 -> stringResource(R.string.tasks_overview_idle, stats.total)
        else -> stringResource(R.string.tasks_overview_empty)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 折叠分组：标题 + 展开箭头，内容用 [AnimatedVisibility] 展开（不引实验性组件）。 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Box(modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(labelRes), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(2)) },
        modifier = modifier,
        label = { Text(stringResource(labelRes), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun copyText(context: android.content.Context, text: String) {
    val clipboard =
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("jm2pdf log", text))
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        LogBridge.append("ERR", "无法打开链接：$url")
    }
}