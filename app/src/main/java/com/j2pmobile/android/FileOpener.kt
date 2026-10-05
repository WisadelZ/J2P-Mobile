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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/**
 * 用系统里的其它应用打开文件（第 2 步 2.2）。
 *
 * 替换桌面版的 `os.startfile`：把文件经 [Platform.uriFor] 转成 content:// URI，
 * 再以 `ACTION_VIEW` 交给系统（图片交给图库、PDF 交给 PDF 阅读器等）。
 *
 * @return 成功发起 Intent 为 true；文件不存在或系统无可用应用为 false
 */
object FileOpener {

    @JvmStatic
    fun open(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val context = Platform.ctx()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(Platform.uriFor(path)), mimeOf(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}