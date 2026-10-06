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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.BookmarkItem
import com.j2pmobile.android.ChapterItem
import com.j2pmobile.android.ReaderSource
import com.j2pmobile.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/**
 * 缩放范围：1.0 = 原始适配大小（初始），可放大到 4 倍，也可缩小到 0.5 倍。
 *
 * 缩小（<1）时内容窄于视口：竖式页面居中、两侧留白，横式整页更小；此时不产生平移
 * （[ZoomState.clamp] 会把 offset 夹回 0），所以不会出现「缩小后左右滑动漂移」。
 */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

/**
 * 松手时若缩放落在 [SNAP_LOW, SNAP_HIGH] 内，就带动画吸附回初始大小（1.0）并复位平移，恢复常规滚动。
 * 区间给得宽（0.8~1.3 = 要明显放大 / 明显缩小才保留），避免「想还原却弹不回去」的迟钝感；
 * 落在区间外的保持不动，因此放大与缩小都真实可用。
 */
private const val SNAP_LOW = 0.8f
private const val SNAP_HIGH = 1.3f

/** 吸附回 1.0 的动画时长（毫秒）。 */
private const val SNAP_DURATION_MS = 160

/** 控制栏缩放输入框的百分比上下限（与 [MIN_ZOOM] / [MAX_ZOOM] 一致）与 +/− 每次的步长。 */
private val MIN_ZOOM_PERCENT = (MIN_ZOOM * 100f).roundToInt()
private val MAX_ZOOM_PERCENT = (MAX_ZOOM * 100f).roundToInt()
private const val ZOOM_STEP_PERCENT = 10

/**
 * 「点击翻页」的判定区域：左右（或上下）各占 30% 的边缘用于翻页，中间 40% 用于显隐界面。
 * 只有沉浸式（界面收起）时边缘点击才翻页，界面显示时点击边缘不做任何事。
 */
private const val CLICK_REGION = 0.3f

/** 自动翻页默认间隔（秒）与允许的取值区间。 */
private const val DEFAULT_AUTO_TURN_SECONDS = 5
private const val MIN_AUTO_TURN_SECONDS = 1
private const val MAX_AUTO_TURN_SECONDS = 3600

/**
 * 阅读器的缩放 / 平移状态（整个阅读界面共用一套，由 [ReaderScreen] 持有）。
 *
 * 缩放在两种排版下的作用方式不同：
 * - **竖式**：作用在整条图片流的**布局宽度**上（`内容宽 = 视口宽 × scale`），
 *   每一页因此同比变宽 / 变高、页与页仍首尾相接，纵向滚动翻页照常可用；放大后横向平移看两侧。
 * - **横式**：单页浏览，缩放作用在当前页的渲染（`graphicsLayer`），放大后可双向平移。
 *
 * 关键点：
 * - 缩放**以手指中心为锚点**——[onGesture] 里按 `k·offset + (centroid - center)·(1-k)` 更新平移，
 *   所以捏合处的内容不会跑掉（不再是「以画面中心缩放」）。
 * - 平移一律**夹在 `(scale-1)×尺寸/2` 之内**，scale 回到 1 时平移正好为 0，
 *   不会出现「看着像初始比例、左右滑动却漂移」。
 * - 松手后 [settle]：缩放落在 [SNAP_LOW, SNAP_HIGH] 内就带动画吸附回 1.0。
 *
 * 缩放的**入口有两种**：双指捏合（[onGesture]，竖式 / 横式都可用、以手指为锚点）；
 * 底部控制栏的 +/− 与输入框（[zoomTo]，以画面中心为锚点、带短动画；不做吸附，精确落在所选比例）。
 */
private class ZoomState(private val scope: CoroutineScope) {

    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** 可视区尺寸（内容宽度换算与平移边界都用它）。 */
    var viewport by mutableStateOf(IntSize.Zero)

    /** 是否处于「放大」状态：只有放大才需要横向平移、才锁住横式的左右翻页。 */
    val magnified: Boolean get() = scale > 1f + 0.001f

    private var snapJob: Job? = null

