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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.R
import com.j2pmobile.android.SyncBridge
import com.j2pmobile.android.SyncCounts
import com.j2pmobile.android.SyncException
import com.j2pmobile.android.SyncLocalSnapshot
import com.j2pmobile.android.SyncSnapshot
import com.j2pmobile.android.SyncUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 登录 / 注册 / 找回密码 三个子界面。 */
enum class SyncAuthMode { LOGIN, REGISTER, RESET }

/** 同步页的六个操作。 */
enum class SyncOp {
    PULL_MERGE, PULL_OVERWRITE, PUSH_MERGE, PUSH_OVERWRITE, CLEAR_LOCAL, CLEAR_CLOUD
}

/**
 * 同步页的可变状态（由 [AppShell] 持有）。
 *
 * 状态放在外壳层：**退出页面重新进入只刷新记录数据，不重置工作状态**（正在进行的操作继续）。
 */
class SyncState {
    /** 本地登录态是否已读过一次。 */
    var sessionLoaded by mutableStateOf(false)

    // ---- 同步账号（网站账号）
    var loggedIn by mutableStateOf(false)
    var token by mutableStateOf("")
    var user by mutableStateOf<SyncUser?>(null)

    // ---- 登录 / 注册 / 找回
    var authMode by mutableStateOf(SyncAuthMode.LOGIN)
    var busy by mutableStateOf(false)
    var statusText by mutableStateOf<String?>(null)
    var statusError by mutableStateOf(false)

    var accountInput by mutableStateOf("")
    var passwordInput by mutableStateOf("")
    var remember by mutableStateOf(false)

    var regUsername by mutableStateOf("")
    var regNickname by mutableStateOf("")
    var regEmail by mutableStateOf("")
    var regCode by mutableStateOf("")
    var regPassword by mutableStateOf("")
    var regPassword2 by mutableStateOf("")
    var cooling by mutableStateOf(0)

    var resetEmail by mutableStateOf("")
    var resetCode by mutableStateOf("")
    var resetPassword by mutableStateOf("")
    var resetPassword2 by mutableStateOf("")
    var resetCooling by mutableStateOf(0)

    // ---- 数据对比
    var localSnapshot by mutableStateOf<SyncLocalSnapshot?>(null)
    var cloudSnapshot by mutableStateOf<SyncSnapshot?>(null)
    var localCorrupted by mutableStateOf(false)
    var localError by mutableStateOf<String?>(null)
    var cloudError by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)

    // ---- 操作（后台进行）
    var opRunning by mutableStateOf(false)
    var opLabel by mutableStateOf("")
    var resultText by mutableStateOf<String?>(null)
    var resultError by mutableStateOf(false)

    /** 手动刷新计数（+1 触发一次刷新）。 */
    var refreshSeq by mutableStateOf(0)

    fun setStatus(text: String?, error: Boolean = false) {
        statusText = text
        statusError = error
    }
}

private data class SyncOutcome(val text: String, val error: Boolean)

/**
 * 同步页：登录前只展示登录 / 注册 / 找回界面；登录后展示本地与云端的对比与六个操作。
 */
