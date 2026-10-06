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

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 探索结果里的一本本子（封面只带 URL，字节由 Kotlin 原生取）。 */
data class AlbumItem(
    val id: String,
    val name: String,
    val author: String,
    val coverUrl: String,
)

/** 探索页的一页结果。 */
data class ExplorePageData(
    val items: List<AlbumItem>,
    val total: Int,
    val uiPage: Int,
)

/** 下载页「搜索 ID」的结果（封面同样只带 URL）。 */
data class AlbumSearchData(
    val id: String,
    val name: String,
    val pages: Int,
    val chapters: Int,
    val tags: String,
    val url: String,
    val coverUrl: String,
)

/** 任务状态取值，与 `core/task_queue.py` 的常量一一对应。 */
object TaskStatus {
    const val WAITING = "waiting"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELED = "canceled"

    /** 筛选菜单里的「全部」，与 `task_queue.FILTER_ALL` 一致。 */
    const val FILTER_ALL = "all"

    /** 筛选菜单顺序；界面筛选与状态名都用它。 */
    val filterable = listOf(WAITING, RUNNING, PAUSED, DONE, FAILED, CANCELED)

    val sortKeys = listOf("added", "id", "name", "progress")

    /** 各排序字段的默认方向（进度默认降序），与 `task_queue.SORT_DESC_DEFAULT` 一致。 */
    val sortDescDefault = mapOf(
        "added" to false,
        "id" to false,
        "name" to false,
        "progress" to true,
    )
}

/** 一条下载任务（进度 / 速度 / 剩余时间已由 Python 侧算好）。 */
data class TaskItem(
    val id: String,
    val seq: Int,
    val albumId: String,
    val name: String,
    val status: String,
    val pagesDone: Int,
    val pagesTotal: Int,
    val progress: Float?,
    val byteRate: Double,
    val eta: Double?,
    val error: String,
    val pdfCount: Int,
    val inflight: Boolean,
    val outputDir: String,
)

/** 各状态计数（含总数）。 */
data class QueueStats(
    val waiting: Int,
    val running: Int,
    val paused: Int,
    val done: Int,
    val failed: Int,
    val canceled: Int,
    val total: Int,
)

/** 任务页一次快照：过滤排序后的任务 + 计数 + 队列级暂停态。 */
data class QueueSnapshot(
    val tasks: List<TaskItem>,
    val stats: QueueStats,
    val paused: Boolean,
    val shown: Int,
    val total: Int,
)

/** 入队结果。 */
data class EnqueueResult(
    val added: List<String>,
    val duplicated: List<String>,
)

/** 队列控制结果：`applied` 为单条操作是否生效，`removed` 为清空已完成移除的条数。 */
data class QueueControlResult(
    val applied: Boolean,
    val removed: Int,
)

/** 本子详情（封面只带 URL，字节由 Kotlin 原生取）。 */
data class AlbumDetail(
    val id: String,
    val title: String,
    val author: String,
    val tags: String,
    val likes: String,
    val views: String,
    val pages: Int,
    val chapters: Int,
    val favorited: Boolean,
    val coverUrl: String,
    val url: String,
)

/** 一页预览图的尺寸（字节按需另取）。 */
data class PreviewPageMeta(val width: Int, val height: Int)

/**
 * 一页预览图（已解码为 JPEG 字节 + 原始像素尺寸）。
 *
 * 用普通类而非 data class：`ByteArray` 的 equals 是引用比较，data class 会带来误导性的语义。
 */
class PreviewImage(val data: ByteArray, val width: Int, val height: Int)

/** 收藏夹（id 为 "0" 表示默认收藏夹，由界面自己补名字）。 */
data class FavoriteFolder(val id: String, val name: String)

