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
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.ReaderSource
import com.j2pmobile.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 最多同时留多少页解码后的位图（一页约 1280×1793，ARGB_8888 约 9 MB）。
 *
 * 要比预取窗口大一些，否则刚预取好的下一页会在显示前就被淘汰掉。8 页 ≈ 73 MB 常驻，
 * 是「翻页不空白」与手机内存之间的折中；嫌占内存可下调 [READER_CONCURRENCY] 与它。
 */
private const val MAX_CACHED_PAGES = 8

/** 量不到页面尺寸时的兜底宽高比（A4 纵向）。 */
private const val DEFAULT_ASPECT = 0.707f

/**
 * 同时取图（含解码）的页数上限。
 *
 * 本地 PDF 是纯内存操作、在线取图是网络请求，两者都能并行；定 6 是为了既铺得够快，
 * 又不会让 6 张 9 MB 的位图同时压在内存里（不走配置里的 `thread_image`：那是下载
 * 落盘的并发，量级与这里不同）。
 */
private const val READER_CONCURRENCY = 6

/** 翻页后**向前**（下一页方向）预取的页数：保证连续下滑时下面几屏已经就绪。 */
private const val PREFETCH_AHEAD = 3

/** 向后（已读过的方向）保留的页数：往回滚时不用重新等。 */
private const val PREFETCH_BEHIND = 2

/** 首次进入一次铺够的页数（6~10 张）：避免逐张加载时一屏一屏的空白感。 */
private const val INITIAL_PREFETCH = 8

/** 单页取图的最大尝试次数（含首次）：网络抖动时自动重试，仍失败才落到可点重试的占位块。 */
private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 400L

/** 缩放范围：1.0 = 原始适配大小（初始），可放大到 4 倍，也可缩小到 0.5 倍。 */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

/**
 * 松手时若缩放落在 [SNAP_LOW, SNAP_HIGH] 内，就吸附回初始大小（1.0）并复位平移，
 * 恢复常规滚动（用户要求「接近初始大小时吸附，不必太敏感」）。
 * 落在区间外的（明显放大 / 明显缩小）保持不动，所以缩小功能真实可用。
 */
private const val SNAP_LOW = 0.9f
private const val SNAP_HIGH = 1.15f

/**
 * 阅读器的缩放 / 平移状态（整个阅读界面共用一套，由 [ReaderScreen] 持有）。
 *
 * 缩放在两种排版下的作用方式不同：
 * - **竖式**：作用在整条图片流的**布局宽度**上（`内容宽 = 视口宽 × scale`），
 *   所以每一页同比缩放、页与页仍首尾相接，纵向滚动翻页照常可用；
 *   放大到超过视口宽时用横向平移看两侧。
 * - **横式**：单页浏览，缩放作用在当前页的渲染（`graphicsLayer`），放大后可双向平移。
 *
 * **只在「已放大」时才消费单指拖动**（用于平移）；未放大时单指拖动仍交给列表滚动 /
 * 翻页，因此不影响正常浏览。平移范围按可视区（竖式）或单页尺寸（横式）裁剪。
 */
private class ZoomState {

    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** 可视区尺寸（竖式换算内容宽度、竖向平移边界都用它）。 */
    var viewport by mutableStateOf(IntSize.Zero)

    /** 单页尺寸（横式平移边界用）。 */
    var pageSize by mutableStateOf(IntSize.Zero)

    /** 是否处于「放大」状态：只有放大才需要横向平移、才锁住横式的左右翻页。 */
    val magnified: Boolean get() = scale > 1f + 0.001f

    /** 一次捏合 / 拖动：按比例更新缩放，并把平移裁剪在边界内。 */
    fun onGesture(vertical: Boolean, zoomChange: Float, pan: Offset) {
        scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val limit = panLimit(vertical)
        offset = Offset(
            (offset.x + pan.x).coerceIn(-limit.x, limit.x),
            // 竖式的纵向由 LazyColumn 滚动负责，不做纵向平移
            if (vertical) 0f else (offset.y + pan.y).coerceIn(-limit.y, limit.y),
        )
    }

