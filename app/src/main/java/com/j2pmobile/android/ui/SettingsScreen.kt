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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.AppLocale
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.Updater
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 设置页（从账号页右上角的小齿轮进入，无独立按钮）。
 *
 * 内容与桌面版设置页对齐：**配置管理**（导出 / 导入）、**外观**、**语言**、
 * **账号设置**（自动登录并签到）、**缓存管理**（清除缓存），最后是**关于**
 * （由原帮助页并入，不再有独立路由）。
 *
 * 安卓端差异：
 * - 配置导入导出走 **SAF**（`CreateDocument` / `OpenDocument`）：拿到的是
 *   `content://` URI，Python 打不开，所以文件读写在 Kotlin 侧、Python 只处理文本；
 * - 开发自检与日志面板是**临时**入口，正式版前移除。
 */
@Composable
fun SettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onRecreate: () -> Unit,
    onClearCache: () -> Unit,
    /**
     * 是否有下载任务在跑（导入配置前要确认）。
     *
     * 传的是**函数**而不是布尔值：在外壳层的组合函数里读队列状态会让整个外壳
     * 跟着队列快照重组，而这里只在点「导入」那一刻问一次。
     */
    isDownloadActive: () -> Boolean,
    /** 点「检查更新」：把当前更新通道交给外壳层的更新中心。 */
    onCheckUpdate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var configPath by remember { mutableStateOf("") }
    var downloadDir by remember { mutableStateOf("") }
    var toPdf by remember { mutableStateOf(false) }
    var autoLogin by remember { mutableStateOf(false) }
    var updateChannel by remember { mutableStateOf(Updater.CHANNEL_STABLE) }
    var autoUpdate by remember { mutableStateOf(false) }
    var previewPages by remember { mutableStateOf(true) }

    var statusRes by remember { mutableStateOf(R.string.status_ready) }
    var statusArg by remember { mutableStateOf<String?>(null) }
    var statusKind by remember { mutableStateOf(StatusKind.IDLE) }
    /** 导出时暂存 YAML 文本，等用户选完目标文件再写。 */
    var pendingExport by remember { mutableStateOf<String?>(null) }

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    /** 把局部配置写回 conf.yml；成功后执行 [after]。 */
    fun persist(patch: JSONObject, after: () -> Unit = {}) {
        scope.launch {
            val result = ApiBridge.updateConfig(patch)
            if (!result.optBoolean("ok")) {
                setStatus(R.string.status_error_hint, StatusKind.ERR, result.optString("error"))
            }
            after()
        }
    }

    LaunchedEffect(Unit) {
        val result = ApiBridge.loadConfig()
        if (!result.optBoolean("ok")) {
            setStatus(R.string.status_error_hint, StatusKind.ERR, result.optString("error"))
            return@LaunchedEffect
        }
        configPath = result.optString("config_path")
        val app = result.optJSONObject("config")?.optJSONObject("app")
        if (app != null) {
            downloadDir = app.optString("download_dir")
            toPdf = app.optBoolean("to_pdf", false)
            autoLogin = app.optBoolean("auto_login", false)
            updateChannel = app.optString("update_channel").ifEmpty { Updater.CHANNEL_STABLE }
            autoUpdate = app.optBoolean("auto_update", false)
            previewPages = app.optBoolean("preview_pages", true)
        }
    }

    // ---- SAF：导出 / 导入配置文件 ----
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/yaml")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null) {
            setStatus(R.string.status_export_canceled, StatusKind.IDLE)
            return@rememberLauncherForActivityResult
        }
        if (text == null || writeText(context, uri, text)) {
            setStatus(R.string.status_exported, StatusKind.OK, uri.lastPathSegment ?: "")
        } else {
            setStatus(R.string.status_export_failed, StatusKind.ERR, uri.toString())
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            setStatus(R.string.status_import_canceled, StatusKind.IDLE)
            return@rememberLauncherForActivityResult
        }
        val text = readText(context, uri)
        if (text == null) {
            setStatus(R.string.status_import_failed, StatusKind.ERR, uri.toString())
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when (val result = ApiBridge.importConfigText(text)) {
                is ApiResult.Ok -> {
                    setStatus(R.string.status_imported, StatusKind.OK)
                    // 配置可能改了主题 / 语言：重建 Activity 让整个界面按新配置起来
                    onRecreate()
                }

                is ApiResult.Err ->
                    setStatus(R.string.status_import_failed, StatusKind.ERR, result.message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------- 配置管理 ----------------
        SectionTitle(stringResource(R.string.section_config))
        Text(
            text = stringResource(R.string.desc_config),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MonoText(stringResource(R.string.shell_config_path) + ": " + configPath)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        when (val result = ApiBridge.exportConfigText()) {
                            is ApiResult.Ok -> {
                                pendingExport = result.value
                                exportLauncher.launch("conf.yml")
                            }

                            is ApiResult.Err ->
                                setStatus(R.string.status_export_failed, StatusKind.ERR, result.message)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_export_conf), maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    // 任务进行中导入会与界面看到的进度打架，与桌面版一样先挡住
                    if (isDownloadActive()) {
                        setStatus(R.string.status_busy_restart, StatusKind.ERR)
                        return@OutlinedButton
                    }
                    importLauncher.launch(
                        arrayOf("text/*", "application/x-yaml", "application/octet-stream")
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_import_conf), maxLines = 1)
            }
        }

        // ---------------- 外观 ----------------
        SectionTitle(stringResource(R.string.section_appearance))
        Text(
            text = stringResource(R.string.desc_appearance),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.all.forEach { mode ->
                ChoiceButton(
                    text = stringResource(themeLabelRes(mode)),
                    selected = themeMode == mode,
                    onClick = {
                        if (themeMode == mode) return@ChoiceButton
                        onThemeModeChange(mode)
                        persist(JSONObject().put("app", JSONObject().put("theme_mode", mode))) {
                            setStatus(
                                R.string.status_theme_changed,
                                StatusKind.OK,
                                stringResourceNow(context, themeLabelRes(mode)),
                            )
                        }
                    },
                )
            }
        }

        // ---------------- 语言 ----------------
        SectionTitle(stringResource(R.string.section_language))
        Text(
            text = stringResource(R.string.desc_language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLocale.languages.forEach { code ->
                ChoiceButton(
                    text = AppLocale.nativeName(code),
                    selected = AppLocale.language == code,
                    onClick = {
                        if (AppLocale.language == code) return@ChoiceButton
                        persist(JSONObject().put("app", JSONObject().put("language", code))) {
                            AppLocale.language = code
                            AppLocale.save(context, code)   // 同步原生缓存，避免下次冷启动回退
                            onRecreate()
                        }
                    },
                )
            }
        }

        // ---------------- 账号设置 ----------------
        SectionTitle(stringResource(R.string.section_account_settings))
        Text(
            text = stringResource(R.string.desc_account_settings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.switch_auto_login))
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = autoLogin,
                onCheckedChange = { value ->
                    autoLogin = value
                    persist(JSONObject().put("app", JSONObject().put("auto_login", value))) {
                        setStatus(
                            if (value) R.string.status_auto_login_on else R.string.status_auto_login_off,
                            if (value) StatusKind.OK else StatusKind.IDLE,
                        )
                    }
                },
            )
        }

        // ---------------- 浏览设置 ----------------
        SectionTitle(stringResource(R.string.section_browse))
        Text(
            text = stringResource(R.string.desc_browse),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.switch_preview_pages), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = previewPages,
                onCheckedChange = { value ->
                    previewPages = value
                    persist(JSONObject().put("app", JSONObject().put("preview_pages", value))) {
                        setStatus(
                            if (value) R.string.status_preview_on else R.string.status_preview_off,
                            if (value) StatusKind.OK else StatusKind.IDLE,
                        )
                    }
                },
            )
        }

        // ---------------- 缓存管理 ----------------
        SectionTitle(stringResource(R.string.section_cache))
        Text(
            text = stringResource(R.string.desc_cache),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                onClearCache()
                scope.launch {
                    when (val result = ApiBridge.clearCache()) {
                        is ApiResult.Ok -> setStatus(R.string.status_cache_cleared, StatusKind.OK)
                        is ApiResult.Err -> setStatus(
                            R.string.status_error_hint, StatusKind.ERR, result.message
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_clear_cache))
        }

        // ---------------- 软件更新 ----------------
        SectionTitle(stringResource(R.string.section_update))
        Text(
            text = stringResource(R.string.desc_update),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.label_update_channel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Updater.CHANNEL_STABLE, Updater.CHANNEL_BETA).forEach { channel ->
                ChoiceButton(
                    text = stringResource(channelLabelRes(channel)),
                    selected = updateChannel == channel,
                    onClick = {
                        if (updateChannel == channel) return@ChoiceButton
                        updateChannel = channel
                        persist(
                            JSONObject().put("app", JSONObject().put("update_channel", channel))
                        ) {
                            setStatus(
                                R.string.status_update_channel,
                                StatusKind.OK,
                                stringResourceNow(context, channelLabelRes(channel)),
                            )
                        }
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onCheckUpdate(updateChannel) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_check_update), maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.switch_auto_update), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = autoUpdate,
                onCheckedChange = { value ->
                    autoUpdate = value
                    persist(JSONObject().put("app", JSONObject().put("auto_update", value))) {
                        setStatus(
                            if (value) R.string.status_auto_update_on
                            else R.string.status_auto_update_off,
                            if (value) StatusKind.OK else StatusKind.IDLE,
                        )
                    }
                },
            )
        }

        // ---------------- 关于（并入设置底部） ----------------
        Spacer(Modifier.height(4.dp))
        SectionTitle(stringResource(R.string.about_title))
        AboutRow(stringResource(R.string.help_version), stringResource(R.string.about_version_value))
        AboutRow(stringResource(R.string.help_author), stringResource(R.string.about_author_value))
        AboutRow(
            label = stringResource(R.string.help_project),
            value = stringResource(R.string.about_project_value),
            onClick = { openUrl(context, context.getString(R.string.about_project_value)) },
        )
        AboutRow(stringResource(R.string.help_license), stringResource(R.string.about_license_value))

        Text(
            text = stringResource(R.string.help_disclaimer_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(
                R.string.help_disclaimer,
                stringResource(R.string.about_license_value),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.help_issue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = { openUrl(context, context.getString(R.string.about_issues_value)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.help_issue_link))
        }

        StatusLine(statusRes, statusArg, statusKind)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 主题 / 语言的单选按钮：选中即实心，未选中为描边（不引入实验性组件）。 */
@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text) }
    }
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun themeLabelRes(mode: String): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    else -> R.string.theme_system
}

/** 更新通道的中文标签：稳定版 / 公测版。 */
private fun channelLabelRes(channel: String): Int = when (channel) {
    Updater.CHANNEL_BETA -> R.string.update_channel_beta
    else -> R.string.update_channel_stable
}

/** 在非组合上下文里取资源文案（状态提示要带上「切换成了什么」）。 */
private fun stringResourceNow(context: Context, resId: Int): String = context.getString(resId)

/** 往 SAF 选定的文件写文本；失败返回 false。 */
private fun writeText(context: Context, uri: Uri, text: String): Boolean = try {
    context.contentResolver.openOutputStream(uri, "wt")?.use {
        it.write(text.toByteArray(Charsets.UTF_8))
    }
    true
} catch (_: Throwable) {
    false
}

/** 读 SAF 选定文件的文本；失败返回 null。 */
private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use {
        it.readBytes().toString(Charsets.UTF_8)
    }
} catch (_: Throwable) {
    null
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        LogBridge.append("ERR", "无法打开链接：$url")
    }
}