@Composable
fun SyncScreen(state: SyncState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingOp by remember { mutableStateOf<SyncOp?>(null) }

    // 首次进入读一次同步账号登录态（读加密文件，不走网络）
    LaunchedEffect(Unit) {
        if (state.sessionLoaded) return@LaunchedEffect
        when (val r = ApiBridge.syncSessionLoad()) {
            is ApiResult.Ok -> {
                state.loggedIn = r.value.loggedIn
                state.token = r.value.token
                state.user = r.value.user
            }

            is ApiResult.Err -> state.setStatus(r.message, true)
        }
        state.sessionLoaded = true
    }

    // 进页面 / 登录态变化 / 手动刷新：重新拉取本地与云端数量
    LaunchedEffect(state.loggedIn, state.refreshSeq) {
        refreshAll(state)
    }

    fun confirmOp(op: SyncOp) {
        pendingOp = op
    }

    fun launchOp(op: SyncOp) {
        pendingOp = null
        if (state.opRunning) return
        state.opRunning = true
        state.opLabel = opLabel(context, op)
        state.resultText = null
        scope.launch {
            val outcome = executeOp(state, op, context)
            state.opRunning = false
            state.resultText = outcome.text
            state.resultError = outcome.error
            refreshAll(state)
        }
    }

    fun logout() {
        scope.launch {
            ApiBridge.syncSessionClear()
            state.loggedIn = false
            state.token = ""
            state.user = null
            state.cloudSnapshot = null
            state.cloudError = null
            state.resultText = null
            state.setStatus(null)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.loggedIn) {
                item {
                    AuthSection(state = state, onLogin = { account, password ->
                        scope.launch { doLogin(context, state, account, password) }
                    }, onRegister = {
                        scope.launch { doRegister(context, state) }
                    }, onReset = {
                        scope.launch { doReset(context, state) }
                    }, onSendRegCode = {
                        scope.launch {
                            if (sendCode(context, state, state.regEmail, true)) {
                                startCooling(scope) { state.cooling = it }
                            }
                        }
                    }, onSendResetCode = {
                        scope.launch {
                            if (sendCode(context, state, state.resetEmail, false)) {
                                startCooling(scope) { state.resetCooling = it }
                            }
                        }
                    })
                }
            } else {
                item {
                    SyncPanel(
                        state = state,
                        onRefresh = { state.refreshSeq += 1 },
                        onLogout = { logout() },
                        onOp = { confirmOp(it) },
                    )
                }
            }
            item { StatusLine(state.statusText, state.statusError) }
        }
    }

    pendingOp?.let { op ->
        ConfirmDialog(
            message = confirmMessage(context, op, state),
            onConfirm = { launchOp(op) },
            onDismiss = { pendingOp = null },
        )
    }

    state.resultText?.let { text ->
        ResultDialog(
            text = text,
            error = state.resultError,
            onClose = { state.resultText = null },
        )
    }
}

// ---------------------------------------------------------------- 数据刷新与操作

private suspend fun refreshAll(state: SyncState) {
    if (state.opRunning) return
    state.refreshing = true
    when (val r = ApiBridge.syncLocalSnapshot()) {
        is ApiResult.Ok -> {
            state.localSnapshot = r.value
            state.localCorrupted = r.value.corrupted
            state.localError = null
        }

        is ApiResult.Err -> {
            state.localSnapshot = null
            state.localError = r.message
        }
    }
    if (state.loggedIn && state.token.isNotEmpty()) {
        try {
            state.cloudSnapshot = SyncBridge.snapshot(state.token)
            state.cloudError = null
        } catch (e: SyncException) {
            state.cloudSnapshot = null
            state.cloudError = e.message
            if (e.status == 401) {
                ApiBridge.syncSessionClear()
                state.loggedIn = false
                state.token = ""
                state.user = null
            }
        } catch (e: Throwable) {
            state.cloudSnapshot = null
            state.cloudError = e.message ?: "network error"
        }
    } else {
        state.cloudSnapshot = null
        state.cloudError = null
    }
    state.refreshing = false
}