    /** 手势结束：接近初始大小（任一方向）就吸附回 1.0。 */
    fun settle() {
        if (scale in SNAP_LOW..SNAP_HIGH) reset()
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 内容超出可视区的那一半，就是允许平移的最大距离。 */
    private fun panLimit(vertical: Boolean): Offset {
        val size = if (vertical) viewport else pageSize
        return Offset(
            max(0f, (scale - 1f) * size.width / 2f),
            max(0f, (scale - 1f) * size.height / 2f),
        )
    }
}

/**
 * 阅读器的可变状态（由 [AppShell] 持有）。
 *
 * 一次浏览会话 = 一个 Python 侧句柄（[source]）；已解码的页留在 [bitmaps] 里并按
 * 「离当前页最远」淘汰，不做 `recycle()`（交给 GC，避免与正在显示的组件打架）。
 *
 * 注意：**当前页码不存在这里** —— 竖排 / 横排各自的滚动位置才是唯一真相，
 * 由 [ReaderScreen] 从 `LazyListState` / `PagerState` 推导（见那里的说明）。
 */
class ReaderState {

    var source by mutableStateOf<ReaderSource?>(null)
        private set

    var pageCount by mutableStateOf(0)
        private set

    var numberedPages by mutableStateOf(true)
        private set

    /** 页面宽高比（宽 / 高），按首页尺寸推定，竖排用它排占位块的高度。 */
    var aspect by mutableStateOf(DEFAULT_ASPECT)
        private set

    var vertical by mutableStateOf(true)

    /**
     * 沉浸式浏览：收起顶栏、底部控制栏与系统栏，画面占满整屏；点一下画面切换。
     * 由 [ReaderScreen] 触发、[AppShell] 读它来决定顶栏显隐，故放在这里共享。
     */
    var immersive by mutableStateOf(false)
        private set

    val bitmaps = mutableStateMapOf<Int, Bitmap>()
    val names = mutableStateMapOf<Int, String>()
    val failures = mutableStateMapOf<Int, String>()

    // 以下三个集合只在主线程读写（组合函数与 rememberCoroutineScope 的协程都在主线程），
    // 因此无需加锁：pending 去重「同一页不并发取两次」（在线本子等于重复扣额度），
    // queued 去重「同一页不重复排队」，gate 限制同时在取的页数。
    private val pending = mutableSetOf<Int>()
    private val queued = mutableSetOf<Int>()
    private val gate = Semaphore(READER_CONCURRENCY)

    /** 最近的聚焦页（预取窗口的锚点），淘汰缓存时按它取「离得最远」的页。 */
    private var center = 0

    /** 首次铺页是否已经做过（每开一次会话铺一次）。 */
    private var initialDone = false

    val handle: String get() = source?.handle.orEmpty()

    /** 进入阅读器：换会话并清空上一本的缓存。 */
    fun open(source: ReaderSource) {
        this.source = source
        pageCount = source.pageCount
        numberedPages = source.numberedPages
        aspect = if (source.width > 0 && source.height > 0) {
            source.width.toFloat() / source.height.toFloat()
        } else {
            DEFAULT_ASPECT
        }
        vertical = true
        immersive = false
        center = 0
        initialDone = false
        bitmaps.clear()
        names.clear()
        failures.clear()
        pending.clear()
        queued.clear()
    }

    /** 已经离开阅读器：丢掉会话引用与缓存（Python 侧句柄由外壳调用 close 释放）。 */
    fun reset() {
        source = null
        pageCount = 0
        immersive = false
        bitmaps.clear()
        names.clear()
        failures.clear()
        pending.clear()
        queued.clear()
    }

    /** 点一下画面：收起 / 唤出界面。 */
    fun toggleImmersive() {
        immersive = !immersive
    }