/** 账号资料（状态字段在登录响应里就有，登录时一并加密保存）。 */
data class AccountProfile(
    val username: String,
    val nickname: String,
    val uid: String,
    val avatarUrl: String,
    val level: Int?,
    val levelName: String,
    val exp: Int?,
    val nextLevelExp: Int?,
    val expPercent: Double?,
    val favorites: Int?,
    val favoritesMax: Int?,
    val coin: Int?,
)

/** 账号页初始状态。 */
data class AccountStateData(
    val loggedIn: Boolean,
    val profile: AccountProfile?,
    val accounts: List<String>,
)

/** 清除某个已保存账号的结果：`wasCurrent` 为真时界面要一并退出登录。 */
data class AccountRemoveResult(val removed: Boolean, val wasCurrent: Boolean)

/** 签到结果（`code` 0 = 成功、1 = 今天已签到）。 */
data class CheckinResult(
    val code: Int,
    val coin: Int?,
    val exp: Int?,
    val msg: String,
    val monthDays: Int,
    val streak: Int,
    val event: String,
)

/** 收藏列表页一页：条目 + 全部收藏夹 + 总数（收藏夹随库页一起返回）。 */
data class FavoritePageData(
    val items: List<AlbumItem>,
    val folders: List<FavoriteFolder>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

/** 资源管理器的一条扫描结果：同一本漫画的 PDF 与图片文件夹归为一项。 */
data class LibraryEntry(
    val name: String,
    val pdf: String,
    val folder: String,
    val size: Long,
)

/** 资源管理器一次列表结果。 */
data class LibraryListData(
    val dir: String,
    val entries: List<LibraryEntry>,
    val total: Int,
    val shown: Int,
    val needsMetadata: Boolean,
)

/** 导入外部 PDF 的结果：落盘文件名、绝对路径与刚分配的本子 ID。 */
data class ImportedPdf(
    val name: String,
    val path: String,
    val albumId: String,
)

/**
 * 一个浏览会话：Python 侧登记的句柄 + 名称 + 页数。
 *
 * `numberedPages` 为真时界面上显示「当前页/总页数」（PDF 与在线本子），
 * 否则显示当前图片文件名（图片文件夹）。`width`/`height` 是首页像素尺寸，
 * 供界面在图片取回来之前先排出宽高比（竖排连续翻页用）。
 */
data class ReaderSource(
    val handle: String,
    val name: String,
    val albumId: String,
    /** 是否在线本子（本地 PDF / 图片文件夹为 false）：决定阅读页是否显示「切换加载源」。 */
    val online: Boolean,
    val pageCount: Int,
    val numberedPages: Boolean,
    val chapters: Int,
    val width: Int,
    val height: Int,
)

/** 一章（在线本子的章节挑选用）：标题 + 起始全局页号（0 起）+ 页数。 */
data class ChapterItem(val title: String, val start: Int, val pages: Int)

/** 一条书签：页码（0 起）、备注、创建与更新时间（秒级时间戳）。 */
data class BookmarkItem(
    val id: String,
    val page: Int,
    val note: String,
    val created: Long,
    val updated: Long,
)

/**
 * 阅读器的一页：已解码为 JPEG / PNG 字节 + 原始像素尺寸（图片文件夹另带文件名）。
 *
 * 用普通类而非 data class：`ByteArray` 的 equals 是引用比较。
 */
class ReaderImage(val data: ByteArray, val width: Int, val height: Int, val name: String)

/** 一个加载源节点：序号只是本次测速的排序结果（不与具体域名绑定）+ 延迟毫秒。 */
data class SourceNode(val index: Int, val delay: Int)

/** 加载源快照：可用节点（按延迟升序）+ 当前节点序号（0 表示还没测速过）。 */
data class SourceState(val nodes: List<SourceNode>, val current: Int)

/** PDF 里的漫画元数据（资源管理器侧栏）。字段缺失时为空串。 */
data class PdfMeta(
    val title: String,
    val albumId: String,
    val author: String,
    val tags: String,
    val pages: String,
    val chapter: String,
)

/** Python 调用的结果封装：失败时只带一条可读错误，不让 JSON / 异常穿到 UI。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val message: String) : ApiResult<Nothing>
}

/**
 * UI ↔ Python 的数据入口（第 4 步）。
 *
 * 与 [PythonBridge] 的分工：`PythonBridge` 只管「怎么调」，这里管「调完怎么用」——
 * 统一切到 IO 线程、统一解析出数据类。UI 层因此完全不碰 JSONObject。
 */
object ApiBridge {

    /** 读配置；失败时返回空对象（调用方按默认值处理）。 */
    suspend fun loadConfig(): JSONObject = withContext(Dispatchers.IO) {
        PythonBridge.call("load_config")
    }

    /** 把局部配置深度合并进 conf.yml。 */
    suspend fun updateConfig(patch: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        PythonBridge.call("update_config", patch.toString())
    }

    /**
     * 把当前配置导出成 YAML 文本。
     *
     * 安卓端拿到的是 SAF 的 `content://` URI（Python 打不开），所以文件读写在 Kotlin 侧做，
     * Python 只负责产出 / 消费文本。
     */
    suspend fun exportConfigText(): ApiResult<String> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("export_config_text")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.optString("text"))
    }

    /** 从 YAML 文本导入配置（覆盖 conf.yml；不动登录态与下载队列）。 */
    suspend fun importConfigText(text: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("import_config_text", text)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    /** 清掉 Python 侧运行期内存缓存（检索页缓存 + 详情预览图字节）。 */
    suspend fun clearCache(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("clear_cache")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    /** 启动时的自动签到（设置了 auto_login 且已登录才做）；回是否真的签到成功。 */
    suspend fun autoCheckin(): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("auto_checkin")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.optBoolean("signed"))
    }

    /**
     * 探索页检索：按「库页 80 条 / 界面页 20 条」取一页。
     *
     * @param mode   搜索方式 all / work / author / tag / actor
     * @param sort   排序 latest / views / pictures / likes
     * @param time   时间筛选 today / week / month / all
     */
    suspend fun exploreSearch(
        mode: String,
        keyword: String,
        uiPage: Int,
        sort: String,
        time: String,
    ): ApiResult<ExplorePageData> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("explore_search", mode, keyword, uiPage, sort, time)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val array: JSONArray? = result.optJSONArray("items")
        ApiResult.Ok(
            ExplorePageData(
                items = array.toAlbumItems(),
                total = result.optInt("total"),
                uiPage = result.optInt("ui_page", uiPage),
            )
        )
    }

    /** 下载页「搜索 ID」：按 ID 取本子详情（名称 / 页数 / 章节数 / 标签 / 链接 / 封面 URL）。 */
    suspend fun albumSearch(albumId: String): ApiResult<AlbumSearchData> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("album_search", albumId)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            AlbumSearchData(
                id = result.optString("id"),
                name = result.optString("name"),
                pages = result.optInt("pages"),
                chapters = result.optInt("chapters"),
                tags = result.optString("tags"),
                url = result.optString("url"),
                coverUrl = result.optString("cover_url"),
            )
        )
    }

    /** 本子详情（详情页）：标题 / 作者 / 标签 / 点赞 / 观看 / 页数 / 章节数 / 收藏状态 / 链接。 */
    suspend fun albumDetail(albumId: String): ApiResult<AlbumDetail> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("album_detail", albumId)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            AlbumDetail(
                id = result.optString("id"),
                title = result.optString("title"),
                author = result.optString("author"),
                tags = result.optString("tags"),
                likes = result.optString("likes"),
                views = result.optString("views"),
                pages = result.optInt("pages"),
                chapters = result.optInt("chapters"),
                favorited = result.optBoolean("favorited"),
                coverUrl = result.optString("cover_url"),
                url = result.optString("url"),
            )
        )
    }

    /**
     * 取本子第一话的前几页预览：先拿每页尺寸（字节已缓存在 Python 内存里），
     * 再用 [albumPreviewPage] 逐页取字节，避免一次调用传几 MB。
     */
    suspend fun albumPreview(albumId: String): ApiResult<List<PreviewPageMeta>> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("album_preview", albumId)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            val array: JSONArray? = result.optJSONArray("pages")
            val pages = ArrayList<PreviewPageMeta>(array?.length() ?: 0)
            if (array != null) {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    pages.add(
                        PreviewPageMeta(
                            width = row.optInt("width"),
                            height = row.optInt("height"),
                        )
                    )
                }
            }
            ApiResult.Ok(pages)
        }

    /** 取第 index 页预览图的字节（Python 侧回传 base64，这里解成 JPEG 字节）。 */
    suspend fun albumPreviewPage(albumId: String, index: Int): ApiResult<PreviewImage> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("album_preview_page", albumId, index)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            val data = try {
                Base64.decode(result.optString("data"), Base64.DEFAULT)
            } catch (_: Throwable) {
                return@withContext ApiResult.Err("预览图片解码失败")
            }
            ApiResult.Ok(
                PreviewImage(
                    data = data,
                    width = result.optInt("width"),
                    height = result.optInt("height"),
                )
            )
        }

    /** 当前登录账号的收藏夹列表（不含「全部」，那一项由界面补）。 */
    suspend fun favoriteFolders(): ApiResult<List<FavoriteFolder>> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("favorite_folders")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val array: JSONArray? = result.optJSONArray("folders")
        val folders = ArrayList<FavoriteFolder>(array?.length() ?: 0)
        if (array != null) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                folders.add(FavoriteFolder(row.optString("id"), row.optString("name")))
            }
        }
        ApiResult.Ok(folders)
    }

    /** 把本子加入收藏夹；`folderId` 传 "0" 表示默认收藏夹。 */
    suspend fun favoriteAdd(albumId: String, folderId: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("favorite_add", albumId, folderId)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(Unit)
        }

    /** 取消收藏本子。 */
    suspend fun favoriteRemove(albumId: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("favorite_remove", albumId)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    /**
     * 任务页快照：按关键词 / 状态过滤并排序后的任务列表 + 计数 + 队列暂停态。
     *
     * 过滤与排序都在 Python 侧做（复用 `core.task_queue` 的纯函数），避免两端各写一套。
     */
    suspend fun queueSnapshot(
        keyword: String,
        status: String,
        sortKey: String,
        desc: Boolean,
    ): ApiResult<QueueSnapshot> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("queue_snapshot", keyword, status, sortKey, desc)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val array: JSONArray? = result.optJSONArray("tasks")
        val tasks = ArrayList<TaskItem>(array?.length() ?: 0)
        if (array != null) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                tasks.add(
                    TaskItem(
                        id = row.optString("id"),
                        seq = row.optInt("seq"),
                        albumId = row.optString("album_id"),
                        name = row.optString("name"),
                        status = row.optString("status", TaskStatus.WAITING),
                        pagesDone = row.optInt("pages_done"),
                        pagesTotal = row.optInt("pages_total"),
                        progress = if (row.isNull("progress")) null else row.optDouble("progress").toFloat(),
                        byteRate = row.optDouble("byte_rate", 0.0),
                        eta = if (row.isNull("eta")) null else row.optDouble("eta"),
                        error = row.optString("error"),
                        pdfCount = row.optInt("pdf_count"),
                        inflight = row.optBoolean("inflight"),
                        outputDir = row.optString("output_dir"),
                    )
                )
            }
        }
        val stats = result.optJSONObject("stats")
        ApiResult.Ok(
            QueueSnapshot(
                tasks = tasks,
                stats = QueueStats(
                    waiting = stats?.optInt("waiting") ?: 0,
                    running = stats?.optInt("running") ?: 0,
                    paused = stats?.optInt("paused") ?: 0,
                    done = stats?.optInt("done") ?: 0,
                    failed = stats?.optInt("failed") ?: 0,
                    canceled = stats?.optInt("canceled") ?: 0,
                    total = stats?.optInt("total") ?: 0,
                ),
                paused = result.optBoolean("paused"),
                shown = result.optInt("shown"),
                total = result.optInt("total"),
            )
        )
    }

    /** 把一批本子 ID 加入下载队列。 */
    suspend fun queueEnqueue(ids: List<String>): ApiResult<EnqueueResult> = withContext(Dispatchers.IO) {
        val payload = JSONArray().apply { ids.forEach { put(it) } }
        val result = PythonBridge.call("queue_enqueue", payload.toString())
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            EnqueueResult(
                added = result.optJSONArray("added").toStringList(),
                duplicated = result.optJSONArray("duplicated").toStringList(),
            )
        )
    }

    /**
     * 队列操作。
     *
     * @param action pause / resume / retry / cancel / remove / pause_all / resume_all / clear_finished
     * @param taskId 单条任务操作时的任务 id（`t-0001`），队列级操作留空
     */
    suspend fun queueControl(action: String, taskId: String = ""): ApiResult<QueueControlResult> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("queue_control", action, taskId)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(
                QueueControlResult(
                    applied = result.optBoolean("applied"),
                    removed = result.optInt("removed"),
                )
            )
        }

    /** 用系统默认方式打开文件 / 文件夹。 */
    suspend fun openPath(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("open_path", path)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    // ---------------------------------------------------------------- 资源管理器

    /**
     * 扫描下载目录并按「搜索方式 + 关键词 + 排序」返回条目。
     *
     * 需要 PDF 元数据时（按作者 / 标签 / 本子 ID / 全部搜索，或按页数排序）Python 会在
     * 同一次调用里读完，所以这里可能稍慢，界面应显示「正在读取元数据…」。
     */
    suspend fun libraryList(
        keyword: String,
        mode: String,
        sortKey: String,
        desc: Boolean,
    ): ApiResult<LibraryListData> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("library_list", keyword, mode, sortKey, desc)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val entries = ArrayList<LibraryEntry>()
        val array = result.optJSONArray("entries")
        if (array != null) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                entries.add(
                    LibraryEntry(
                        name = row.optString("name"),
                        pdf = row.optString("pdf"),
                        folder = row.optString("folder"),
                        size = row.optLong("size"),
                    )
                )
            }
        }
        ApiResult.Ok(
            LibraryListData(
                dir = result.optString("dir"),
                entries = entries,
                total = result.optInt("total"),
                shown = result.optInt("shown"),
                needsMetadata = result.optBoolean("needs_metadata"),
            )
        )
    }

    /** 读一个 PDF 的漫画元数据；文件没有元数据时回 `Ok(null)`。 */
    suspend fun libraryPdfMeta(pdfPath: String): ApiResult<PdfMeta?> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("library_pdf_meta", pdfPath)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        if (result.isNull("meta")) return@withContext ApiResult.Ok(null)
        val meta = result.optJSONObject("meta")
            ?: return@withContext ApiResult.Ok(null)
        ApiResult.Ok(
            PdfMeta(
                title = meta.optString("title"),
                albumId = meta.optString("album_id"),
                author = meta.optString("author"),
                tags = meta.optString("tags"),
                pages = meta.optString("pages"),
                chapter = meta.optString("chapter"),
            )
        )
    }

    /** 把选中的文件 / 文件夹移入回收站；返回实际处理的数量。 */
    suspend fun libraryDelete(paths: List<String>): ApiResult<Int> = withContext(Dispatchers.IO) {
        val payload = JSONArray().apply { paths.forEach { put(it) } }
        val result = PythonBridge.call("library_delete", payload.toString())
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.optInt("count"))
    }

    /**
     * 导入外部 PDF：把 [tempPath]（Kotlin 侧已写好的临时文件）复制进下载目录，
     * 分配一个新的八位本子 ID 并写进 PDF 元数据。
     */
    suspend fun importPdf(tempPath: String, displayName: String): ApiResult<ImportedPdf> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("import_pdf", tempPath, displayName)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(
                ImportedPdf(
                    name = result.optString("name"),
                    path = result.optString("path"),
                    albumId = result.optString("album_id"),
                )
            )
        }

    // ---------------------------------------------------------------- 阅读器

    /**
     * 打开本地 PDF / 图片文件夹的浏览会话。
     *
     * 回 `Ok(null)` 表示「文件夹里没有图片」（界面提示用），其余失败走 `Err`。
     */
    suspend fun readerOpenLocal(path: String): ApiResult<ReaderSource?> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("reader_open_local", path)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            if (result.optBoolean("empty_folder")) return@withContext ApiResult.Ok(null)
            ApiResult.Ok(result.toReaderSource())
        }

    /** 打开在线本子的浏览会话（详情要联网取，可能稍慢）。 */
    suspend fun readerOpenOnline(albumId: String): ApiResult<ReaderSource> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("reader_open_online", albumId)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(result.toReaderSource())
        }

    /** 取第 index 页（0 起）的图片字节与尺寸。 */
    suspend fun readerPage(handle: String, index: Int): ApiResult<ReaderImage> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("reader_page", handle, index)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            val data = try {
                Base64.decode(result.optString("data"), Base64.DEFAULT)
            } catch (_: Throwable) {
                return@withContext ApiResult.Err("页面图片解码失败")
            }
            ApiResult.Ok(
                ReaderImage(
                    data = data,
                    width = result.optInt("width"),
                    height = result.optInt("height"),
                    name = result.optString("name"),
                )
            )
        }

    /** 结束浏览会话（离开阅读器时调用，释放 Python 侧的缓存与句柄）。 */
    suspend fun readerClose(handle: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("reader_close", handle)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    // ---- 加载源（禁漫 API 域名节点）----

    /** 当前加载源快照：只读，不联网。 */
    suspend fun sourceState(): ApiResult<SourceState> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("source_state")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.toSourceState())
    }

    /** 测一次所有候选源并按延迟排序（单源最多 3 秒），延迟最低的成为当前节点。 */
    suspend fun sourceRefresh(): ApiResult<SourceState> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("source_refresh")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.toSourceState())
    }

    /** 按节点序号切换加载源（只影响之后的新请求）。 */
    suspend fun sourceSelect(index: Int): ApiResult<SourceState> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("source_select", index)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.toSourceState())
    }

    /** 阅读器标题点击后的资料弹窗（在线本子 / 本地 PDF / 图片文件夹统一成同一组字段）。 */
    suspend fun readerMeta(handle: String): ApiResult<PdfMeta?> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("reader_meta", handle)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val meta = result.optJSONObject("meta")
            ?: return@withContext ApiResult.Ok(null)
        ApiResult.Ok(
            PdfMeta(
                title = meta.optString("title"),
                albumId = meta.optString("album_id"),
                author = meta.optString("author"),
                tags = meta.optString("tags"),
                pages = meta.optString("pages"),
                chapter = meta.optString("chapter"),
            )
        )
    }

    /** 章节列表（在线多章本子；本地单章文件回空列表）。首次调用要联网逐章取页数。 */
    suspend fun readerChapters(handle: String): ApiResult<List<ChapterItem>> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("reader_chapters", handle)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            val array = result.optJSONArray("chapters")
            val items = ArrayList<ChapterItem>(array?.length() ?: 0)
            if (array != null) {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    items.add(
                        ChapterItem(
                            title = row.optString("title"),
                            start = row.optInt("start"),
                            pages = row.optInt("pages"),
                        )
                    )
                }
            }
            ApiResult.Ok(items)
        }

    // ---------------------------------------------------------------- 书签

    /** 读取当前本子的全部书签（每次打开书签页都以磁盘文件为准）。 */
    suspend fun bookmarkLoad(handle: String): ApiResult<List<BookmarkItem>> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("bookmark_load", handle)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(result.optJSONArray("items").toBookmarks())
        }

    /** 新增一条书签（page 为 0 起的页号）。 */
    suspend fun bookmarkAdd(handle: String, page: Int, note: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("bookmark_add", handle, page.toString(), note)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(Unit)
        }

    /** 修改一条书签的备注（更新时间一并刷新）。 */
    suspend fun bookmarkUpdate(handle: String, id: String, note: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("bookmark_update", handle, id, note)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(Unit)
        }

    /** 删除一条书签。 */
    suspend fun bookmarkDelete(handle: String, id: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("bookmark_delete", handle, id)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(Unit)
        }

    // ---------------------------------------------------------------- 账号

    /** 账号页初始状态：是否已登录 + 账号资料 + 已保存账号的用户名列表。 */
    suspend fun accountState(): ApiResult<AccountStateData> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_state")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            AccountStateData(
                loggedIn = result.optBoolean("logged_in"),
                profile = result.optJSONObject("profile")?.toAccountProfile(),
                accounts = result.optJSONArray("accounts").toStringList(),
            )
        )
    }

    /** 登录并加密保存账号（用全新会话，避免与上一个账号串号）。 */
    suspend fun accountLogin(username: String, password: String): ApiResult<AccountProfile> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("account_login", username, password)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(
                result.optJSONObject("profile")?.toAccountProfile() ?: AccountProfile(
                    username = username, nickname = username, uid = "", avatarUrl = "",
                    level = null, levelName = "", exp = null, nextLevelExp = null,
                    expPercent = null, favorites = null, favoritesMax = null, coin = null,
                )
            )
        }

    /** 用已保存的凭据登录指定账号（切换账号）。 */
    suspend fun accountSwitch(username: String): ApiResult<AccountProfile> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_switch", username)
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            result.optJSONObject("profile")?.toAccountProfile() ?: AccountProfile(
                username = username, nickname = username, uid = "", avatarUrl = "",
                level = null, levelName = "", exp = null, nextLevelExp = null,
                expPercent = null, favorites = null, favoritesMax = null, coin = null,
            )
        )
    }

    /** 清除指定的已保存账号。 */
    suspend fun accountRemove(username: String): ApiResult<AccountRemoveResult> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("account_remove", username)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            ApiResult.Ok(
                AccountRemoveResult(
                    removed = result.optBoolean("removed"),
                    wasCurrent = result.optBoolean("was_current"),
                )
            )
        }

    /** 退出当前账号（其它已保存账号保留）。 */
    suspend fun accountLogout(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_logout")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    /** 清除全部登录。 */
    suspend fun accountClearAll(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_clear_all")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(Unit)
    }

    /** 重新登录一次以刷新账号状态（等级 / 经验 / 收藏数 / J 币）。 */
    suspend fun accountRefreshProfile(): ApiResult<AccountProfile> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_refresh_profile")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(
            result.optJSONObject("profile")?.toAccountProfile() ?: AccountProfile(
                username = "", nickname = "", uid = "", avatarUrl = "",
                level = null, levelName = "", exp = null, nextLevelExp = null,
                expPercent = null, favorites = null, favoritesMax = null, coin = null,
            )
        )
    }

    /** 执行每日签到。 */
    suspend fun accountCheckin(): ApiResult<CheckinResult> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("account_checkin")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        val data = result.optJSONObject("result")
        ApiResult.Ok(
            CheckinResult(
                code = data?.optInt("code") ?: 0,
                coin = data?.optIntOrNull("coin"),
                exp = data?.optIntOrNull("exp"),
                msg = data?.optString("msg") ?: "",
                monthDays = data?.optInt("month_days") ?: 0,
                streak = data?.optInt("streak") ?: 0,
                event = data?.optString("event") ?: "",
            )
        )
    }

    // ---------------------------------------------------------------- 收藏

    /** 账号页收藏预览：「全部」收藏夹最前面的若干本。 */
    suspend fun favoritePreview(): ApiResult<List<AlbumItem>> = withContext(Dispatchers.IO) {
        val result = PythonBridge.call("favorite_preview")
        if (!result.optBoolean("ok")) {
            return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
        }
        ApiResult.Ok(result.optJSONArray("items").toAlbumItems())
    }

    /** 收藏列表页一页（含该账号的全部收藏夹与总数）。 */
    suspend fun favoritePage(page: Int, folderId: String): ApiResult<FavoritePageData> =
        withContext(Dispatchers.IO) {
            val result = PythonBridge.call("favorite_page", page, folderId)
            if (!result.optBoolean("ok")) {
                return@withContext ApiResult.Err(result.optString("error").ifEmpty { "unknown error" })
            }
            val folders = ArrayList<FavoriteFolder>()
            val folderArray = result.optJSONArray("folders")
            if (folderArray != null) {
                for (index in 0 until folderArray.length()) {
                    val row = folderArray.optJSONObject(index) ?: continue
                    folders.add(FavoriteFolder(row.optString("id"), row.optString("name")))
                }
            }
            ApiResult.Ok(
                FavoritePageData(
                    items = result.optJSONArray("items").toAlbumItems(),
                    folders = folders,
                    total = result.optInt("total"),
                    page = result.optInt("page", page),
                    pageSize = result.optInt("page_size", 20),
                )
            )
        }
}

