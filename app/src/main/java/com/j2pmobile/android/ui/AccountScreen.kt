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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AccountProfile
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.CheckinResult
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import com.j2pmobile.android.ui.components.CoverImage
import kotlinx.coroutines.launch

/** 签到结果码（与 `core/checkin.py` 一致）。 */
private const val CHECKIN_SUCCESS = 0
private const val CHECKIN_ALREADY = 1

/** 「已登录账号」框的高度与条目高度（与桌面版一致）。 */
private val SAVED_ACCOUNTS_HEIGHT = 140.dp
private val AVATAR_SIZE = 72.dp

/**
 * 账号页的可变状态（由 [AppShell] 持有，切走再回来不会丢）。
 *
 * 与桌面版一致：未登录（或点过「切换账号」）时展示登录界面；登录后展示头像 / 昵称 /
 * 账号状态 / 收藏预览与四个功能按钮。
 */
class AccountState {

    /** 本地账号状态是否已读过一次。 */
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(true)

    var profile by mutableStateOf<AccountProfile?>(null)
    var accounts by mutableStateOf<List<String>>(emptyList())

    /** 「切换账号」：仍是登录态，但展示登录界面（含已登录账号列表）。 */
    var pickerMode by mutableStateOf(false)

    /** 已登录账号列表里被点中的条目；只有选中的条目才显示勾 / 叉按钮。 */
    var selectedAccount by mutableStateOf<String?>(null)

    var usernameInput by mutableStateOf("")
    var passwordInput by mutableStateOf("")
    var loggingIn by mutableStateOf(false)
    var checkingIn by mutableStateOf(false)

    // ---- 收藏预览（null = 还没取过）
    var preview by mutableStateOf<List<AlbumItem>?>(null)
    var previewError by mutableStateOf<String?>(null)
    var favoritesExpanded by mutableStateOf(true)

    var statusRes by mutableStateOf(R.string.status_ready)
    var statusArg by mutableStateOf<String?>(null)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    val loggedIn: Boolean get() = profile != null

    /** 展示登录界面还是已登录界面。 */
    val showLogin: Boolean get() = profile == null || pickerMode

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }
}

/**
 * 账号页：登录 / 账号状态 / 签到 / 切换与退出 / 收藏预览。
 *
 * 与桌面版一致：
 * - 未登录或「切换账号」时是登录界面（提示语 + 账号 + 密码 + 登录按钮 + 已登录账号框）；
 * - 登录后是头像 + 昵称 + 等级 / 经验 / 收藏 / J 币，加折叠的收藏预览与四个功能按钮；
 * - 签到完成后弹窗展示当月 / 连续天数与当天奖励，并顺手刷新账号状态（签到改 J 币与经验）。
 *
 * 安卓端差异：
 * - **「已登录账号」的勾 / 叉改为「点击条目选中（高亮）后才显示」**（用户 2026-10-05 指定，
 *   取代桌面版的「鼠标悬浮才显示」——手机没有 hover）；
 * - 刷新按钮从顶栏移到页面内右上角（顶栏在外壳层，账号页已有齿轮，再挤一个不划算）；
 * - 头像与收藏封面都走 Kotlin 原生取图 + `LruCache`（桌面版是 Python 取字节）。
 */