    /**
     * 进入阅读器后第一次铺页：一次向前多取几张（[INITIAL_PREFETCH]），
     * 避免逐张加载时一屏一屏的空白感。每个会话只做一次。
     */
    fun initialPrefetch(scope: CoroutineScope) {
        if (initialDone) return
        initialDone = true
        prefetch(scope, 0, INITIAL_PREFETCH - 1)
    }

    /**
     * 以 [anchor]（当前聚焦页）为锚点补图：先取聚焦页，再向前取 [ahead] 页
     * （保证连续下滑时下面几屏已经就绪），然后回补向后 [PREFETCH_BEHIND] 页。
     *
     * 各页取图由 [gate] 限流并发，取图失败在 [ensure] 里自动重试。
     * 快速滑动时锚点会一直变，靠 [ensure] 的 `stale` 判断把「已经滑过去、不再需要」的页
     * 丢掉（在线取图会扣下载额度，不能白取）。只在主线程调用。
     */
    fun prefetch(scope: CoroutineScope, anchor: Int, ahead: Int = PREFETCH_AHEAD) {
        if (pageCount <= 0) return
        this.center = anchor.coerceIn(0, pageCount - 1)
        val window = maxOf(ahead, PREFETCH_BEHIND)
        for (index in prefetchOrder(this.center, ahead)) {
            if (bitmaps.containsKey(index) || !queued.add(index)) continue
            scope.launch {
                try {
                    // 还在当前预取窗口内才取；窗口外说明已经滑过，跳过
                    ensure(index) { abs(index - center) > window }
                } finally {
                    queued.remove(index)
                }
            }
        }
    }

    /** 预取的优先级顺序：聚焦页 → 向前若干页 → 向后若干页。 */
    private fun prefetchOrder(center: Int, ahead: Int): List<Int> {
        val order = LinkedHashSet<Int>()
        order.add(center)
        for (step in 1..ahead) order.add(center + step)
        for (step in 1..PREFETCH_BEHIND) order.add(center - step)
        return order.filter { it in 0 until pageCount }
    }

    /**
     * 确保第 index 页已解码进缓存。
     *
     * 取图与解码都不在主线程；失败自动重试 [MAX_ATTEMPTS] 次，仍失败才记下原因
     * （界面显示可点重试的占位块），不打断整次浏览。
     *
     * [stale] 只由预取传入：等并发许可的这段时间里用户可能已经滑走，返回 true 就不再取。
     * 可见页的取图不传它（可见的页必须取）。
     */
    suspend fun ensure(index: Int, stale: (() -> Boolean)? = null) {
        if (index !in 0 until pageCount) return
        if (bitmaps.containsKey(index) || !pending.add(index)) return
        try {
            gate.withPermit {
                if (stale?.invoke() == true) return
                var attempt = 1
                var error = "取图失败"
                while (true) {
                    when (val result = ApiBridge.readerPage(handle, index)) {
                        is ApiResult.Ok -> {
                            // 解码一页约 9 MB 位图，放 Default 线程，别卡住滑动
                            val bitmap = withContext(Dispatchers.Default) {
                                decodePage(result.value.data)
                            }
                            if (bitmap != null) {
                                bitmaps[index] = bitmap
                                failures.remove(index)
                                if (result.value.name.isNotEmpty()) names[index] = result.value.name
                                trim()
                                return
                            }
                            error = "图片解码失败"
                        }

                        is ApiResult.Err -> error = result.message
                    }
                    if (attempt >= MAX_ATTEMPTS) break
                    delay(RETRY_DELAY_MS * attempt)
                    attempt++
                }
                failures[index] = error
            }
        } finally {
            pending.remove(index)
        }
    }

    /** 清掉某页的失败标记，让界面重新取一次（点占位块重试用）。 */
    fun retry(index: Int) {
        failures.remove(index)
    }

