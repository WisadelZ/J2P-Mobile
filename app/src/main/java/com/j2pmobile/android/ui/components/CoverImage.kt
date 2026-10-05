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
package com.j2pmobile.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.j2pmobile.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 封面图内存缓存：按**字节数**计容量（约可用堆的 1/8，下限 4MB）。
 *
 * 为什么按字节而不是按条数：封面尺寸差别很大，按条数限会在大图上撑爆内存。
 * 用 [LruCache] 而不是自己维护链表，省掉一份多余的实现。
 */
object CoverCache {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(url: String): Bitmap? = if (url.isEmpty()) null else cache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        if (url.isNotEmpty()) cache.put(url, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}

/**
 * 原生取封面（`HttpURLConnection`，UA 与桌面版一致）。
 *
 * 设计决定：封面**不在 Python 侧取**，Kotlin 原生取图 + 缓存，既省掉跨 JNI 传字节，
 * 也避免 Python 侧为几十张封面再开线程池。失败返回 null（封面只是点缀，不影响主流程）。
 */
private fun fetchCoverBitmap(url: String): Bitmap? = try {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0")
    }
    try {
        if (connection.responseCode == 200) {
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } else {
            null
        }
    } finally {
        connection.disconnect()
    }
} catch (_: Throwable) {
    null
}

/**
 * 封面控件：命中缓存则立即显示，否则异步取图；取不到时显示占位图。
 *
 * 注意：**不做点击放大**（第 4 步的既定交互调整），点击行为由外层卡片统一处理。
 */
@Composable
fun CoverImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(CoverCache.get(url)) }

    LaunchedEffect(url) {
        if (url.isNotEmpty() && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { fetchCoverBitmap(url) }
            if (loaded != null) {
                CoverCache.put(url, loaded)
                bitmap = loaded
            }
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_broken_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}