@Composable
fun AccountScreen(
    state: AccountState,
    onOpenAlbum: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var checkinResult by remember { mutableStateOf<CheckinResult?>(null) }
    var checkinError by remember { mutableStateOf<String?>(null) }

    // 首次进入读一次本地账号状态（读加密文件，不走网络）
    LaunchedEffect(Unit) {
        if (state.loaded) return@LaunchedEffect
        state.loading = true
        when (val result = ApiBridge.accountState()) {
            is ApiResult.Ok -> {
                state.profile = result.value.profile
                state.accounts = result.value.accounts
            }

            is ApiResult.Err -> state.setStatus(R.string.status_error_hint, StatusKind.ERR, result.message)
        }
        state.loaded = true
        state.loading = false
    }

    // 收藏预览：换了账号（profile 变成另一个用户名）且还没取过时拉一次
    LaunchedEffect(state.profile?.username, state.pickerMode) {
        if (state.profile == null || state.pickerMode) return@LaunchedEffect
        if (state.preview != null || state.previewError != null) return@LaunchedEffect
        when (val result = ApiBridge.favoritePreview()) {
            is ApiResult.Ok -> state.preview = result.value
            is ApiResult.Err -> {
                state.preview = emptyList()
                state.previewError = result.message
            }
        }
    }

    /** 重读本地账号状态（不走网络）；[resetPreview] 为真时把收藏预览也清掉重取。 */
    fun reloadAccount(resetPreview: Boolean) {
        scope.launch {
            when (val result = ApiBridge.accountState()) {
                is ApiResult.Ok -> {
                    state.profile = result.value.profile
                    state.accounts = result.value.accounts
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_error_hint, StatusKind.ERR, result.message
                )
            }
            if (resetPreview) {
                state.preview = null
                state.previewError = null
            }
        }
    }

    fun login() {
        val username = state.usernameInput.trim()
        val password = state.passwordInput
        if (username.isEmpty() || password.isEmpty()) {
            state.setStatus(R.string.status_login_need_input, StatusKind.ERR)
            return
        }
        if (state.loggingIn) return
        state.loggingIn = true
        state.setStatus(R.string.status_logging_in, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.accountLogin(username, password)) {
                is ApiResult.Ok -> {
                    state.passwordInput = ""
                    state.usernameInput = ""
                    state.pickerMode = false
                    state.selectedAccount = null
                    state.profile = result.value
                    state.preview = null
                    state.previewError = null
                    state.setStatus(R.string.status_login_ok, StatusKind.OK, result.value.nickname)
                    // 账号列表要跟着变（新账号追加、旧记录标记为当前）
                    when (val refreshed = ApiBridge.accountState()) {
                        is ApiResult.Ok -> state.accounts = refreshed.value.accounts
                        is ApiResult.Err -> Unit
                    }
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_login_failed, StatusKind.ERR, result.message
                )
            }
            state.loggingIn = false
        }
    }

    fun switchAccount(username: String) {
        if (state.loggingIn) return
        state.loggingIn = true
        state.selectedAccount = null
        state.setStatus(R.string.status_logging_in, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.accountSwitch(username)) {
                is ApiResult.Ok -> {
                    state.pickerMode = false
                    state.profile = result.value
                    state.preview = null
                    state.previewError = null
                    state.setStatus(R.string.status_login_ok, StatusKind.OK, result.value.nickname)
                    reloadAccount(false)
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_login_failed, StatusKind.ERR, result.message
                )
            }
            state.loggingIn = false
        }
    }

    fun removeAccount(username: String) {
        scope.launch {
            when (val result = ApiBridge.accountRemove(username)) {
                is ApiResult.Ok -> {
                    state.selectedAccount = null
                    state.setStatus(R.string.status_account_removed, StatusKind.OK, username)
                    // 清掉的正是当前账号：一并回到登录界面（Kotlin 侧只重置界面状态）
                    if (result.value.wasCurrent) {
                        state.profile = null
                        state.pickerMode = false
                    }
                    reloadAccount(true)
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_account_remove_failed, StatusKind.ERR, result.message
                )
            }
        }
    }

    /** 签到：成功后弹窗展示统计，并刷新账号状态（签到会改 J 币与经验）。 */
    fun checkin() {
        if (state.checkingIn) return
        state.checkingIn = true
        state.setStatus(R.string.status_checking_in, StatusKind.IDLE)
        scope.launch {
            when (val result = ApiBridge.accountCheckin()) {
                is ApiResult.Ok -> {
                    checkinError = null
                    checkinResult = result.value
                    state.setStatus(R.string.status_checkin_done, StatusKind.OK)
                    when (val refreshed = ApiBridge.accountRefreshProfile()) {
                        is ApiResult.Ok -> state.profile = refreshed.value
                        is ApiResult.Err -> Unit
                    }
                }

                is ApiResult.Err -> {
                    checkinResult = null
                    checkinError = result.message
                    state.setStatus(R.string.status_checkin_failed, StatusKind.ERR, result.message)
                }
            }
            state.checkingIn = false
        }
    }

    /** 刷新账号数据：重新读账号状态 + 重新拉头像 / 状态 / 收藏预览。 */
    fun refreshAccount() {
        state.setStatus(R.string.status_account_refreshing, StatusKind.IDLE)
        state.preview = null
        state.previewError = null
        scope.launch {
            when (val result = ApiBridge.accountRefreshProfile()) {
                is ApiResult.Ok -> {
                    state.profile = result.value
                    state.setStatus(R.string.status_account_refreshed, StatusKind.OK)
                }

                is ApiResult.Err -> state.setStatus(
                    R.string.status_account_refresh_failed, StatusKind.ERR, result.message
                )
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 用 LazyColumn 而不是「Column + verticalScroll」：可视区外的内容不参与组合与布局，
        // 也让下面几块各自独立重组（签到状态变化不会连带重排收藏预览）。
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.showLogin) {
                item {
                    LoginView(
                        state = state,
                        onLogin = { login() },
                        onSwitch = { switchAccount(it) },
                        onRemove = { removeAccount(it) },
                    )
                }
            } else {
                item { LoggedHeader(state = state, onRefresh = { refreshAccount() }) }
                item { HorizontalDivider() }
                item {
                    FavoritesSection(
                        expanded = state.favoritesExpanded,
                        onToggleExpanded = { state.favoritesExpanded = !state.favoritesExpanded },
                        items = state.preview,
                        error = state.previewError,
                        onOpenFavorites = onOpenFavorites,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
                item {
                    LoggedActions(
                        checkingIn = state.checkingIn,
                        onCheckin = { checkin() },
                        onSwitchAccount = {
                            state.pickerMode = true
                            state.selectedAccount = null
                        },
                        onLogout = {
                            scope.launch {
                                when (val result = ApiBridge.accountLogout()) {
                                    is ApiResult.Ok -> {
                                        state.profile = null
                                        state.pickerMode = false
                                        state.setStatus(
                                            R.string.status_logged_out, StatusKind.IDLE
                                        )
                                        reloadAccount(true)
                                    }

                                    is ApiResult.Err -> state.setStatus(
                                        R.string.status_logout_failed,
                                        StatusKind.ERR,
                                        result.message,
                                    )
                                }
                            }
                        },
                        onClearAll = {
                            scope.launch {
                                when (val result = ApiBridge.accountClearAll()) {
                                    is ApiResult.Ok -> {
                                        state.profile = null
                                        state.pickerMode = false
                                        state.setStatus(
                                            R.string.status_all_accounts_cleared,
                                            StatusKind.IDLE,
                                        )
                                        reloadAccount(true)
                                    }

                                    is ApiResult.Err -> state.setStatus(
                                        R.string.status_clear_all_failed,
                                        StatusKind.ERR,
                                        result.message,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            item { StatusLine(state.statusRes, state.statusArg, state.statusKind) }
        }
    }

    // 签到结果弹窗（失败时展示原因，成功 / 已签到时展示统计）
    if (checkinResult != null || checkinError != null) {
        CheckinDialog(
            result = checkinResult,
            error = checkinError,
            onClose = {
                checkinResult = null
                checkinError = null
            },
        )
    }
}

// ---------------------------------------------------------------- 登录界面

@Composable
private fun LoginView(
    state: AccountState,
    onLogin: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = stringResource(R.string.account_login_prompt),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.usernameInput,
            onValueChange = { state.usernameInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_account)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.passwordInput,
            onValueChange = { state.passwordInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onLogin() }),
        )
        Button(
            onClick = onLogin,
            enabled = !state.loggingIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_login))
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.account_logged_in),
            style = MaterialTheme.typography.titleSmall,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SAVED_ACCOUNTS_HEIGHT)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(4.dp)
        ) {
            if (state.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.account_logged_in_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    state.accounts.forEach { username ->
                        val selected = state.selectedAccount == username
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable {
                                    state.selectedAccount = if (selected) null else username
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = username,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 只有选中的条目才露出勾 / 叉（手机没有 hover）
                            if (selected) {
                                IconAction(
                                    iconRes = R.drawable.ic_check,
                                    labelRes = R.string.btn_login_this_account,
                                    tint = Color(0xFF27AE60),
                                    onClick = { onSwitch(username) },
                                )
                                IconAction(
                                    iconRes = R.drawable.ic_close,
                                    labelRes = R.string.btn_remove_this_account,
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { onRemove(username) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 已登录界面

/** 已登录界面的头部：刷新按钮 + 头像 + 昵称 + 账号状态（等级 / 经验 / 收藏 / J 币）。 */
@Composable
private fun LoggedHeader(state: AccountState, onRefresh: () -> Unit) {
    val profile = state.profile ?: return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.btn_refresh_account),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = profile.nickname.ifEmpty { profile.username },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AccountInfo(profile)
            }
        }
    }
}

/** 已登录界面的四个功能按钮（两行两列）。 */
@Composable
private fun LoggedActions(
    checkingIn: Boolean,
    onCheckin: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCheckin,
                enabled = !checkingIn,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_checkin), maxLines = 1)
            }
            OutlinedButton(onClick = onSwitchAccount, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_switch_account), maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_logout), maxLines = 1)
            }
            Button(
                onClick = onClearAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_clear_all_login), maxLines = 1)
            }
        }
    }
}