    /** 只留离聚焦页最近的若干页，多余的丢掉引用让系统回收。 */
    private fun trim() {
        if (bitmaps.size <= MAX_CACHED_PAGES) return
        val pivot = center
        val drop = bitmaps.keys
            .sortedByDescending { abs(it - pivot) }
            .take(bitmaps.size - MAX_CACHED_PAGES)
        drop.forEach {
            bitmaps.remove(it)
            names.remove(it)
        }
    }
}

/**
 * 阅读器（切片 5b）：浏览本地 PDF / 图片文件夹与在线本子。
 *
 * 与桌面版浏览层对应，手机上做了两处适配：
 *
 * - **竖排连续翻页为默认**（桌面版默认横排）——手机竖屏连续下滑最顺手；
 *   横排单页翻页改用左右滑动（`HorizontalPager`），不再用两侧按钮占宽度；
 * - 底部一条控制栏：横竖切换 + 上/下页 + 进度滑杆（拖动即跳页）+ 页码跳转弹窗。
 *
 * 页面**逐页**取（Python 侧 base64 回传），但按「聚焦页优先 + 前后预取」并发拉取
 * （见 [ReaderState.prefetch]），不是滑到才一张一张等。
 *
 * **沉浸式浏览**：点一下画面收起顶栏 / 底部控制栏 / 系统栏，画面占满整屏；再点一下唤出。
 *
 * **手势缩放**：双指捏合放大 / 缩小（0.5×~4×）。竖式**整条流统一缩放**（页与页首尾相接，
 * 放大后横向平移、纵向照常滚动翻页）；横式缩放当前页、放大后双向平移。松手时若接近初始
 * 大小（0.9×~1.15×）就自动吸附回 1×，恢复正常滚动（见 [ZoomState]）。
 *
 * 【性能要点】当前页码**不在本函数里直接读**：竖排 / 横排的滚动位置才是唯一真相，
 * 页码由 `derivedStateOf` 推导后**只交给底部控制栏**（且以 lambda 形式传下去）；
 * 预取也只通过 `snapshotFlow` 在协程里读它。否则滑动时整屏会跟着重组一次（§6.11 的教训）。
 */
@Composable
fun ReaderScreen(
    state: ReaderState,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    // 沉浸式：连系统栏一起收，让画面真正铺满（离开阅读器 / 退出沉浸时恢复）
    DisposableEffect(state.immersive) {
        val controller = ViewCompat.getWindowInsetsController(view)
        if (state.immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (state.source == null || state.pageCount <= 0) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.reader_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    // 两套滚动状态各自常驻：切模式时按当前页显式滚过去，不靠重建状态来定位
    val listState = remember { LazyListState() }
    val pagerState = remember { PagerState(0, 0f) { state.pageCount } }
    // 缩放 / 平移：整个阅读界面共用一套（离开阅读器即随组合销毁而复位）
    val zoom = remember { ZoomState() }
    // 只在 lambda（控制栏 / 预取流）里读，不在组合期读：滑动不牵动整屏重组
    val currentPage by remember {
        derivedStateOf {
            if (state.vertical) listState.firstVisibleItemIndex else pagerState.currentPage
        }
    }

    // 进入阅读器先铺一批；此后聚焦页一变就按「聚焦页优先」重排预取窗口。
    // 页码只在 snapshotFlow 的协程里读，不在组合期读 —— 滑动不会牵动整屏重组。
    LaunchedEffect(state.source) {
        state.initialPrefetch(scope)
        snapshotFlow {
            if (state.vertical) listState.firstVisibleItemIndex else pagerState.currentPage
        }.collect { page -> state.prefetch(scope, page) }
    }

    val jumpTo: suspend (Int) -> Unit = { target ->
        val index = target.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        if (state.vertical) listState.scrollToItem(index) else pagerState.scrollToPage(index)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { zoom.viewport = it }
                // 点一下收起 / 唤出界面（滑动不会触发：滚动组件只消费拖动，不消费点击）
                .pointerInput(Unit) {
                    detectTapGestures { state.toggleImmersive() }
                }
        ) {
            if (state.vertical) {
                VerticalStream(state, zoom, listState)
            } else {
                HorizontalStream(state, zoom, pagerState)
            }
        }
        if (state.immersive) return@Column
        ReaderControls(
            state = state,
            currentPage = { currentPage },
            onTurn = { delta -> scope.launch { jumpTo(currentPage + delta) } },
            onJump = { target -> scope.launch { jumpTo(target) } },
            onToggleMode = {
                val target = currentPage
                // 换排版方式时把缩放复位，免得「已放大」的状态在另一种排版下错位
                zoom.reset()
                state.vertical = !state.vertical
                scope.launch {
                    if (state.vertical) listState.scrollToItem(target)
                    else pagerState.scrollToPage(target)
                }
            },
        )
    }
}

/**
 * 竖式：**整条图片流统一缩放**。
 *
 * 缩放作用在内容的**布局宽度**上（`内容宽 = 视口宽 × scale`）——每一页因此同比变宽 / 变窄，
 * 页与页仍首尾相接、不割裂，纵向滚动翻页照常可用（这正是「按页分别放大」做不到的）。
 * - 放大（>1）时内容宽于视口：用横向平移看两侧（纵向仍由列表滚动）；
 * - 缩小（<1）时内容窄于视口：页面居中，两侧留白。
 */
@Composable
private fun VerticalStream(
    state: ReaderState,
    zoom: ZoomState,
    listState: LazyListState,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectZoomGestures(
                    vertical = true,
                    isMagnified = { zoom.magnified },
                    onGesture = { change, pan -> zoom.onGesture(true, change, pan) },
                    onEnd = { zoom.settle() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .width(maxWidth * zoom.scale)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .graphicsLayer { translationX = zoom.offset.x },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = state.pageCount, key = { it }) { index ->
                    VerticalPage(state, index)
                }
            }
        }
    }
}