private suspend fun executeOp(state: SyncState, op: SyncOp, ctx: Context): SyncOutcome {
    val local = state.localSnapshot
    val cloud = state.cloudSnapshot
    val lh = local?.counts?.history ?: 0
    val lt = local?.counts?.tags ?: 0
    val ch = cloud?.counts?.history ?: 0
    val ct = cloud?.counts?.tags ?: 0
    return try {
        when (op) {
            SyncOp.PULL_MERGE, SyncOp.PULL_OVERWRITE -> {
                val mode = if (op == SyncOp.PULL_MERGE) "merge" else "overwrite"
                val json = cloudToJson(cloud)
                when (val r = ApiBridge.syncApplyCloud(mode, json)) {
                    is ApiResult.Ok -> when {
                        !r.value.changed -> SyncOutcome(ctx.getString(R.string.sync_result_nochange), false)
                        op == SyncOp.PULL_MERGE -> SyncOutcome(
                            ctx.getString(R.string.sync_result_merge,
                                r.value.counts.history, r.value.counts.tags), false)
                        else -> SyncOutcome(
                            ctx.getString(R.string.sync_result_overwrite_local,
                                ch, ct, lh, lt), false)
                    }

                    is ApiResult.Err -> SyncOutcome(
                        ctx.getString(R.string.sync_result_failed, r.message), true)
                }
            }

            SyncOp.PUSH_MERGE, SyncOp.PUSH_OVERWRITE -> {
                val mode = if (op == SyncOp.PUSH_MERGE) "merge" else "overwrite"
                val snap = local
                val r = SyncBridge.push(
                    state.token, mode,
                    snap?.folders ?: JSONArray(), snap?.tags ?: JSONArray(),
                    snap?.history ?: JSONArray())
                when {
                    !r.changed -> SyncOutcome(ctx.getString(R.string.sync_result_nochange), false)
                    op == SyncOp.PUSH_MERGE -> SyncOutcome(
                        ctx.getString(R.string.sync_result_merge, r.counts.history, r.counts.tags), false)
                    else -> SyncOutcome(
                        ctx.getString(R.string.sync_result_overwrite_cloud, lh, lt, ch, ct), false)
                }
            }

            SyncOp.CLEAR_LOCAL -> when (val r = ApiBridge.syncClearLocal()) {
                is ApiResult.Ok -> if (!r.value.changed) {
                    SyncOutcome(ctx.getString(R.string.sync_result_nochange), false)
                } else {
                    SyncOutcome(ctx.getString(R.string.sync_result_clear,
                        r.value.cleared.history, r.value.cleared.tags), false)
                }

                is ApiResult.Err -> SyncOutcome(
                    ctx.getString(R.string.sync_result_failed, r.message), true)
            }

            SyncOp.CLEAR_CLOUD -> {
                val r = SyncBridge.clear(state.token)
                if (!r.changed) SyncOutcome(ctx.getString(R.string.sync_result_nochange), false)
                else SyncOutcome(ctx.getString(R.string.sync_result_clear,
                    r.cleared.history, r.cleared.tags), false)
            }
        }
    } catch (e: Throwable) {
        SyncOutcome(ctx.getString(R.string.sync_result_failed, e.message ?: ""), true)
    }
}

private fun cloudToJson(snap: SyncSnapshot?): String {
    val o = JSONObject()
    o.put("folders", snap?.folders ?: JSONArray())
    o.put("tags", snap?.tags ?: JSONArray())
    o.put("history", snap?.history ?: JSONArray())
    return o.toString()
}

private fun unionCount(a: JSONArray?, b: JSONArray?, key: String): Int {
    val set = HashSet<String>()
    listOf(a, b).forEach { arr ->
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val value = arr.optJSONObject(i)?.optString(key).orEmpty()
                if (value.isNotEmpty()) set.add(value)
            }
        }
    }
    return set.size
}

private fun opLabel(ctx: Context, op: SyncOp): String = ctx.getString(
    when (op) {
        SyncOp.PULL_MERGE -> R.string.sync_op_pull_merge
        SyncOp.PULL_OVERWRITE -> R.string.sync_op_pull_overwrite
        SyncOp.PUSH_MERGE -> R.string.sync_op_push_merge
        SyncOp.PUSH_OVERWRITE -> R.string.sync_op_push_overwrite
        SyncOp.CLEAR_LOCAL -> R.string.sync_op_clear_local
        SyncOp.CLEAR_CLOUD -> R.string.sync_op_clear_cloud
    }
)

private fun confirmMessage(ctx: Context, op: SyncOp, state: SyncState): String {
    val lh = state.localSnapshot?.counts?.history ?: 0
    val lt = state.localSnapshot?.counts?.tags ?: 0
    val ch = state.cloudSnapshot?.counts?.history ?: 0
    val ct = state.cloudSnapshot?.counts?.tags ?: 0
    val uh = unionCount(state.localSnapshot?.history, state.cloudSnapshot?.history, "album_id")
    val ut = unionCount(state.localSnapshot?.tags, state.cloudSnapshot?.tags, "name")
    return when (op) {
        SyncOp.PULL_MERGE, SyncOp.PUSH_MERGE ->
            ctx.getString(R.string.sync_confirm_merge, uh, ut)
        SyncOp.PULL_OVERWRITE ->
            ctx.getString(R.string.sync_confirm_overwrite_local, ch, ct, lh, lt)
        SyncOp.PUSH_OVERWRITE ->
            ctx.getString(R.string.sync_confirm_overwrite_cloud, lh, lt, ch, ct)
        SyncOp.CLEAR_LOCAL ->
            ctx.getString(R.string.sync_confirm_clear, lh, lt)
        SyncOp.CLEAR_CLOUD ->
            ctx.getString(R.string.sync_confirm_clear, ch, ct)
    }
}

