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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream

/**
 * 原生图像转码 / 解扰桥（供 Python 侧调用）。
 *
 * 背景：Chaquopy 的 Pillow 编译时未启用 WebP（`PIL/_imaging.so` 内无任何 webp 符号），
 * 而站点下发的是 WebP，且**图片在服务端被横向切条打乱存放**。Android 原生
 * `BitmapFactory` 对 JPEG/PNG/GIF/WebP 统一支持，故由本类承担「解码 → 解扰 → 转 JPEG」，
 * 与原桌面版 `core/downloader.py::_decode_image` + `download.image.suffix: .jpg` 的行为一致。
 *
 * 切块数 `num` 由 Python 侧用 jmcomic 的 `JmImageTool.get_num_by_url` 算出后传入
 * （它依赖 scramble_id / aid / 去后缀文件名三者，Kotlin 侧不重复实现该逻辑）。
 *
 * Python 调用示例：
 * ```
 * from java import jclass
 * bridge = jclass("com.j2pmobile.android.ImageBridge")
 * bridge.transcodeAndDescramble(src, dst, 85, num)
 * ```
 */
object ImageBridge {

    /** 只转码、不解扰（等价于 num=0）。 */
    @JvmStatic
    fun transcodeToJpeg(srcPath: String, dstPath: String, quality: Int): Boolean =
        transcodeAndDescramble(srcPath, dstPath, quality, 0)

    /**
     * 把 [srcPath] 指向的图片解码后以 JPEG 写入 [dstPath]；[num] > 0 时先按 jmcomic
     * 的分条规则还原打乱的图片。
     *
     * @param quality JPEG 质量（1..100）
     * @param num     解扰切块数；0 表示无需解扰
     * @return 成功为 true；解码失败或写盘异常为 false
     */
    @JvmStatic
    fun transcodeAndDescramble(srcPath: String, dstPath: String, quality: Int, num: Int): Boolean {
        val source: Bitmap = BitmapFactory.decodeFile(srcPath) ?: return false
        val target = if (num > 0) descramble(source, num) else source
        return try {
            FileOutputStream(File(dstPath)).use { out ->
                target.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        } catch (t: Throwable) {
            false
        } finally {
            if (target !== source) target.recycle()
            source.recycle()
        }
    }

    /**
     * 只读图片尺寸、不解码整张图（`inJustDecodeBounds`），供 Python 侧拿占位比例。
     *
     * @return `[宽, 高]`；读不出来时为 `[0, 0]`（或负值）
     */
    @JvmStatic
    fun imageSize(path: String): IntArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return intArrayOf(options.outWidth, options.outHeight)
    }

    /**
     * 按 jmcomic 的分条规则还原被打乱的图片（与桌面版 `_decode_image` 逐行一致）。
     *
     * 站点把图横向切成 `num` 条并打乱存放：`over` 为切不尽的余数，第 0 条（最底部
     * 的一条）额外带上 `over` 行，其余各条依次向上取，落位时整体向下偏移 `over` 行。
     */
    @JvmStatic
    fun descramble(source: Bitmap, num: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (num <= 0 || height < num) return source

        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val over = height % num
        for (i in 0 until num) {
            var move = height / num
            val ySrc = height - (move * (i + 1)) - over
            var yDst = move * i
            if (i == 0) move += over else yDst += over

            val band = Bitmap.createBitmap(source, 0, ySrc, width, move)
            canvas.drawBitmap(band, 0f, yDst.toFloat(), null)
            if (band !== source) band.recycle()
        }
        return target
    }
}