/**
 * 横式：单页浏览。缩放作用在当前页的渲染上（放大后单指拖动平移），
 * 放大期间锁住左右翻页，避免平移与翻页抢手势。
 */
@Composable
private fun HorizontalStream(
    state: ReaderState,
    zoom: ZoomState,
    pagerState: PagerState,
) {
    val scrollEnabled by remember { derivedStateOf { !zoom.magnified } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectZoomGestures(
                    vertical = false,
                    isMagnified = { zoom.magnified },
                    onGesture = { change, pan -> zoom.onGesture(false, change, pan) },
                    onEnd = { zoom.settle() },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = scrollEnabled,
            key = { it },
        ) { index ->
            HorizontalPage(state, zoom, index)
        }
    }
}

/**
 * 竖排里的一页：宽度随整条流的内容宽（天然跟着缩放走）。
 * 先按首页宽高比占位（避免加载时整列跳动），取到图后填进去。
 */
@Composable
private fun VerticalPage(state: ReaderState, index: Int) {
    LaunchedEffect(state.source, index) { state.ensure(index) }
    val bitmap = state.bitmaps[index]
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(state.aspect)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            PagePlaceholder(state, index)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 横排里的一页：整页等比缩放到放进可视区，缩放 / 平移由此页的渲染承担。 */
@Composable
private fun HorizontalPage(state: ReaderState, zoom: ZoomState, index: Int) {
    LaunchedEffect(state.source, index) { state.ensure(index) }
    val bitmap = state.bitmaps[index]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds()
            .onSizeChanged { zoom.pageSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            PagePlaceholder(state, index)
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // 缩放 / 平移放在 graphicsLayer 的 block 里读状态：只重绘、不重组
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
            )
        }
    }
}

