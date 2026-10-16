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
package com.j2pmobile.android.ui

import android.graphics.Bitmap
import com.j2pmobile.android.AlbumDetail
import com.j2pmobile.android.CommentItem
import com.j2pmobile.android.PreviewPageMeta

/**
 * 一个按**访问顺序**淘汰的定长映射（LRU），容量是硬上限。
 *
 * 为什么自己写而不是用 `android.util.LruCache`：这里缓存的是「本子 ID → 一组数据」，
 * 用条数做上限比用字节数更直观（每档数据量级差很多，见 [PageCache] 的三档上限），
 * 而且需要能在界面线程直接读写。`LinkedHashMap(accessOrder = true)` 已经天然按访问顺序
 * 排列，取第一个键就是「最久没被访问」的那个，不必自己维护链表。
 */
class LruMap<K, V>(private val capacity: Int) {

    private val map = LinkedHashMap<K, V>(capacity.coerceAtLeast(1), 0.75f, true)

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        if (capacity <= 0) return
        map[key] = value
        while (map.size > capacity) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    fun remove(key: K) {
        map.remove(key)
    }

    fun clear() {
        map.clear()
    }
}

/** 详情页的「预览前几页」快照：元数据 + 原图字节 + 已解码缩略图。 */
class AlbumPreview(
    val metas: List<PreviewPageMeta>,
    val bytes: List<ByteArray>,
    val thumbs: List<Bitmap?>,
)

/** 详情页评论区的快照（含已加载的页数与总数，支持「加载更多」继续追加）。 */
class AlbumComments(
    val items: List<CommentItem>,
    val page: Int,
    val total: Int,
    val count: Int,
)

/**
 * 页面级缓存（由 [AppShell] 持有，随进程退出彻底释放）。
 *
 * 解决的现象：进详情页加载好数据后切到别的页面，返回时详情、预览缩略图、评论都要重新联网取。
 * 根因是这些数据原本只存在 `AlbumScreen` 的 `remember` 里，离开页面即随组合销毁。
 *
 * 设计要点：
 * - **三档独立上限**：详情（纯文本，很便宜）、评论（文本，便宜）、预览（原图字节 + 位图，很贵）
 *   各按自己的量级设上限，互不挤占 —— 用同一个上限会让「多存几本详情」把预览挤掉；
 * - **自动淘汰**：每档都是 LRU，超上限就丢最久没访问的那本，不需要额外的清理时机；
 * - **只在退出时彻底清**：本对象活在 [AppShell] 的 `remember` 里，页面切换不影响它；
 *   设置页的「清除缓存」会显式调 [clear]。
 *
 * 内存量级（粗估）：详情 / 评论几乎可忽略；预览一本约 5 页 ×（原图 ~0.3 MB + 缩略图 ~0.57 MB）
 * ≈ 4.4 MB，12 本约 53 MB。这是「省流量」与「不被缓存拖累」之间的折中，
 * 12 本这一档已由使用者确认接受（低配机若吃紧，优先降 [AlbumScreen] 的 `THUMB_TARGET_PX`，
 * 而不是减本数 —— 后者会立刻把「切回上一本」变成重新联网）。
 *
 * 注意：预览条是**最容易被用户感知**的一档 —— 一旦被淘汰，切回该本子会重新联网、逐页取图，
 * 期间预览条先转圈、再逐个填占位底框，体验很差。所以这一档的容量要能覆盖「来回翻好几本」
 * 的常见节奏，单本开销则靠缩小缩略图（见 `THUMB_TARGET_PX`）来压。
 */
class PageCache {

    /** 本子 ID → 详情（纯文本，最便宜，留得最多）。 */
    private val details = LruMap<String, AlbumDetail>(DETAIL_CAPACITY)

    /** 本子 ID → 评论快照（文本）。 */
    private val comments = LruMap<String, AlbumComments>(COMMENT_CAPACITY)

    /** 本子 ID → 预览（原图字节 + 位图，最贵，留得最少）。 */
    private val previews = LruMap<String, AlbumPreview>(PREVIEW_CAPACITY)

    fun detail(albumId: String): AlbumDetail? = if (albumId.isEmpty()) null else details[albumId]

    fun putDetail(albumId: String, value: AlbumDetail) {
        if (albumId.isNotEmpty()) details.put(albumId, value)
    }

    fun comments(albumId: String): AlbumComments? =
        if (albumId.isEmpty()) null else comments[albumId]

    fun putComments(albumId: String, value: AlbumComments) {
        if (albumId.isNotEmpty()) comments.put(albumId, value)
    }

    fun preview(albumId: String): AlbumPreview? = if (albumId.isEmpty()) null else previews[albumId]

    fun putPreview(albumId: String, value: AlbumPreview) {
        if (albumId.isNotEmpty()) previews.put(albumId, value)
    }

    /** 设置页「清除缓存」：丢掉全部页面缓存（不影响登录态、配置与下载任务）。 */
    fun clear() {
        details.clear()
        comments.clear()
        previews.clear()
    }

    private companion object {
        /** 详情上限：条目很轻，留 16 本足够覆盖「来回翻好几本」的场景。 */
        const val DETAIL_CAPACITY = 16

        /** 评论上限：纯文本，留 8 本。 */
        const val COMMENT_CAPACITY = 8

        /**
         * 预览上限：含原图字节与缩略图，最贵的一档。
         *
         * 12 本约 53 MB —— 覆盖「连着看十几本、再回头翻」的常见节奏，尽量不让预览条被淘汰
         * （一旦淘汰就要重新联网、逐页取图，那种「转圈 + 占位底框」最容易被用户察觉）。
         * 要再往上加，请先看 [AlbumScreen] 的 `THUMB_TARGET_PX`：把单本开销压下来比加本数划算得多。
         */
        const val PREVIEW_CAPACITY = 12
    }
}