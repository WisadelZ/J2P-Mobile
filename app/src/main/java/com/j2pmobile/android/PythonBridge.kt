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

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/**
 * Kotlin → Python 的统一调用入口（第 1 步桥接层）。
 *
 * 之后所有对 Python 的调用都走这里，好处：
 * - 统一启动 Python 运行时并注入目录（[ensureReady]，幂等）；
 * - 统一按 `bridge.py` 的约定解析 JSON 返回；
 * - 统一把 Python 异常映射成 `{"ok": false, "error": ...}`，不让异常穿透到 UI。
 *
 * **启动开销**：`Python.start()` 会初始化整个解释器（几百毫秒），不能在
 * `Application.onCreate` 主线程里做，否则首帧被顶后。因此 [Jm2pdfApp] 只在后台
 * 线程预热点，任何一次 [call] 也会先 [ensureReady] 兜底（幂等、线程安全）。
 */
object PythonBridge {

    /** Python 侧的门面模块名（app/src/main/python/bridge.py）。 */
    private const val MODULE = "bridge"

    @Volatile
    private var ready: JSONObject? = null

    /**
     * 启动 Python 运行时并调用一次 `bridge.init`（幂等）。
     *
     * 可在任意线程调用；首次调用会阻塞到 Python 就绪。
     */
    @Synchronized
    fun ensureReady(): JSONObject {
        ready?.let { return it }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(Platform.ctx()))
        }
        val result = callRaw(
            "init",
            Platform.rootPath(),
            Platform.configDir().absolutePath,
            "INFO"
        )
        ready = result
        return result
    }

    /**
     * 调用 `bridge.py` 中的函数，返回其 JSON 结果。
     *
     * 函数约定返回 `{"ok": true, ...}` 或 `{"ok": false, "error": ..., "traceback": ...}`；
     * 若 Python 侧抛出未捕获异常，这里也统一兜底成失败结构。
     */
    fun call(function: String, vararg args: Any): JSONObject {
        ensureReady()
        return callRaw(function, *args)
    }

    private fun callRaw(function: String, vararg args: Any): JSONObject {
        return try {
            val module = Python.getInstance().getModule(MODULE)
            JSONObject(module.callAttr(function, *args).toString())
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}