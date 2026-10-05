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
import android.content.res.Configuration
import java.util.Locale

/**
 * 界面语言（与配置 `app.language` 的取值一致：zh_cn / zh_tw / en）。
 *
 * 语言来源为 conf.yml；App 启动时由 [Jm2pdfApp] 读入，Activity 在
 * `attachBaseContext` 里按它包一层带 Locale 的 Context，从而让资源选中对应的
 * `values-zh-rCN` / `values-zh-rTW`。
 */
object AppLocale {

    const val ZH_CN = "zh_cn"
    const val ZH_TW = "zh_tw"
    const val EN = "en"

    /** 语言下拉框的展示顺序。 */
    val languages = listOf(ZH_CN, ZH_TW, EN)

    private const val PREFS_NAME = "jm2pdf_prefs"
    private const val KEY_LANGUAGE = "language"

    /**
     * 读语言：优先用原生的 SharedPreferences 缓存。
     *
     * 为什么要缓存：语言的唯一权威来源是 conf.yml，但读它要先启动 Python 运行时
     * （几百毫秒）。而语言必须在 Activity 的 `attachBaseContext` 之前确定，
     * 那会把 Python 启动顶到首帧之前、拖慢冷启动。因此这里留一份毫秒级的原生缓存，
     * 启动时先用它，Python 就绪后再与 conf.yml 校准（见 Jm2pdfApp）。
     */
    fun load(context: Context): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return if (saved != null && saved in languages) saved else ZH_CN
    }

    /** 语言变更时同步缓存（conf.yml 仍由调用方负责写入）。 */
    fun save(context: Context, code: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code)
            .apply()
    }

    @Volatile
    var language: String = ZH_CN

    /** 语言代码 -> 该语言自身的名称（语言选择器里始终用母语显示）。 */
    fun nativeName(code: String): String = when (code) {
        ZH_TW -> "繁體中文"
        EN -> "English"
        else -> "简体中文"
    }

    private fun locale(code: String): Locale = when (code) {
        ZH_TW -> Locale.TRADITIONAL_CHINESE
        EN -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }

    /** 给 Context 套上当前语言，供 Activity 的 attachBaseContext 使用。 */
    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale(language))
        return base.createConfigurationContext(configuration)
    }
}