    /**
     * 一次捏合 / 拖动：先叠加平移，再以 [centroid] 为锚点按 [zoomChange] 缩放，最后夹紧。
     * 返回本次实际生效的缩放倍率 k（手势被夹在 [MIN_ZOOM]/[MAX_ZOOM] 时可能为 1），
     * 竖式据此补偿列表滚动（见 [VerticalStream]）。
     *
     * 有意做成**非挂起**：手势回调在 `AwaitPointerEventScope`（`@RestrictsSuspension`）里执行，
     * 只能调用非挂起函数；竖式的滚动补偿因此改用同步的 `dispatchRawDelta`。
     */
    fun onGesture(
        vertical: Boolean,
        zoomChange: Float,
        pan: Offset,
        centroid: Offset,
    ): Float {
        snapJob?.cancel()
        snapJob = null
        val old = scale
        val next = (old * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = if (old > 0f) next / old else 1f
        var x = offset.x + pan.x
        // 竖式的纵向翻页交给 LazyColumn 滚动，不做纵向平移
        var y = if (vertical) offset.y else offset.y + pan.y
        if (k != 1f) {
            x = k * x + (centroid.x - viewport.width / 2f) * (1f - k)
            if (!vertical) {
                y = k * y + (centroid.y - viewport.height / 2f) * (1f - k)
            }
        }
        scale = next
        clamp(x, y)
        return k
    }

    /** 手势结束：只是略微放大 / 略微缩小（落在吸附区间内）就带动画吸附回 1.0 并复位平移。 */
    fun settle() {
        if (scale > SNAP_HIGH || scale < SNAP_LOW) return
        if (scale == 1f && offset == Offset.Zero) return
        snapJob?.cancel()
        snapJob = scope.launch {
            val startScale = scale
            val startOffset = offset
            animate(0f, 1f, animationSpec = tween(SNAP_DURATION_MS)) { t, _ ->
                scale = startScale + (1f - startScale) * t
                clamp(startOffset.x * (1f - t), startOffset.y * (1f - t))
            }
            scale = 1f
            offset = Offset.Zero
        }
    }

    /**
     * 按钮 / 输入框直接设定缩放比例（以**画面中心**为锚点，带短动画）。
     *
     * 与 [settle] 不同：这里不做「接近 1.0 就吸附」——按钮和输入框就是要精确落在用户选的比例上。
     * 缩小时平移会被 [clamp] 夹回 0，所以缩小后不会残留偏移。
     */
    fun zoomTo(target: Float) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == scale) return
        snapJob?.cancel()
        val startScale = scale
        val startOffset = offset
        // 绕画面中心缩放时，平移同比放大 k 倍（k = 新 / 旧）
        val k = if (startScale > 0f) next / startScale else 1f
        snapJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(SNAP_DURATION_MS)) { t, _ ->
                scale = startScale + (next - startScale) * t
                clamp(startOffset.x * (1f - t + t * k), startOffset.y * (1f - t + t * k))
            }
            scale = next
            clamp(startOffset.x * k, startOffset.y * k)
        }
    }

    fun reset() {
        snapJob?.cancel()
        snapJob = null
        scale = 1f
        offset = Offset.Zero
    }

    /** 把平移夹在「内容超出可视区的那一半」以内；scale 为 1 时正好夹回 0。 */
    private fun clamp(x: Float, y: Float) {
        val lx = max(0f, (scale - 1f) * viewport.width / 2f)
        val ly = max(0f, (scale - 1f) * viewport.height / 2f)
        offset = Offset(x.coerceIn(-lx, lx), y.coerceIn(-ly, ly))
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
     * 阅读设置：全部只在本次运行内保留（[open] / [reset] 都不清空，进出同一本子再回来仍是同一套）。
     * 都是阅读器自己的显示偏好，不落 conf.yml，避免污染全局配置。
     */
    var volumeTurn by mutableStateOf(false)
    var autoTurn by mutableStateOf(false)
    var autoTurnSeconds by mutableIntStateOf(DEFAULT_AUTO_TURN_SECONDS)
    var clickTurn by mutableStateOf(false)

    /** 点击翻页的判定方向：true = 上下点击，false = 左右点击。 */
    var clickVertical by mutableStateOf(false)

    /** 横式翻页方向：true = 从右到左（日漫习惯），false = 从左到右。 */
    var horizontalRtl by mutableStateOf(false)

    // 章节弹窗 / 添加书签弹窗由底栏触发，故放在共享状态里
    // （阅读设置页与书签页已改成独立的路由页面，不再占用这里的开关）
    var chapterOpen by mutableStateOf(false)
    var bookmarkAddOpen by mutableStateOf(false)

    /**
     * 两套滚动状态放在这里（而不是 ReaderScreen 内部 remember）：去「阅读设置 / 书签」这两个
     * 独立页面再回来时，ReaderScreen 会重新组合，若状态随组合销毁就会跳回第一页。
     */
    val listState = LazyListState()
    val pagerState = PagerState(0, 0f) { pageCount }

    /** 待执行的跳页（进新本子归零、从书签页跳转）：由 ReaderScreen 挂载时消费一次。 */
    var pendingJump: Int? = null

    /**
     * 沉浸式浏览：收起顶栏、底部控制栏与系统栏，画面占满整屏；点一下画面切换。
     * 由 [ReaderScreen] 触发、[AppShell] 读它来决定顶栏显隐，故放在这里共享。
     */
    var immersive by mutableStateOf(false)
        private set

    val bitmaps = mutableStateMapOf<Int, Bitmap>()
    val names = mutableStateMapOf<Int, String>()
    val failures = mutableStateMapOf<Int, String>()

    /**
     * 各页的**原始图片字节**（未解码）。只用来支持「保存当前页到相册」：
     * 原样落盘，不重新编码，画质与在线浏览看到的完全一致。
     *
     * 体积远小于解码后的位图（一页几百 KB vs 9 MB），因此与 [bitmaps] 同窗口淘汰。
     */
    val raws = mutableStateMapOf<Int, ByteArray>()

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
        closeOverlays()
        pendingJump = 0
        center = 0
        initialDone = false
        bitmaps.clear()
        names.clear()
        failures.clear()
        raws.clear()
        pending.clear()
        queued.clear()
    }

    /** 已经离开阅读器：丢掉会话引用与缓存（Python 侧句柄由外壳调用 close 释放）。 */
    fun reset() {
        source = null
        pageCount = 0
        immersive = false
        closeOverlays()
        pendingJump = null
        bitmaps.clear()
        names.clear()
        failures.clear()
        raws.clear()
        pending.clear()
        queued.clear()
    }

    /** 点一下画面：收起 / 唤出界面。 */
    fun toggleImmersive() {
        immersive = !immersive
    }

    /** 关掉阅读器里的两个弹窗（换本子或离开阅读器时调用）。 */
    fun closeOverlays() {
        chapterOpen = false
        bookmarkAddOpen = false
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
                                raws[index] = result.value.data
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
            raws.remove(it)
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
 * **缩放**：范围 0.5×~4×，两种入口并存 ——
 * - **双指捏合**（竖式 / 横式都可用；以手指中心为锚点，松手时若落在 0.8×~1.3× 就带动画吸附回 1×）；
 * - **底部控制栏的 − / 输入框 / +**（输入框与当前比例实时同步）。
 * 竖式为**整条流统一缩放**（页与页首尾相接，放大后单指横向平移看两侧、纵向照常滚动翻页）。
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
    // 保存当前页到相册：Android 10 起无需权限，更早版本先申请存储权限再保存
    val context = LocalContext.current
    var pendingSavePage by remember { mutableStateOf<Int?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingSavePage
        pendingSavePage = null
        if (granted && target != null) {
            scope.launch { savePageToGallery(context, state, target) }
        } else if (!granted) {
            Toast.makeText(context, R.string.reader_save_page_no_permission, Toast.LENGTH_SHORT).show()
        }
    }
    val requestSavePage: (Int) -> Unit = { index ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch { savePageToGallery(context, state, index) }
        } else {
            pendingSavePage = index
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    // 两套滚动状态由 ReaderState 常驻：去「阅读设置 / 书签」页再回来不会跳回第一页
    val listState = state.listState
    val pagerState = state.pagerState
    // 缩放 / 平移：整个阅读界面共用一套（离开阅读器即随组合销毁而复位）
    val zoom = remember { ZoomState(scope) }
    // 只在 lambda（控制栏 / 预取流）里读，不在组合期读：滑动不牵动整屏重组
    val currentPage by remember {
        derivedStateOf {
            if (state.vertical) listState.firstVisibleItemIndex else pagerState.currentPage
        }
    }

    // 进阅读器先处理一次待跳页（新本子归零 / 从书签页跳转），再铺页；
    // 此后聚焦页一变就按「聚焦页优先」重排预取窗口。
    // 页码只在 snapshotFlow 的协程里读，不在组合期读 —— 滑动不会牵动整屏重组。
    LaunchedEffect(state.source) {
        state.pendingJump?.let { target ->
            state.pendingJump = null
            val index = target.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
            if (state.vertical) listState.scrollToItem(index) else pagerState.scrollToPage(index)
        }
        state.initialPrefetch(scope)
        snapshotFlow {
            if (state.vertical) listState.firstVisibleItemIndex else pagerState.currentPage
        }.collect { page -> state.prefetch(scope, page) }
    }

    val jumpTo: suspend (Int) -> Unit = { target ->
        val index = target.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        if (state.vertical) listState.scrollToItem(index) else pagerState.scrollToPage(index)
    }

    /** 翻一页：音量键 / 点击区域 / 底栏上下页共用。 */
    fun turn(delta: Int) {
        scope.launch { jumpTo(currentPage + delta) }
    }

    // 自动翻页：只在沉浸式（界面收起）时运行；显隐切换会重启本效果，相当于重新计时。
    // 到最后一页就停下（不循环），避免用户没在看时无限翻下去。
    val autoTurnActive = state.autoTurn && state.immersive && state.pageCount > 1
    LaunchedEffect(autoTurnActive, state.autoTurnSeconds, state.source) {
        if (!autoTurnActive) return@LaunchedEffect
        val seconds = state.autoTurnSeconds.coerceIn(MIN_AUTO_TURN_SECONDS, MAX_AUTO_TURN_SECONDS)
        while (true) {
            delay(seconds * 1000L)
            val page = if (state.vertical) listState.firstVisibleItemIndex else pagerState.currentPage
            if (page >= state.pageCount - 1) break
            jumpTo(page + 1)
        }
    }

    // 音量键翻页：让画面本身可聚焦，并在预览阶段抢在系统调整音量之前处理按键。
    // 该修饰符只挂在阅读页，离开阅读器即随组合销毁，故不会影响其它页面。
    val focusRequester = remember { FocusRequester() }
    val focusSource = remember { MutableInteractionSource() }
    // requestFocus 在节点尚未挂到布局上时会抛异常，这里兜一下（下一帧的另一个效果会再补一次）
    LaunchedEffect(state.source) { runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(state.chapterOpen, state.bookmarkAddOpen) {
        // 弹窗关闭后把焦点还给画面，否则音量键会失效（弹窗期间不抢，免得弹窗输入框失焦）
        if (!state.chapterOpen && !state.bookmarkAddOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { zoom.viewport = it }
                .focusRequester(focusRequester)
                // focusable 默认不传 indication，因此不会画出焦点高亮
                .focusable(interactionSource = focusSource)
                .onPreviewKeyEvent { event ->
                    if (!state.volumeTurn || event.type != KeyEventType.KeyDown) {
                        false
                    } else when (event.key) {
                        // 上键向上翻（上一页）、下键向下翻（下一页）：与「上=上面/前面内容」的直觉一致。
                        // 中间 40% 的点击区域仍由 detectTapGestures 负责，两者互不冲突。
                        Key.VolumeUp -> {
                            turn(-1)
                            true
                        }

                        Key.VolumeDown -> {
                            turn(1)
                            true
                        }

                        else -> false
                    }
                }
                // 点一下收起 / 唤出界面；开启「点击翻页」后边缘 30% 翻页（仅界面收起时生效）
                .pointerInput(state.clickTurn, state.clickVertical) {
                    detectTapGestures { position ->
                        val width = zoom.viewport.width.toFloat()
                        val height = zoom.viewport.height.toFloat()
                        if (!state.clickTurn || width <= 0f || height <= 0f) {
                            state.toggleImmersive()
                            return@detectTapGestures
                        }
                        val previous = if (state.clickVertical) {
                            position.y < height * CLICK_REGION
                        } else {
                            position.x < width * CLICK_REGION
                        }
                        val next = if (state.clickVertical) {
                            position.y > height * (1f - CLICK_REGION)
                        } else {
                            position.x > width * (1f - CLICK_REGION)
                        }
                        when {
                            previous && state.immersive -> turn(-1)
                            next && state.immersive -> turn(1)
                            previous || next -> Unit   // 界面显示时点边缘不翻页
                            else -> state.toggleImmersive()
                        }
                    }
                }
        ) {
            if (state.vertical) {
                VerticalStream(state, zoom, listState)
            } else {
                HorizontalStream(state, zoom, pagerState)
            }
        }
        if (!state.immersive) {
            ReaderControls(
                state = state,
                currentPage = { currentPage },
                zoomPercent = { (zoom.scale * 100f).roundToInt() },
                onZoomPercent = { percent -> zoom.zoomTo(percent / 100f) },
                onTurn = { delta -> turn(delta) },
                onJump = { target -> scope.launch { jumpTo(target) } },
                onSavePage = { requestSavePage(currentPage) },
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

    // 章节弹窗 / 添加书签弹窗（「阅读设置页」「书签页」已改为独立路由页面，不在这里渲染）
    if (state.chapterOpen) {
        ChapterDialog(
            state = state,
            currentPage = currentPage,
            onJump = { target -> scope.launch { jumpTo(target) } },
            onClose = { state.chapterOpen = false },
        )
    }
    if (state.bookmarkAddOpen) {
        AddBookmarkDialog(
            state = state,
            currentPage = currentPage,
            onClose = { state.bookmarkAddOpen = false },
        )
    }
}

/**
 * 竖式：**整条图片流统一缩放**。
 *
 * 缩放作用在内容的**布局宽度**上（`内容宽 = 视口宽 × scale`）——每一页因此同比变宽 / 变高，
 * 页与页仍首尾相接、不割裂，纵向滚动翻页照常可用（这正是「按页分别放大」做不到的）；
 * 放大后内容宽于视口，用**单指横向拖动**看两侧；缩小时内容窄于视口，居中留白。
 *
 * **必须用 `requiredWidth` 而不是 `width`**：`Modifier.width` 会把请求的宽度**夹进父约束**
 * （父就是 `BoxWithConstraints`，上限 = 视口宽），于是 `scale > 1` 时布局根本不会变宽；
 * 而 `scale` 已经 > 1 → `magnified` 为真 → 单指横向拖动被当成平移 ——
 * 表现就是「按放大键画面没变大，左右滑却漂移」（缩小能生效，是因为比视口窄，不受夹取）。
 * `requiredWidth` 忽略父约束，布局才能真的宽于视口。
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
            // 双指捏合缩放；放大后单指横向拖动平移（纵向不抢，交给 LazyColumn 滚动）
            .pointerInput(Unit) {
                detectZoomGestures(
                    vertical = true,
                    isMagnified = { zoom.magnified },
                    onGesture = { change, pan, centroid ->
                        zoom.onGesture(true, change, pan, centroid)
                    },
                    onEnd = { zoom.settle() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .requiredWidth(maxWidth * zoom.scale)
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
                    onGesture = { change, pan, centroid ->
                        zoom.onGesture(false, change, pan, centroid)
                    },
                    onEnd = { zoom.settle() },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = scrollEnabled,
            // 「从右到左」只改排布方向，页码索引不变（跳页 / 进度仍按 0 起算）
            reverseLayout = state.horizontalRtl,
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
            .clipToBounds(),
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
 * 捏合缩放 / 平移手势（竖式与横式共用）。
 *
 * - **双指**：缩放（消费），并把手指中心 [calculateCentroid] 一并回传，
 *   供 [ZoomState.onGesture] 做「以手指为锚点」的缩放；
 * - **单指**：仅在「已放大」时消费用于平移 —— 竖式只认横向拖动（纵向留给列表滚动），
 *   横式认任意方向。未放大时不消费，竖式的上下滚动 / 横式的左右翻页都照常。
 *
 * **在 [PointerEventPass.Initial] 阶段收发事件**：`LazyColumn` / `Pager` 在 Main 阶段（自下而上）
 * 先于外层拿到事件，双指手势会被它们先行消费掉。Initial 阶段是自上而下、外层先拿到，抢在滚动
 * 组件之前把双指事件标记为已消费；单指未放大时则完全不消费，滚动 / 翻页照旧。
 *
 * 一旦本次手势出现过双指，后续即使只剩一指也继续消费，避免抬手瞬间滚动组件「接盘」乱跳。
 * 手势结束时回调 [onEnd]，由它决定是否吸附回初始大小。
 */
private suspend fun PointerInputScope.detectZoomGestures(
    vertical: Boolean,
    isMagnified: () -> Boolean,
    onGesture: (Float, Offset, Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var multiTouch = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) multiTouch = true
            val pan = event.calculatePan()
            val take = multiTouch ||
                (isMagnified() && (!vertical || abs(pan.x) > abs(pan.y)))
            if (!take) continue
            onGesture(event.calculateZoom(), pan, event.calculateCentroid(useCurrent = true))
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
 * 底部控制栏：页码 + 进度滑杆 + 缩放 / 横竖切换 + 快捷选章 + 添加书签 + 上页 / 跳页 / 下页。
 *
 * [currentPage] 与 [zoomPercent] 特意做成 lambda：让「页码 / 缩放订阅」落在本组件里，
 * 滑动与缩放不牵动阅读器整屏重组。
 *
 * 缩放簇（− / 输入框 / % / +）放在第 1 行、贴着进度滑杆，是为了给第 2 行新增的
 * 「章节 / 书签」腾出位置 —— 两行都塞满的话，窄屏上最右边的「下一页」会被挤出屏幕。
 */
@Composable
private fun ReaderControls(
    state: ReaderState,
    currentPage: () -> Int,
    zoomPercent: () -> Int,
    onZoomPercent: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onJump: (Int) -> Unit,
    onSavePage: () -> Unit,
    onToggleMode: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    // 非 null 表示正在编辑缩放比例：此时输入框不被外部比例覆盖，提交后清空
    var zoomInput by remember { mutableStateOf<String?>(null) }
    val page = currentPage()
    val percent = zoomPercent()
    val lastPage = (state.pageCount - 1).coerceAtLeast(0)
    val sliderValue = dragging ?: page.toFloat()

    fun confirmJump() {
        jumpOpen = false
        val target = jumpInput.toIntOrNull() ?: return
        onJump(target - 1)
    }

    fun commitZoom() {
        val raw = zoomInput ?: return
        zoomInput = null
        raw.toIntOrNull()?.let(onZoomPercent)
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 第 1 行：页码 + 进度滑杆 + 缩放（缩放挪到本行，给第 2 行新增的章节 / 书签腾位置）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pageLabel(state, page),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 54.dp, max = 110.dp),
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
                // 缩放：− / 输入框（与当前比例实时同步）/ %
                IconButton(
                    onClick = { onZoomPercent(percent - ZOOM_STEP_PERCENT) },
                    enabled = percent > MIN_ZOOM_PERCENT,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("−", style = MaterialTheme.typography.titleMedium)
                }
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicTextField(
                        value = zoomInput ?: percent.toString(),
                        onValueChange = { text -> zoomInput = text.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitZoom() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .onFocusChanged { if (!it.isFocused) commitZoom() },
                    )
                }
                Text("%", style = MaterialTheme.typography.labelSmall)
                IconButton(
                    onClick = { onZoomPercent(percent + ZOOM_STEP_PERCENT) },
                    enabled = percent < MAX_ZOOM_PERCENT,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
            // 第 2 行：横竖切换 / 快捷选章 / 添加书签 / 上页 / 跳页 / 下页
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onToggleMode,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        // 显示「将要切到」的模式，与桌面版按钮的含义一致
                        text = stringResource(
                            if (state.vertical) R.string.reader_mode_horizontal
                            else R.string.reader_mode_vertical
                        ),
                        maxLines = 1,
                    )
                }
                // 快捷选章：本子有多章才可点（本地单章文件 / 单章本子置灰）
                TextButton(
                    onClick = { state.chapterOpen = true },
                    enabled = (state.source?.chapters ?: 1) > 1,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.reader_chapters), maxLines = 1)
                }
                IconButton(
                    onClick = { state.bookmarkAddOpen = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = stringResource(R.string.reader_bookmark_add),
                    )
                }
                // 保存当前页到相册（原图落盘，文件名为「本子ID-页码」）
                IconButton(
                    onClick = onSavePage,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = stringResource(R.string.reader_save_page),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onTurn(-1) },
                    enabled = page > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.btn_prev_page),
                    )
                }
                TextButton(
                    onClick = {
                        jumpInput = (page + 1).toString()
                        jumpOpen = true
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.reader_jump), maxLines = 1)
                }
                IconButton(
                    onClick = { onTurn(1) },
                    enabled = page < lastPage,
                    modifier = Modifier.size(36.dp),
                ) {
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

/** 保存到相册的相册名（Android 10+ 下的 `Pictures/<它>/`）。 */
private const val GALLERY_DIRNAME = "J2P Mobile"

/**
 * 把当前页原图写进系统相册，文件名 `<本子ID>-<页码5位>.<扩展名>`（如 `1462837-00003.jpg`）。
 *
 * 取到的原始字节直接落盘，不重新编码；字节还没取到（或已被缓存淘汰）时提示稍后重试。
 * 落盘走 IO 线程 —— MediaStore 插入是一次 IPC，大图写入也要时间，不能压在主线程上。
 */
private suspend fun savePageToGallery(context: Context, state: ReaderState, index: Int) {
    val data = state.raws[index]
    if (data == null) {
        Toast.makeText(context, R.string.reader_save_page_pending, Toast.LENGTH_SHORT).show()
        return
    }
    val outcome = runCatching {
        withContext(Dispatchers.IO) {
            writePageToGallery(context, state.source?.albumId.orEmpty(), index, data)
        }
    }
    val text = outcome.fold(
        onSuccess = { name -> context.getString(R.string.reader_save_page_done, name) },
        onFailure = { exc -> context.getString(R.string.reader_save_page_failed, exc.message.orEmpty()) },
    )
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 实际写盘：Android 10 起用 MediaStore（分区存储），更早版本写公共图片目录并通知媒体库。 */
private fun writePageToGallery(
    context: Context,
    albumId: String,
    index: Int,
    data: ByteArray,
): String {
    val extension = imageExtension(data)
    val name = "%s-%05d.%s".format(sanitizeFileName(albumId), index + 1, extension)
    val mime = if (extension == "jpg") "image/jpeg" else "image/$extension"
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + GALLERY_DIRNAME,
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入相册")
        try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (exc: Throwable) {
            resolver.delete(uri, null, null)   // 写失败时不要把半截文件留在相册里
            throw exc
        }
    } else {
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folder = File(pictures, GALLERY_DIRNAME)
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IllegalStateException("无法创建相册目录")
        }
        val target = File(folder, name)
        target.outputStream().use { it.write(data) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
    }
    return name
}

/** 文件名里不允许出现的字符（与桌面端 `core.library.save_page_image` 的规则保持一致）。 */
private fun sanitizeFileName(text: String): String {
    val cleaned = text.trim().replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
    return cleaned.ifEmpty { "album" }
}

/** 按文件头判断图片扩展名，拿不准时按 jpg 处理。 */
private fun imageExtension(data: ByteArray): String = when {
    data.size >= 8 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "jpg"
    data.size >= 8 && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() -> "png"
    data.size >= 12 && data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() -> "webp"
    data.size >= 6 && data[0] == 'G'.code.toByte() -> "gif"
    else -> "jpg"
}

/**
 * 阅读设置页（独立路由，不是弹窗）：按「通用 / 横式 / 竖式」分组。
 *
 * - 通用：音量键翻页、自动翻页（带秒数输入框）、点击翻页（带判定方向切换 + 区域说明）；
 * - 横式：翻页方向（从左到右 / 从右到左）；
 * - 竖式：目前没有独立项，给一行说明占位。
 *
 * 设置本身是会话级的（见 [ReaderState]），本页只负责读写它；返回上一级走顶栏的返回键。
 */
@Composable
internal fun ReaderSettingsScreen(state: ReaderState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingSection(stringResource(R.string.reader_settings_general))
            SwitchRow(
                label = stringResource(R.string.reader_setting_volume_turn),
                checked = state.volumeTurn,
                onChange = { state.volumeTurn = it },
            )
            SwitchRow(
                label = stringResource(R.string.reader_setting_auto_turn),
                checked = state.autoTurn,
                onChange = { state.autoTurn = it },
            )
            if (state.autoTurn) AutoTurnIntervalRow(state)
            SwitchRow(
                label = stringResource(R.string.reader_setting_click_turn),
                checked = state.clickTurn,
                onChange = { state.clickTurn = it },
            )
            // 只有开启点击翻页，才展示「上下点击 / 左右点击」的切换
            if (state.clickTurn) {
                SwitchValueRow(
                    label = stringResource(R.string.reader_setting_click_direction),
                    value = stringResource(
                        if (state.clickVertical) R.string.reader_click_area_vertical
                        else R.string.reader_click_area_horizontal
                    ),
                    checked = state.clickVertical,
                    onChange = { state.clickVertical = it },
                )
                // 区域说明只放在设置里（阅读画面上不浮提示条，免得挡画面）
                Text(
                    text = stringResource(
                        if (state.clickVertical) R.string.reader_hint_click_tb
                        else R.string.reader_hint_click_lr
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                )
            }

            SettingSection(stringResource(R.string.reader_settings_horizontal))
            SwitchValueRow(
                label = stringResource(R.string.reader_setting_turn_direction),
                value = stringResource(
                    if (state.horizontalRtl) R.string.reader_dir_rtl
                    else R.string.reader_dir_ltr
                ),
                checked = state.horizontalRtl,
                onChange = { state.horizontalRtl = it },
            )

            SettingSection(stringResource(R.string.reader_settings_vertical))
            Text(
                text = stringResource(R.string.reader_settings_vertical_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 自动翻页的间隔输入（秒）：只收数字，越界 / 空值都保持上一个有效值。 */
@Composable
private fun AutoTurnIntervalRow(state: ReaderState) {
    var input by remember { mutableStateOf(state.autoTurnSeconds.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.reader_auto_turn_interval),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = input,
            onValueChange = { text ->
                input = text.filter { it.isDigit() }.take(4)
                val seconds = input.toIntOrNull()
                if (seconds != null && seconds in MIN_AUTO_TURN_SECONDS..MAX_AUTO_TURN_SECONDS) {
                    state.autoTurnSeconds = seconds
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.width(84.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.reader_unit_second),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 设置分组标题（通用 / 横式 / 竖式）。 */
@Composable
private fun SettingSection(title: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/** 一行「开关」设置。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 带「当前设置」文本的开关行：开关旁边同步显示当前取值（方向 / 判定区域这类二选一）。 */
@Composable
private fun SwitchValueRow(
    label: String,
    value: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 快捷选章弹窗：列出各章标题与页范围，当前章高亮，点一行跳过去。
 *
 * 章节列表由 Python 侧现取（在线本子要逐章取页数，首次会慢一点），带竖直滚动条。
 */
@Composable
private fun ChapterDialog(
    state: ReaderState,
    currentPage: Int,
    onJump: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var chapters by remember { mutableStateOf<List<ChapterItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.handle) {
        loading = true
        error = null
        when (val result = ApiBridge.readerChapters(state.handle)) {
            is ApiResult.Ok -> chapters = result.value
            is ApiResult.Err -> error = result.message
        }
        loading = false
    }

    // 当前页落在哪一章：最后一个起始页号不超过当前页的章
    val currentChapter = chapters.indexOfLast { it.start <= currentPage }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.reader_chapters_title)) },
        text = {
            Box(modifier = Modifier.heightIn(max = 380.dp)) {
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.reader_chapters_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    error != null -> Text(
                        text = stringResource(R.string.reader_chapters_failed, error!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    chapters.isEmpty() -> Text(
                        text = stringResource(R.string.reader_chapters_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> ScrollPane(modifier = Modifier.fillMaxWidth()) {
                        chapters.forEachIndexed { index, chapter ->
                            val selected = index == currentChapter
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        onJump(chapter.start)
                                        onClose()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.title.ifEmpty { "#%d".format(index + 1) },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.reader_chapter_range,
                                            chapter.start + 1,
                                            chapter.start + chapter.pages,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (selected) {
                                    Text(
                                        text = stringResource(R.string.reader_chapter_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/**
 * 添加书签弹窗：展示当前页码与当前时间，备注可留空。
 *
 * 显示的时间是打开弹窗那一刻；真正落盘的创建时间由 Python 侧再取一次，两者可能差几秒。
 */
@Composable
private fun AddBookmarkDialog(
    state: ReaderState,
    currentPage: Int,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    val now = remember { formatTimestamp(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.reader_bookmark_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.reader_bookmark_page_value, currentPage + 1, state.pageCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.reader_bookmark_time) + ": " + now,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reader_bookmark_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                scope.launch { ApiBridge.bookmarkAdd(state.handle, currentPage, note) }
            }) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/** 书签页的排序字段（默认按页码）。 */
private val BOOKMARK_SORT_KEYS = listOf("page", "note", "created", "updated")

/**
 * 书签页（独立路由，不是弹窗）：列出当前本子的书签，支持跳转 / 改备注 / 删除 / 刷新 / 排序。
 *
 * 数据每次打开都从磁盘读（Python 侧 `<本子ID>.json`），因此与应用内的增删始终同步；
 * 排序默认按页码升序，「备注」一档用 [Collator] 比较（中文按拼音）。
 * 标题与返回键由外壳的顶栏提供，本页只负责内容。
 */
@Composable
internal fun BookmarksScreen(
    state: ReaderState,
    currentPage: () -> Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<BookmarkItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortKey by remember { mutableStateOf("page") }
    var desc by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<BookmarkItem?>(null) }
    var editTarget by remember { mutableStateOf<BookmarkItem?>(null) }
    var editNote by remember { mutableStateOf("") }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            when (val result = ApiBridge.bookmarkLoad(state.handle)) {
                is ApiResult.Ok -> items = result.value
                is ApiResult.Err -> error = result.message
            }
            loading = false
        }
    }

    LaunchedEffect(state.handle) { reload() }

    // 当前页只用来「高亮它所在的那条 + 显示页码」，本页不随阅读滚动重组
    val page = currentPage()
    val sorted = remember(items, sortKey, desc) { sortBookmarks(items, sortKey, desc) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // 排序：字段下拉 + 升降序切换（外观与资源管理器 / 任务页一致）；右侧是计数与刷新
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BookmarkSortBox(sortKey) { picked ->
                    if (picked != sortKey) {
                        sortKey = picked
                        desc = false
                    }
                }
                IconButton(
                    onClick = { desc = !desc },
                    enabled = !loading && sorted.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (desc) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward
                        ),
                        contentDescription = stringResource(
                            if (desc) R.string.btn_order_to_asc else R.string.btn_order_to_desc
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.explorer_count, sorted.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { reload() }, enabled = !loading) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.reader_bookmark_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Text(
                        text = stringResource(R.string.explorer_reading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )

                    error != null -> Text(
                        text = stringResource(R.string.reader_bookmarks_failed, error!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                    )

                    sorted.isEmpty() -> Text(
                        text = stringResource(R.string.reader_bookmark_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )

                    else -> ScrollPane(modifier = Modifier.fillMaxSize()) {
                        sorted.forEach { item ->
                            BookmarkRow(
                                item = item,
                                isCurrent = item.page == page,
                                onClick = { actionTarget = item },
                            )
                        }
                    }
                }
            }
        }
    }

    // 选中一条后的操作：跳转 / 修改备注 / 删除
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(stringResource(R.string.reader_bookmark_actions)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = target.note.ifEmpty { stringResource(R.string.reader_bookmark_no_note) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.reader_bookmark_page_value, target.page + 1, state.pageCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                // 三个动作摆在同一行，按钮宽度按内容自适应，不再竖着堆成一列
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        actionTarget = null
                        onJump(target.page)
                    }) { Text(stringResource(R.string.reader_bookmark_jump), maxLines = 1) }
                    TextButton(onClick = {
                        editNote = target.note
                        editTarget = target
                        actionTarget = null
                    }) { Text(stringResource(R.string.reader_bookmark_edit), maxLines = 1) }
                    TextButton(onClick = {
                        actionTarget = null
                        scope.launch {
                            ApiBridge.bookmarkDelete(state.handle, target.id)
                            reload()
                        }
                    }) { Text(stringResource(R.string.reader_bookmark_delete), maxLines = 1) }
                }
            },
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    // 修改备注
    editTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.reader_bookmark_edit)) },
            text = {
                OutlinedTextField(
                    value = editNote,
                    onValueChange = { editNote = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reader_bookmark_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = target.id
                    val text = editNote
                    editTarget = null
                    scope.launch {
                        ApiBridge.bookmarkUpdate(state.handle, targetId, text)
                        reload()
                    }
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 书签页里的一条：备注 / 页码 / 创建与更新时间（年月日时分秒）。 */
@Composable
private fun BookmarkRow(item: BookmarkItem, isCurrent: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.note.ifEmpty { stringResource(R.string.reader_bookmark_no_note) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.reader_bookmark_page) + ": " + (item.page + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.reader_bookmark_created) + ": " +
                formatTimestamp(item.created * 1000L),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.reader_bookmark_updated) + ": " +
                formatTimestamp(item.updated * 1000L),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 书签页的排序字段下拉（外观与资源管理器 / 任务页一致）。 */
@Composable
private fun BookmarkSortBox(current: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(bookmarkSortLabelRes(current)),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BOOKMARK_SORT_KEYS.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(bookmarkSortLabelRes(key))) },
                    trailingIcon = {
                        if (key == current) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onPick(key)
                    },
                )
            }
        }
    }
}

private fun bookmarkSortLabelRes(key: String): Int = when (key) {
    "note" -> R.string.reader_bookmark_sort_note
    "created" -> R.string.reader_bookmark_sort_created
    "updated" -> R.string.reader_bookmark_sort_updated
    else -> R.string.reader_bookmark_sort_page
}

/**
 * 带竖直滚动条的滚动容器（章节弹窗 / 书签页共用）。
 *
 * Compose 的滚动容器在安卓上不画滚动条，这里用一个细条自己画：
 * 滑块高度按「视口 / 内容」比例估，位置按当前滚动比例给。
 */
@Composable
private fun ScrollPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    Box(modifier = modifier.onSizeChanged { viewport = it.height }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
            content = content,
        )
        val max = scroll.maxValue
        if (max > 0 && viewport > 0) {
            val contentHeight = max + viewport
            val thumbHeight =
                (viewport.toFloat() * viewport / contentHeight).coerceAtLeast(24f).toInt()
            val thumbOffset = ((viewport - thumbHeight) * (scroll.value.toFloat() / max)).toInt()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffset) }
                    .padding(end = 2.dp)
                    .width(4.dp)
                    .height(thumbHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/** 书签排序用的中文比较器（按拼音顺序），只在首次使用时创建。 */
private val bookmarkCollator: Collator by lazy { Collator.getInstance(Locale.CHINA) }

/** 书签排序：「备注」按拼音顺序，其余按数值；[desc] 为真时反序。 */
private fun sortBookmarks(
    items: List<BookmarkItem>,
    key: String,
    desc: Boolean,
): List<BookmarkItem> {
    val comparator: Comparator<BookmarkItem> = when (key) {
        "note" -> Comparator { a, b -> bookmarkCollator.compare(a.note, b.note) }
        "created" -> compareBy { it.created }
        "updated" -> compareBy { it.updated }
        else -> compareBy { it.page }
    }
    return if (desc) items.sortedWith(comparator.reversed()) else items.sortedWith(comparator)
}

/** 秒级时间戳 → 「年-月-日 时:分:秒」。 */
private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))