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

import android.app.Application

/**
 * 应用入口。
 *
 * **冷启动优化**：`Python.start()` + 读 `conf.yml` 要几百毫秒，放在
 * `Application.onCreate` 会把它顶到首帧之前。所以这里：
 *
 * 1. 先只做毫秒级的事 —— 注册平台层、从原生缓存取界面语言；
 * 2. Python 运行时与配置改到**后台线程**预热（[PythonBridge.ensureReady] 幂等，
 *    任何一次 Python 调用也会自动兜底）;
 * 3. 预热完成后与 `conf.yml` 里的语言校准一次（写回缓存，供下次启动使用）。
 *
 * 语言的唯一权威来源仍是 `conf.yml`，缓存只是启动时的加速副本。
 */
class Jm2pdfApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Platform.init(this)
        // 语言先取缓存（毫秒级），保证 attachBaseContext 不用等 Python
        AppLocale.language = AppLocale.load(this)

        Thread({
            val result = PythonBridge.ensureReady()
            val language = result.optJSONObject("config")
                ?.optJSONObject("app")
                ?.optString("language")
            if (!language.isNullOrEmpty() && language != AppLocale.language) {
                // conf.yml 与缓存不一致（例如手工改过配置）：以配置为准
                AppLocale.language = language
                AppLocale.save(this, language)
            }
            // 启动时的自动签到：设置了「自动登录并签到」且本机已登录才会真的执行；
            // 未登录 / 今天已签到 / 请求失败都静默（详见 bridge.auto_checkin）。
            PythonBridge.call("auto_checkin")
        }, "jm2pdf-python-init").apply { isDaemon = true }.start()
    }
}