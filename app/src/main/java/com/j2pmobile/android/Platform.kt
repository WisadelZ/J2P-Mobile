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
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 平台层：应用目录与对外可分享的 content:// URI（第 2 步 2.1）。
 *
 * 目录语义（替换桌面的「exe 所在目录」）：
 * - 数据根：[rootDir] 取应用外部私有目录（`Android/data/<pkg>/files`），
 *   配置里的相对路径（如 `./download`）都相对它解析；
 * - 配置目录：[configDir] 取应用内部私有目录（`files/config`），放 conf.yml 等；
 * - 回收站：[recycleDir] 取数据根下的 `.trash`（见 [RecycleBin]）。
 *
 * 对外分享必须走 [FileProvider]（Android 7+ 禁止把 `file://` 暴露给别的应用），
 * 因此这里同时提供 [uriFor] 与 [authority]。
 */
object Platform {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun ctx(): Context = appContext ?: error("Platform.init 尚未调用")

    fun rootDir(): File = ctx().getExternalFilesDir(null) ?: ctx().filesDir

    fun configDir(): File = File(ctx().filesDir, "config")

    fun downloadDir(): File = File(rootDir(), "download")

    fun recycleDir(): File = File(rootDir(), ".trash")

    @JvmStatic
    fun rootPath(): String = rootDir().absolutePath

    @JvmStatic
    fun downloadPath(): String = downloadDir().absolutePath

    @JvmStatic
    fun recyclePath(): String = recycleDir().absolutePath

    fun authority(): String = ctx().packageName + ".fileprovider"

    /** 把应用私有目录下的文件转成可授权给其它应用的 content:// URI。 */
    @JvmStatic
    fun uriFor(path: String): String =
        FileProvider.getUriForFile(ctx(), authority(), File(path)).toString()
}

/**
 * 强制重启应用：清空任务栈后重新拉起 [MainActivity]，再结束当前进程。
 *
 * 用于「热更补丁加载失败」的重试 / 清除补丁，以及设置页「清除补丁」——必须整进程重启，
 * 才能保证补丁要么完整生效、要么完全不生效，绝不带着半加载状态继续使用。
 *
 * **必须在主线程调用**（[android.app.Activity.startActivity] 的约束）。
 */
fun forceRestart() {
    val context = Platform.ctx()
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
    kotlin.system.exitProcess(0)
}