// ---------------------------------------------------------------- 登录 / 注册 / 找回

private suspend fun doLogin(ctx: Context, state: SyncState, account: String, password: String) {
    if (state.busy) return
    if (account.isEmpty() || password.isEmpty()) {
        state.setStatus(ctx.getString(R.string.sync_auth_need_input), true)
        return
    }
    state.busy = true
    try {
        val auth = SyncBridge.login(account, password, state.remember)
        saveSession(ctx, state, auth.token, auth.user)
        state.passwordInput = ""
        state.accountInput = ""
    } catch (e: Throwable) {
        state.setStatus(e.message ?: "login failed", true)
    }
    state.busy = false
}

private suspend fun doRegister(ctx: Context, state: SyncState) {
    if (state.busy) return
    if (state.regUsername.isBlank() || state.regEmail.isBlank() ||
        state.regCode.isBlank() || state.regPassword.isEmpty()) {
        state.setStatus(ctx.getString(R.string.sync_auth_need_input), true)
        return
    }
    if (state.regPassword != state.regPassword2) {
        state.setStatus(ctx.getString(R.string.sync_password_mismatch), true)
        return
    }
    state.busy = true
    try {
        val auth = SyncBridge.register(
            state.regUsername.trim(), state.regPassword, state.regEmail.trim(),
            state.regCode.trim(), state.regNickname.trim())
        saveSession(ctx, state, auth.token, auth.user)
        state.setStatus(ctx.getString(R.string.sync_register_success), false)
    } catch (e: Throwable) {
        state.setStatus(e.message ?: "register failed", true)
    }
    state.busy = false
}

private suspend fun doReset(ctx: Context, state: SyncState) {
    if (state.busy) return
    if (state.resetEmail.isBlank() || state.resetCode.isBlank() || state.resetPassword.isEmpty()) {
        state.setStatus(ctx.getString(R.string.sync_auth_need_input), true)
        return
    }
    if (state.resetPassword != state.resetPassword2) {
        state.setStatus(ctx.getString(R.string.sync_password_mismatch), true)
        return
    }
    state.busy = true
    try {
        SyncBridge.resetPassword(
            state.resetEmail.trim(), state.resetCode.trim(), state.resetPassword)
        state.setStatus(ctx.getString(R.string.sync_reset_success), false)
        state.authMode = SyncAuthMode.LOGIN
        state.resetCode = ""
        state.resetPassword = ""
        state.resetPassword2 = ""
    } catch (e: Throwable) {
        state.setStatus(e.message ?: "reset failed", true)
    }
    state.busy = false
}

private suspend fun sendCode(
    ctx: Context,
    state: SyncState,
    email: String,
    register: Boolean,
): Boolean {
    if (email.isBlank()) {
        state.setStatus(ctx.getString(R.string.sync_auth_need_input), true)
        return false
    }
    return try {
        val msg = if (register) SyncBridge.registerCode(email.trim())
        else SyncBridge.resetCode(email.trim())
        state.setStatus(msg.ifEmpty { ctx.getString(R.string.sync_code_sent) }, false)
        true
    } catch (e: Throwable) {
        state.setStatus(e.message ?: "send failed", true)
        false
    }
}

private fun startCooling(scope: kotlinx.coroutines.CoroutineScope, setter: (Int) -> Unit) {
    scope.launch {
        var left = 60
        while (left > 0) {
            setter(left)
            delay(1000)
            left--
        }
        setter(0)
    }
}

private suspend fun saveSession(ctx: Context, state: SyncState, token: String, user: SyncUser) {
    val userJson = JSONObject()
        .put("id", user.id)
        .put("username", user.username)
        .put("nickname", user.nickname)
        .put("email", user.email)
        .toString()
    ApiBridge.syncSessionSave(token, userJson)
    state.loggedIn = true
    state.token = token
    state.user = user
    state.setStatus(
        ctx.getString(R.string.sync_login_success, user.nickname.ifEmpty { user.username }), false)
}

