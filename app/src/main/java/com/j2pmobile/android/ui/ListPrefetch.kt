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

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ui.components.warmCover
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 空闲时最多往前预取几页。
 *
 * 这是**硬上限**（不是「软窗口」）：无论停留多久，最多只把后面这两页取回来，
 * 不会越滑越多、也不会把整本都悄悄下完。
 *
 * 取 2（而不是 3）是为了**跟缓存容量对齐**：一页 18 张封面，预取 2 页就是 36 张，
 * 加上当前页的 18 张，仍在 `CoverCache` 的容量之内；预取更多会把「上一页」的封面
 * 挤出去 —— 那样往回翻反而又变成满屏占位底框，得不偿失。
 */
private const val PREFETCH_MAX_PAGES = 2

/** 页面数据到位后静置多久才开始预取（对「屏幕静止」的近似）。 */
private const val PREFETCH_IDLE_DELAY_MS = 1_500L

/**
 * 预取的并发：正常 2；第一页耗时超过 [SLOW_PAGE_MS]（判定为弱网）时降到 1。
 *
 * 弱网下降低并发是刻意的：此时带宽本来就紧张，多路并发只会互相拖慢，
 * 还把「用户真正要看的那一页」挤在后面。
 */
private const val PREFETCH_CONCURRENCY = 2
private const val PREFETCH_CONCURRENCY_WEAK = 1
private const val SLOW_PAGE_MS = 2_000L

/**
 * 列表页的「空闲超额预取」：页面静止 [PREFETCH_IDLE_DELAY_MS] 后，往后预取最多
 * [PREFETCH_MAX_PAGES] 页的列表数据，并把这几页的封面一并取进缓存。
 *
 * 目的：翻页时不再等网络 —— 下一页的数据已在桥接层的检索缓存里、封面也已在 `CoverCache` 里，
 * 点「下一页」几乎是瞬间出图，而不是先看到满屏占位底框。
 *
 * 触发方式：以「页码 / 总页数 / 就绪状态」变化为信号，静置一段时间再开始；期间再翻页或重新搜索，
 * [LaunchedEffect] 的键就变了 → 上一轮自动取消，不会把带宽浪费在用户已经离开的页上。
 *
 * 预取结果**不写进界面状态**（界面只认用户真正翻到的那一页），只用来预热缓存。
 */
@Composable
fun IdleListPrefetch(
    /** 当前页号（1 起）。 */
    currentPage: Int,
    /** 总页数。 */
    totalPages: Int,
    /** 数据是否已就绪：加载中 / 出错 / 空列表时都不预取。 */
    ready: Boolean,
    /** 取第 page 页的条目；失败回 null（预取失败绝不能影响界面）。 */
    fetchPage: suspend (Int) -> List<AlbumItem>?,
) {
    LaunchedEffect(currentPage, totalPages, ready) {
        if (!ready || totalPages <= currentPage) return@LaunchedEffect
        delay(PREFETCH_IDLE_DELAY_MS)

        val pages = (currentPage + 1)..minOf(totalPages, currentPage + PREFETCH_MAX_PAGES)
        val items = ArrayList<AlbumItem>()

        // 第一页串行取：顺便用它的耗时判断是不是弱网，据此决定这一轮用几个并发
        val started = SystemClock.elapsedRealtime()
        fetchPage(pages.first)?.let { items += it }
        val slow = SystemClock.elapsedRealtime() - started > SLOW_PAGE_MS

        val rest = pages.drop(1)
        if (rest.isNotEmpty()) {
            val gate = Semaphore(if (slow) PREFETCH_CONCURRENCY_WEAK else PREFETCH_CONCURRENCY)
            coroutineScope {
                rest.forEach { page ->
                    launch { gate.withPermit { fetchPage(page)?.let { items += it } } }
                }
            }
        }

        // 封面才是「切页不再满屏占位」的关键：数据取回来了，就顺手把封面也预热掉
        // （warmCover 走预取专用闸，不会挤占可见封面的名额）
        coroutineScope {
            items.forEach { item -> launch { warmCover(item.coverUrl) } }
        }
    }
}