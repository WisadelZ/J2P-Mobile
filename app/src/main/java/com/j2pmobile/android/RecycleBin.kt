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

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站（第 2 步 2.3）：软删除到 [Platform.recycleDir]，可还原。
 *
 * 替换桌面版的 Windows `SHFileOperationW`。每搬入一项，旁边写一个同名 `.origin`
 * 侧车文件记录它原来的绝对路径，[restore] 靠它搬回去；[list] 也据此给出可读列表。
 *
 * 与桌面的语义一致：删除只是移入回收站，用户可以从回收站还原（见 i18n 的
 * `status_deleted` / `delete_dialog_body` 文案）。
 */
object RecycleBin {

    private const val SIDECAR_SUFFIX = ".origin"

    private fun entryName(file: File): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "_" + file.name

    /** 把一个文件或目录移入回收站。 */
    @JvmStatic
    fun moveToTrash(path: String): Boolean {
        val source = File(path)
        if (!source.exists()) return false
        val trash = Platform.recycleDir().apply { mkdirs() }

        var target = File(trash, entryName(source))
        var index = 1
        while (target.exists()) {
            target = File(trash, entryName(source) + "_" + index)
            index++
        }

        return try {
            // 同一卷内 renameTo 最快；跨卷失败时退回复制 + 删除
            if (!source.renameTo(target)) {
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            }
            File(trash, target.name + SIDECAR_SUFFIX).writeText(source.absolutePath)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 列出回收站内的条目（JSON 数组，元素含 name / origin / isDir / size）。 */
    @JvmStatic
    fun list(): String {
        val result = JSONArray()
        val trash = Platform.recycleDir()
        val sidecars = trash.listFiles { f -> f.isFile && f.name.endsWith(SIDECAR_SUFFIX) }
            ?: return result.toString()
        for (sidecar in sidecars.sortedByDescending { it.name }) {
            val name = sidecar.name.removeSuffix(SIDECAR_SUFFIX)
            val entry = File(trash, name)
            if (!entry.exists()) continue
            try {
                result.put(
                    JSONObject()
                        .put("name", name)
                        .put("origin", sidecar.readText().trim())
                        .put("isDir", entry.isDirectory)
                        .put("size", if (entry.isFile) entry.length() else 0L)
                )
            } catch (t: Throwable) {
                // 单个条目的侧车读不出来就跳过
            }
        }
        return result.toString()
    }

    /** 把回收站里的某个条目搬回原位置。 */
    @JvmStatic
    fun restore(name: String): Boolean {
        val trash = Platform.recycleDir()
        val entry = File(trash, name)
        val sidecar = File(trash, name + SIDECAR_SUFFIX)
        if (!entry.exists() || !sidecar.exists()) return false
        return try {
            val origin = File(sidecar.readText().trim())
            origin.parentFile?.mkdirs()
            if (!entry.renameTo(origin)) {
                entry.copyRecursively(origin, overwrite = true)
                entry.deleteRecursively()
            }
            sidecar.delete()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 清空回收站，返回清除的条目数。 */
    @JvmStatic
    fun empty(): Int {
        var count = 0
        val trash = Platform.recycleDir()
        for (child in trash.listFiles() ?: return 0) {
            if (child.name.endsWith(SIDECAR_SUFFIX)) {
                child.delete()
                continue
            }
            val deleted = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (deleted) {
                File(trash, child.name + SIDECAR_SUFFIX).delete()
                count++
            }
        }
        return count
    }
}