// ---------------------------------------------------------------- 界面：登录 / 注册 / 找回

@Composable
private fun AuthSection(
    state: SyncState,
    onLogin: (String, String) -> Unit,
    onRegister: () -> Unit,
    onReset: () -> Unit,
    onSendRegCode: () -> Unit,
    onSendResetCode: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.authMode) {
            SyncAuthMode.LOGIN -> {
                Text(stringResource(R.string.sync_login_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sync_login_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = state.accountInput,
                    onValueChange = { state.accountInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_username_or_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Text(stringResource(R.string.hint_login_id),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = { state.passwordInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onLogin(state.accountInput.trim(), state.passwordInput) }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.remember, onCheckedChange = { state.remember = it })
                    Text(stringResource(R.string.hint_remember),
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onLogin(state.accountInput.trim(), state.passwordInput) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_login)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        state.authMode = SyncAuthMode.RESET
                        state.setStatus(null)
                    }) { Text(stringResource(R.string.sync_link_forgot)) }
                    TextButton(onClick = {
                        state.authMode = SyncAuthMode.REGISTER
                        state.setStatus(null)
                    }) { Text(stringResource(R.string.sync_link_register)) }
                }
            }

            SyncAuthMode.REGISTER -> {
                Text(stringResource(R.string.sync_register_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sync_register_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field(state.regUsername, { state.regUsername = it },
                    stringResource(R.string.label_username_id))
                Text(stringResource(R.string.hint_username_id),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field(state.regNickname, { state.regNickname = it },
                    stringResource(R.string.label_nickname_optional))
                Text(stringResource(R.string.hint_nickname),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmailWithCode(
                    email = state.regEmail,
                    onEmail = { state.regEmail = it },
                    code = state.regCode,
                    onCode = { state.regCode = it },
                    cooling = state.cooling,
                    onSend = onSendRegCode,
                )
                Text(stringResource(R.string.hint_code),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordField(state.regPassword, { state.regPassword = it },
                    stringResource(R.string.label_password))
                Text(stringResource(R.string.hint_password),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordField(state.regPassword2, { state.regPassword2 = it },
                    stringResource(R.string.label_confirm_password))
                Button(
                    onClick = onRegister,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_register)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.sync_have_account),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        state.authMode = SyncAuthMode.LOGIN
                        state.setStatus(null)
                    }) { Text(stringResource(R.string.sync_link_login)) }
                }
            }

            SyncAuthMode.RESET -> {
                Text(stringResource(R.string.sync_reset_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sync_reset_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmailWithCode(
                    email = state.resetEmail,
                    onEmail = { state.resetEmail = it },
                    code = state.resetCode,
                    onCode = { state.resetCode = it },
                    cooling = state.resetCooling,
                    onSend = onSendResetCode,
                )
                PasswordField(state.resetPassword, { state.resetPassword = it },
                    stringResource(R.string.label_new_password))
                PasswordField(state.resetPassword2, { state.resetPassword2 = it },
                    stringResource(R.string.label_confirm_new_password))
                Button(
                    onClick = onReset,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_reset_password)) }
                TextButton(onClick = {
                    state.authMode = SyncAuthMode.LOGIN
                    state.setStatus(null)
                }) { Text(stringResource(R.string.sync_link_back_login)) }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
    )
}

@Composable
private fun EmailWithCode(
    email: String,
    onEmail: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    cooling: Int,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmail,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.label_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedButton(onClick = onSend, enabled = cooling <= 0) {
            Text(
                if (cooling > 0) stringResource(R.string.btn_resend_code, cooling)
                else stringResource(R.string.btn_send_code),
                maxLines = 1,
            )
        }
    }
    Text(stringResource(R.string.hint_email_unique),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = code,
        onValueChange = onCode,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.label_email_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

// ---------------------------------------------------------------- 界面：同步面板

@Composable
private fun SyncPanel(
    state: SyncState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onOp: (SyncOp) -> Unit,
) {
    val cloudReady = state.cloudSnapshot != null && state.cloudError == null
    val localReady = state.localSnapshot != null && !state.localCorrupted &&
        state.localError == null
    val idle = !state.opRunning && !state.refreshing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.sync_logged_in_as,
                    state.user?.let { it.nickname.ifEmpty { it.username } }.orEmpty(),
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = idle) {
                Text(stringResource(R.string.sync_refresh))
            }
            TextButton(onClick = onLogout) {
                Text(stringResource(R.string.btn_logout))
            }
        }

        if (state.opRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.sync_op_running, state.opLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 两个对比卡片
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountCard(
                title = stringResource(R.string.sync_local_card),
                counts = state.localSnapshot?.counts,
                note = when {
                    state.localCorrupted -> stringResource(R.string.sync_corrupted)
                    state.localError != null ->
                        stringResource(R.string.sync_local_failed, state.localError.orEmpty())
                    state.localSnapshot == null -> stringResource(R.string.sync_loading)
                    else -> null
                },
                error = state.localCorrupted || state.localError != null,
                modifier = Modifier.weight(1f),
            )
            CountCard(
                title = stringResource(R.string.sync_cloud_card),
                counts = state.cloudSnapshot?.counts,
                note = when {
                    state.cloudError != null ->
                        stringResource(R.string.sync_cloud_failed, state.cloudError.orEmpty())
                    state.cloudSnapshot == null -> stringResource(R.string.sync_loading)
                    else -> null
                },
                error = state.cloudError != null,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        // 六个操作：本地相关两两成对
        OpRow(
            left = SyncOp.PULL_MERGE, right = SyncOp.PULL_OVERWRITE,
            leftEnabled = cloudReady && localReady && idle,
            rightEnabled = cloudReady && localReady && idle,
            onOp = onOp,
        )
        OpRow(
            left = SyncOp.PUSH_MERGE, right = SyncOp.PUSH_OVERWRITE,
            leftEnabled = cloudReady && localReady && idle,
            rightEnabled = cloudReady && localReady && idle,
            onOp = onOp,
        )
        OpRow(
            left = SyncOp.CLEAR_LOCAL, right = SyncOp.CLEAR_CLOUD,
            leftEnabled = localReady && idle,
            rightEnabled = cloudReady && idle,
            onOp = onOp,
        )
    }
}

@Composable
private fun OpRow(
    left: SyncOp,
    right: SyncOp,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
    onOp: (SyncOp) -> Unit,
) {
    val danger = setOf(SyncOp.CLEAR_LOCAL, SyncOp.CLEAR_CLOUD)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OpButton(left, leftEnabled, danger.contains(left), Modifier.weight(1f), onOp)
        OpButton(right, rightEnabled, danger.contains(right), Modifier.weight(1f), onOp)
    }
}

@Composable
private fun OpButton(
    op: SyncOp,
    enabled: Boolean,
    danger: Boolean,
    modifier: Modifier,
    onOp: (SyncOp) -> Unit,
) {
    val label = stringResource(
        when (op) {
            SyncOp.PULL_MERGE -> R.string.sync_op_pull_merge
            SyncOp.PULL_OVERWRITE -> R.string.sync_op_pull_overwrite
            SyncOp.PUSH_MERGE -> R.string.sync_op_push_merge
            SyncOp.PUSH_OVERWRITE -> R.string.sync_op_push_overwrite
            SyncOp.CLEAR_LOCAL -> R.string.sync_op_clear_local
            SyncOp.CLEAR_CLOUD -> R.string.sync_op_clear_cloud
        }
    )
    if (danger) {
        Button(
            onClick = { onOp(op) },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White,
            ),
            modifier = modifier,
        ) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = { onOp(op) }, enabled = enabled, modifier = modifier) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun CountCard(
    title: String,
    counts: SyncCounts?,
    note: String?,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (counts != null && !error) {
            Text(
                text = stringResource(
                    R.string.sync_counts, counts.history, counts.tags, counts.folders),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String?, error: Boolean) {
    if (text.isNullOrEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
    )
}

@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.btn_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun ResultDialog(text: String, error: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.sync_result_title)) },
        text = {
            Text(
                text = text,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.btn_confirm)) }
        },
    )
}