/**
 * 捏合缩放 / 平移手势。
 *
 * - **双指**：缩放（任何模式下都消费）；
 * - **单指**：仅在「已放大」时消费用于平移 —— 竖式只认横向拖动（纵向留给列表滚动），
 *   横式认任意方向。未放大时不消费，竖式的上下滚动 / 横式的左右翻页都照常。
 *
 * 挂在滚动容器的**外层**即可：竖式纵向滚动由 LazyColumn 自身消费、我们只是不抢；
 * 横式放大时 `userScrollEnabled=false` 已让 Pager 不翻页，单指便落到这里。
 * 手势结束时回调 [onEnd]，由它决定是否吸附回初始大小。
 */
private suspend fun PointerInputScope.detectZoomGestures(
    vertical: Boolean,
    isMagnified: () -> Boolean,
    onGesture: (Float, Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val pointers = event.changes.count { it.pressed }
            val pan = event.calculatePan()
            val take = pointers >= 2 ||
                (isMagnified() && (!vertical || abs(pan.x) > abs(pan.y)))
            if (!take) continue
            onGesture(event.calculateZoom(), pan)
            event.changes.forEach { it.consume() }
        }
        onEnd()
    }
}

/** 还没取到图时的占位：取失败给可点的「重试」块，取图期间是转圈。 */
@Composable
private fun PagePlaceholder(state: ReaderState, index: Int) {
    val scope = rememberCoroutineScope()
    val error = state.failures[index]
    if (error == null) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clickable {
                state.retry(index)
                scope.launch { state.ensure(index) }
            }
            .padding(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_broken_image),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.reader_page_failed, index + 1, error),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 底部控制栏：页码 / 进度滑杆（拖动即跳页）/ 横竖切换 / 上下页 / 页码跳转。
 *
 * [currentPage] 特意做成 lambda：让「页码订阅」落在本组件里，滑动时不牵动阅读器整屏重组。
 */
@Composable
private fun ReaderControls(
    state: ReaderState,
    currentPage: () -> Int,
    onTurn: (Int) -> Unit,
    onJump: (Int) -> Unit,
    onToggleMode: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    val page = currentPage()
    val lastPage = (state.pageCount - 1).coerceAtLeast(0)
    val sliderValue = dragging ?: page.toFloat()

    fun confirmJump() {
        jumpOpen = false
        val target = jumpInput.toIntOrNull() ?: return
        onJump(target - 1)
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pageLabel(state, page),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 60.dp, max = 120.dp),
                )
                Slider(
                    value = sliderValue,
                    valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
                    enabled = lastPage > 0,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        val target = (dragging ?: page.toFloat()).roundToInt()
                        dragging = null
                        onJump(target)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onToggleMode) {
                    Text(
                        // 显示「将要切到」的模式，与桌面版按钮的含义一致
                        text = stringResource(
                            if (state.vertical) R.string.reader_mode_horizontal
                            else R.string.reader_mode_vertical
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onTurn(-1) }, enabled = page > 0) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.btn_prev_page),
                    )
                }
                TextButton(onClick = {
                    jumpInput = (page + 1).toString()
                    jumpOpen = true
                }) {
                    Text(stringResource(R.string.reader_jump), maxLines = 1)
                }
                IconButton(onClick = { onTurn(1) }, enabled = page < lastPage) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = stringResource(R.string.btn_next_page),
                    )
                }
            }
        }
    }

    if (jumpOpen) {
        AlertDialog(
            onDismissRequest = { jumpOpen = false },
            title = { Text(stringResource(R.string.reader_jump_title)) },
            text = {
                OutlinedTextField(
                    value = jumpInput,
                    onValueChange = { text -> jumpInput = text.filter { it.isDigit() }.take(7) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reader_jump_label, state.pageCount)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirmJump() }),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmJump() }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { jumpOpen = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 底部左侧：按页编号的数据源显示「当前页/总页数」，图片文件夹显示当前文件名。 */
private fun pageLabel(state: ReaderState, page: Int): String {
    if (state.numberedPages) return "%d / %d".format(page + 1, state.pageCount)
    return state.names[page] ?: "%d / %d".format(page + 1, state.pageCount)
}

private fun decodePage(bytes: ByteArray): Bitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (_: Throwable) {
    null
}