private fun JSONObject.toReaderSource(): ReaderSource = ReaderSource(
    handle = optString("handle"),
    name = optString("name"),
    albumId = optString("album_id"),
    online = optBoolean("online", false),
    pageCount = optInt("page_count"),
    numberedPages = optBoolean("numbered_pages", true),
    chapters = optInt("chapters", 1),
    width = optInt("width"),
    height = optInt("height"),
)

private fun JSONObject.toSourceState(): SourceState {
    val array = optJSONArray("nodes")
    val nodes = ArrayList<SourceNode>(array?.length() ?: 0)
    for (i in 0 until (array?.length() ?: 0)) {
        val item = array?.optJSONObject(i) ?: continue
        nodes.add(SourceNode(index = item.optInt("index"), delay = item.optInt("delay")))
    }
    return SourceState(nodes = nodes, current = optInt("current"))
}

private fun JSONObject.toAccountProfile(): AccountProfile = AccountProfile(
    username = optString("username"),
    nickname = optString("nickname"),
    uid = optString("uid"),
    avatarUrl = optString("avatar_url"),
    level = optIntOrNull("level"),
    levelName = optString("level_name"),
    exp = optIntOrNull("exp"),
    nextLevelExp = optIntOrNull("next_level_exp"),
    expPercent = optDoubleOrNull("exp_percent"),
    favorites = optIntOrNull("favorites"),
    favoritesMax = optIntOrNull("favorites_max"),
    coin = optIntOrNull("coin"),
)

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key)

private fun JSONArray?.toAlbumItems(): List<AlbumItem> {
    if (this == null) return emptyList()
    val out = ArrayList<AlbumItem>(length())
    for (index in 0 until length()) {
        val row = optJSONObject(index) ?: continue
        out.add(
            AlbumItem(
                id = row.optString("id"),
                name = row.optString("name"),
                author = row.optString("author"),
                coverUrl = row.optString("cover_url"),
            )
        )
    }
    return out
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (index in 0 until length()) out.add(optString(index))
    return out
}

private fun JSONArray?.toBookmarks(): List<BookmarkItem> {
    if (this == null) return emptyList()
    val out = ArrayList<BookmarkItem>(length())
    for (index in 0 until length()) {
        val row = optJSONObject(index) ?: continue
        out.add(
            BookmarkItem(
                id = row.optString("id"),
                page = row.optInt("page"),
                note = row.optString("note"),
                created = row.optLong("created"),
                updated = row.optLong("updated"),
            )
        )
    }
    return out
}