/** 头像：有地址就原生取图（圆形裁切），否则显示人形占位。 */
@Composable
private fun Avatar(profile: AccountProfile) {
    if (profile.avatarUrl.isNotEmpty()) {
        CoverImage(
            url = profile.avatarUrl,
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_person),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** 账号状态：等级 / 经验（进度条 + 数值）/ 收藏数与 J 币。 */
@Composable
private fun AccountInfo(profile: AccountProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoLine(
            label = stringResource(R.string.account_level),
            value = levelText(profile),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.account_exp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
            )
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(expFraction(profile).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.account_exp_value,
                    intText(profile.exp),
                    intText(profile.nextLevelExp),
                    percentText(profile.expPercent),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoLine(
            label = stringResource(R.string.account_favorites_count),
            value = stringResource(
                R.string.account_favorites_value,
                intText(profile.favorites),
                intText(profile.favoritesMax),
            ) + "   " + stringResource(R.string.account_coin) + " " + intText(profile.coin),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 收藏预览：折叠标题 + 「查看更多」+ 封面网格（每行 3 本）。 */
@Composable
private fun FavoritesSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    items: List<AlbumItem>?,
    error: String?,
    onOpenFavorites: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.favorite_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (expanded) {
                TextButton(onClick = onOpenFavorites) {
                    Text(stringResource(R.string.btn_view_more))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // 收起时不去读 items / error：那两个状态变化时就不必重排这一段
        if (expanded) {
            when {
                error != null -> Text(
                    text = stringResource(R.string.favorite_load_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                items == null -> Text(
                    text = stringResource(R.string.favorite_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                items.isEmpty() -> Text(
                    text = stringResource(R.string.favorite_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> AlbumGridRows(items = items, onOpen = onOpenAlbum)
            }
        }
    }
}

/** 非惰性网格：每行 3 本（账号页在一个可滚动容器里，不能嵌 LazyVerticalGrid）。 */
@Composable
private fun AlbumGridRows(items: List<AlbumItem>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    // 直接把 weight 交给卡片，省掉一层 Box（嵌套越浅，重组与测量的开销越小）
                    AlbumCard(
                        item = item,
                        onClick = { onOpen(item.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 3 本时补空位，保证卡片宽度与满行一致
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- 签到弹窗

@Composable
private fun CheckinDialog(result: CheckinResult?, error: String?, onClose: () -> Unit) {
    val rows = ArrayList<Pair<String, String>>()
    if (error != null) {
        rows.add(stringResource(R.string.checkin_status) to stringResource(R.string.checkin_status_failed))
        rows.add(stringResource(R.string.checkin_error) to error)
    } else if (result != null) {
        rows.add(
            stringResource(R.string.checkin_status) to stringResource(
                if (result.code == CHECKIN_SUCCESS) {
                    R.string.checkin_status_ok
                } else {
                    R.string.checkin_status_already
                }
            )
        )
        rows.add(
            stringResource(R.string.checkin_month_days) to
                stringResource(R.string.checkin_days, result.monthDays)
        )
        rows.add(
            stringResource(R.string.checkin_streak) to
                stringResource(R.string.checkin_days, result.streak)
        )
        rows.add(stringResource(R.string.checkin_reward) to rewardText(result))
        if (result.event.isNotEmpty()) {
            rows.add(stringResource(R.string.checkin_event) to result.event)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.checkin_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
    )
}

/** 当天签到奖励：成功时给 J 币 / 经验，否则退回「今日已领取」或服务端原文。 */
@Composable
private fun rewardText(result: CheckinResult): String {
    if (result.code != CHECKIN_SUCCESS) {
        return stringResource(R.string.checkin_reward_claimed)
    }
    if (result.coin == null && result.exp == null) {
        return result.msg.ifEmpty { "—" }
    }
    return stringResource(
        R.string.checkin_reward_value,
        intText(result.coin ?: 0),
        intText(result.exp ?: 0),
    )
}

// ---------------------------------------------------------------- 小工具

@Composable
private fun IconAction(
    iconRes: Int,
    labelRes: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(labelRes),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 等级文本：`Lv.10 称号`；都取不到时给占位符。 */
@Composable
private fun levelText(profile: AccountProfile): String {
    val name = profile.levelName.trim()
    if (profile.level == null && name.isEmpty()) return "—"
    val level = profile.level?.toString() ?: "—"
    return ("Lv.$level $name").trim()
}

private fun expFraction(profile: AccountProfile): Float =
    ((profile.expPercent ?: 0.0) / 100.0).toFloat()

/** 数字文本：带千位分隔符；取不到时给占位符（与桌面 `_int_text` 一致）。 */
private fun intText(value: Int?): String = if (value == null) "—" else "%,d".format(value)

private fun percentText(value: Double?): String =
    if (value == null) "—" else "%.1f%%".format(value)