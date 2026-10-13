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
package com.j2pmobile.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 同步账号（网站账号）的用户资料。 */
data class SyncUser(
    val id: Int,
    val username: String,
    val nickname: String,
    val email: String,
)

/** 一端（本地或云端）的数据数量。 */
data class SyncCounts(val folders: Int, val tags: Int, val history: Int)

/** 一次登录 / 注册的结果。 */
data class SyncAuth(val token: String, val user: SyncUser)

/** 云端（或本地）的一份数据快照：三个数组直接用于对比 / 合并。 */
class SyncSnapshot(
    val folders: JSONArray,
    val tags: JSONArray,
    val history: JSONArray,
    val counts: SyncCounts,
)

/** 推送（本地 → 云端）结果。 */
data class SyncPushResult(val changed: Boolean, val counts: SyncCounts)

/** 清除云端结果。 */
data class SyncClearResult(val changed: Boolean, val cleared: SyncCounts, val counts: SyncCounts)

/** 同步接口错误（带 HTTP 状态码，便于界面区分 401 等）。 */
class SyncException(val status: Int, message: String) : Exception(message)

/**
 * 云同步 HTTP 客户端（网站账号 + 应用数据）。
 *
 * 直接用 [HttpURLConnection]（与 [Updater] 一致，不引入新依赖）。所有方法都是挂起函数，
 * 内部切到 IO 线程；失败抛 [SyncException]（含后端返回的中文提示）。
 *
 * 账号接口走 `/api/app/`（App 专用，免 Turnstile）；数据接口走 `/api/sync/`（Bearer 鉴权）。
 */
object SyncBridge {

    /** 后端基地址（与网站同域，走 HTTPS）。 */
    const val BASE_URL = "https://www.wisadelz.cn"

    private const val TIMEOUT_MS = 15000
    private const val USER_AGENT = "J2P-Mobile-sync"

    private fun toUser(json: JSONObject?): SyncUser {
        val u = json ?: JSONObject()
        val name = u.optString("username")
        return SyncUser(
            id = u.optInt("id"),
            username = name,
            nickname = u.optString("nickname").ifEmpty { name },
            email = u.optString("email"),
        )
    }

    private fun toCounts(json: JSONObject?): SyncCounts {
        val c = json ?: JSONObject()
        return SyncCounts(c.optInt("folders"), c.optInt("tags"), c.optInt("history"))
    }

    /** 登录（支持用户名或邮箱）。 */
    suspend fun login(account: String, password: String, remember: Boolean): SyncAuth =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", account)
                .put("password", password)
                .put("remember", remember)
            val r = request("POST", "/api/app/login", body, null)
            SyncAuth(r.optString("access_token"), toUser(r.optJSONObject("user")))
        }

    /** 发送注册验证码；返回后端提示。 */
    suspend fun registerCode(email: String): String = withContext(Dispatchers.IO) {
        val r = request("POST", "/api/app/register/code",
            JSONObject().put("email", email), null)
        r.optString("message")
    }

    /** 提交注册（成功后自动登录）。 */
    suspend fun register(
        username: String,
        password: String,
        email: String,
        code: String,
        nickname: String,
    ): SyncAuth = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("email", email)
            .put("code", code)
            .put("nickname", nickname)
        val r = request("POST", "/api/app/register", body, null)
        SyncAuth(r.optString("access_token"), toUser(r.optJSONObject("user")))
    }

    /** 找回密码：发送验证码。 */
    suspend fun resetCode(email: String): String = withContext(Dispatchers.IO) {
        val r = request("POST", "/api/app/password/reset/code",
            JSONObject().put("email", email), null)
        r.optString("message")
    }

    /** 找回密码：提交新密码。 */
    suspend fun resetPassword(email: String, code: String, newPassword: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("email", email)
                .put("code", code)
                .put("new_password", newPassword)
            val r = request("POST", "/api/app/password/reset", body, null)
            r.optString("message")
        }

    /** 校验 token 并取当前用户（用于启动时确认登录态仍有效）。 */
    suspend fun me(token: String): SyncUser = withContext(Dispatchers.IO) {
        toUser(request("GET", "/api/app/me", null, token))
    }

    /** 拉取云端应用数据快照。 */
    suspend fun snapshot(token: String): SyncSnapshot = withContext(Dispatchers.IO) {
        val r = request("GET", "/api/sync/snapshot", null, token)
        SyncSnapshot(
            folders = r.optJSONArray("folders") ?: JSONArray(),
            tags = r.optJSONArray("tags") ?: JSONArray(),
            history = r.optJSONArray("history") ?: JSONArray(),
            counts = toCounts(r.optJSONObject("counts")),
        )
    }

    /** 把本地数据合并 / 覆盖到云端。 */
    suspend fun push(
        token: String,
        mode: String,
        folders: JSONArray,
        tags: JSONArray,
        history: JSONArray,
    ): SyncPushResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("mode", mode)
            .put("folders", folders)
            .put("tags", tags)
            .put("history", history)
        val r = request("POST", "/api/sync/push", body, token)
        SyncPushResult(r.optBoolean("changed"), toCounts(r.optJSONObject("counts")))
    }

    /** 清空云端的应用数据。 */
    suspend fun clear(token: String): SyncClearResult = withContext(Dispatchers.IO) {
        val r = request("POST", "/api/sync/clear", JSONObject(), token)
        SyncClearResult(
            changed = r.optBoolean("changed"),
            cleared = toCounts(r.optJSONObject("cleared")),
            counts = toCounts(r.optJSONObject("counts")),
        )
    }

    /** 统一的 JSON 请求；非 2xx 抛 [SyncException]。 */
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
    ): JSONObject {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doInput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try {
                if (text.isNotEmpty()) JSONObject(text) else JSONObject()
            } catch (_: Throwable) {
                JSONObject()
            }
            if (code !in 200..299) {
                val msg = json.optString("detail").ifEmpty { json.optString("message") }
                    .ifEmpty { "HTTP $code" }
                throw SyncException(code, msg)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}