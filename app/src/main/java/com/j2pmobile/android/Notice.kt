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

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一条启动公告（正文已按当前界面语言取好）。
 *
 * @param epochSeconds 公告时间（解析自 `platforms.android.date` 的 epoch 秒；
 *                     用于「下次更新前不再弹出」的比对）
 * @param title        公告标题（为空时界面用内置默认标题兜底）
 * @param body         公告正文（已按界面语言取 content_i18n，缺失时回落 content）
 * @param link         详情链接（非空时界面展示「查看详情」按钮）
 */
data class NoticeData(
    val epochSeconds: Long,
    val title: String,
    val body: String,
    val link: String,
)

/**
 * 启动公告：静默后台拉取 + 按「时间（epoch 秒）」记忆「下次更新前不再弹出」。
 *
 * 清单结构为**双端分开**的 `notice.json`（schema_version 2），本端**只读 `platforms.android`**：
 * 顶级没有 `platforms`、没有 `android`、该段 `content` 去空白后为空、或 `date` 无法解析，
 * 都当作「没有公告」，静默不弹（无网络 / 403 / JSON 非法同样静默）。
 *
 * `date` 为 ISO 8601、精确到秒、可带时区偏移；解析**不使用 java.time**
 * （minSdk 24 且未开启 core library desugaring，java.time 在 API 24/25 会崩），
 * 改用 [SimpleDateFormat] 依次尝试多种模式，全部失败即视为无公告（见 [parseEpochSeconds]）。
 *
 * 「不再弹出」的状态存 SharedPreferences（沿用界面语言缓存用的偏好文件名），
 * 键为 `notice_muted_ts`（Long，epoch 秒）：只有用户勾选并确定才写，不勾选则下次启动照常弹。
 */
object NoticeCenter {

    /** 沿用原生偏好文件名（见 AppLocale.PREFS_NAME）。 */
    private const val PREFS_NAME = "jm2pdf_prefs"

    /** 「不再弹出」的键：值为被静音的公告 epoch 秒。 */
    private const val KEY_MUTED_TS = "notice_muted_ts"

    /** 旧键（扁平结构时代的日期字符串）：读取时顺手清理。 */
    private const val KEY_MUTED_DATE_LEGACY = "notice_muted_date"

    /** 未静音时的哨兵值（正常公告时间不会是 Long.MIN_VALUE）。 */
    private const val NO_MUTE = Long.MIN_VALUE

    /** 公告清单里本端的平台键（桌面端是 windows）。 */
    private const val NOTICE_PLATFORM = "android"

    /** 公告拉取超时：定小一点，公告不值得拖慢启动。 */
    private const val TIMEOUT_MS = 5000

    /**
     * `date` 的解析模式（按顺序尝试，全部失败即视为无公告）。
     *
     * 前两种支持带时区偏移；后三种用于不带偏移 / 只有日期的兜底。
     * `XXX` 在个别低版本机型上可能不受支持（构造时抛 IllegalArgumentException），
     * 失败会自然落到后面的模式。
     */
    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    /**
     * 已静音的公告时间（epoch 秒）；未设置时返回哨兵 [NO_MUTE]。
     *
     * 顺带清理旧键 `notice_muted_date`（扁平结构时代遗留）。
     */
    fun mutedTs(context: Context): Long {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_MUTED_DATE_LEGACY)) {
            prefs.edit().remove(KEY_MUTED_DATE_LEGACY).apply()
        }
        return prefs.getLong(KEY_MUTED_TS, NO_MUTE)
    }

    /** 记住「该公告不再弹出」。 */
    fun mute(context: Context, epochSeconds: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MUTED_TS, epochSeconds)
            .apply()
    }

    /** 静默拉取并解析公告；无网络 / 403 / JSON 非法 / 无本端公告都返回 null。 */
    fun fetch(): NoticeData? = try {
        Updater.fetchText(Updater.NOTICE_URL, TIMEOUT_MS)?.let { parse(it, AppLocale.language) }
    } catch (_: Throwable) {
        null
    }

    /**
     * 解析公告 JSON：只读 `platforms.android`；正文按 [language]（zh_cn / zh_tw / en）
     * 取 `content_i18n`，缺失或为空时回落 `content`。
     *
     * 平台段缺失、正文为空、或 `date` 解析不出时间，都返回 null（不弹）。
     */
    fun parse(body: String, language: String): NoticeData? {
        val root = JSONObject(body)
        val section = root.optJSONObject("platforms")?.optJSONObject(NOTICE_PLATFORM)
            ?: return null
        val key = when (language) {
            AppLocale.ZH_TW -> "zh_TW"
            AppLocale.EN -> "en"
            else -> "zh_CN"
        }
        val localized = section.optJSONObject("content_i18n")?.optString(key).orEmpty()
        val content = localized.ifBlank { section.optString("content") }
        if (content.isBlank()) return null
        val epochSeconds = parseEpochSeconds(section.optString("date")) ?: return null
        return NoticeData(
            epochSeconds = epochSeconds,
            title = section.optString("title").trim(),
            body = content,
            link = section.optString("link").trim(),
        )
    }

    /**
     * 把 ISO 8601 时间串解析成 epoch 秒；依次尝试 [DATE_PATTERNS]，全部失败返回 null。
     *
     * 每次新建 [SimpleDateFormat]（**非线程安全**，不复用）；解析用非宽松模式，
     * 构造函数异常（个别机型不支持 `XXX`）与解析异常都忽略并继续下一个模式。
     */
    private fun parseEpochSeconds(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        for (pattern in DATE_PATTERNS) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.isLenient = false
                val parsed = format.parse(value) ?: continue
                return parsed.time / 1000
            } catch (_: Exception) {
                // 该模式不适用（格式不符 / 机型不支持），换下一个
            }
        }
        return null
    }
}