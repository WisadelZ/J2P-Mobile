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
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * 软件更新引擎：更新源探测、版本比较、Release 查询、按 ABI 选包与下载。
 *
 * 与桌面版 `core/updater.py` 语义一一对应（安卓端用原生 [HttpURLConnection] 实现，
 * 不引入新依赖）。
 *
 * **下载链接来自更新清单**（OSS 上的 update.json，见 [fetchManifest]）：清单里给出
 * 「镜像加速前缀」与「网盘 / Release 跳转链接」，端上运行时拉取解析，因此增删节点、
 * 换网盘链接都**不需要重新发版**（结构与字段规则见《UPDATE-JSON.md》）。
 *
 * - **查询源**（带 `api`）：能查 Release，只有 GitHub 官方；
 * - **下载加速源**（带 `prefix`）：给 Release 文件加速，拼接方式是
 *   「前缀 + 原始 GitHub 地址」（``https://gh-proxy.com/https://github.com/...``）。
 *
 * 「有没有新版本」仍由 GitHub API 判定；清单只负责**怎么下载**。
 *
 * 检查更新时**并行**探测各源时延（[probeSources]）；拿到 Release 后由界面把候选下载地址
 * 连同时延一起列出来让用户自己选（[downloadOptions]）。下载时用户选中的源排在最前，
 * 它失败再依次换后面的源（[downloadFrom]），最后还有 GitHub 原址兜底。
 * 排序遵循「加速源优先」：加速源按时延升序排在前面，GitHub 原址固定排最后。
 * 探测接口只用于排序与展示，不代表查询一定失败 —— 走代理 / VPN 时首包慢，
 * 因此查询会「探测结果 → 官方 API 兜底」逐个真试。
 *
 * 所有方法都是阻塞式的，调用方需放到 IO 线程执行（[probeSources] 为挂起函数）。
 */
object Updater {

    /** 本项目的 GitHub 仓库（owner/repo）。 */
    const val REPO = "WisadelZ/J2P-Mobile"
    const val RELEASES_URL = "https://github.com/WisadelZ/J2P-Mobile/releases"

    /** 更新清单（OSS 上的 update.json）的固定地址；结构与字段规则见《UPDATE-JSON.md》。 */
    const val MANIFEST_URL = "http://getproxy.wisadelz.cn/update.json"

    /** 清单里本端的平台键（桌面端是 "windows"；两端清单文件是同一份）。 */
    private const val MANIFEST_PLATFORM = "android"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_BETA = "beta"
    val CHANNELS = listOf(CHANNEL_STABLE, CHANNEL_BETA)

    /**
     * 探测超时：定 8 秒而不是更短 —— 走代理 / VPN 时首个请求要算上代理握手与 DNS，
     * 3 秒会把「其实能用、只是首包慢」的源误判成不可用。
     */
    private const val PROBE_TIMEOUT_MS = 8000
    private const val REQUEST_TIMEOUT_MS = 15000
    private const val DOWNLOAD_TIMEOUT_MS = 30000
    private const val USER_AGENT = "J2P-Mobile-updater"

    /**
     * 更新源：
     * - [probe] 探测可用性与时延用的极小接口；
     * - [api] Release 接口根地址（只有官方有）；
     * - [prefix] 下载加速前缀（拼接：前缀 + 原始 GitHub 地址）。
     */
    data class Source(
        val name: String,
        val probe: String,
        val api: String = "",
        val prefix: String = "",
    )

    /** 官方查询源（也是下载的第一个候选地址）。 */
    val OFFICIAL_SOURCE = Source("GitHub", "https://api.github.com/", "https://api.github.com")

    /**
     * 内置兜底镜像：拉不到清单时用它，保证私有域名挂了也还能加速下载。
     * 正常情况以清单里的 mirrors 为准（增删节点改清单即可，无需发版）。
     */
    private val DEFAULT_MIRRORS = listOf(
        Mirror("gh-proxy.com", "https://gh-proxy.com/"),
        Mirror("ghfile.geekertao.top", "https://ghfile.geekertao.top/"),
        Mirror("ghproxy.homeboyc.cn", "https://ghproxy.homeboyc.cn/"),
        Mirror("ghproxy.net", "https://ghproxy.net/"),
    )

    /** 更新清单里的一条镜像加速源（[prefix] 拼接方式：前缀 + 原始 GitHub 地址）。 */
    data class Mirror(val name: String, val prefix: String)

    /** 更新清单里的一条网盘入口。 */
    data class Netdisk(val name: String, val url: String)

    /**
     * 本次检查拉到的更新清单：镜像节点 + 网盘入口 + Release 页。
     * [sources] 已转成可直接探测 / 拼接的 [Source] 列表（官方 GitHub 固定排在最后）。
     */
    data class Manifest(
        val sources: List<Source>,
        val netdisks: List<Netdisk>,
        val releasePage: String,
    )

    /** Release 里的一个资产。 */
    data class Asset(val name: String, val url: String, val size: Long)

    /** 一个候选下载地址（[latencyMs] 为探测到的时延，毫秒；未知时为 -1，不在界面上展示）。 */
    data class Candidate(val name: String, val url: String, val latencyMs: Long = -1)

    /** 一次 Release 查询的结果。 */
    data class Release(
        val version: String,
        val tag: String,
        val page: String,
        val prerelease: Boolean,
        val assets: List<Asset>,
    )

    /** 一个可用更新源及其探测时延。 */
    data class Probed(val source: Source, val latencyMs: Long)

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    /** 把 `v2.4.4` / `2.4.4-android.beta.2` 之类解析成可比较的数字列表。 */
    fun parseVersion(text: String?): List<Int> =
        Regex("\\d+").findAll(text.orEmpty()).map { it.value.toInt() }.take(4).toList()

    /** latest 是否比 current 新（逐段比较，缺位补 0）。 */
    fun isNewer(latest: String?, current: String?): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ------------------------------------------------------------------
    // 更新源探测
    // ------------------------------------------------------------------

    /**
     * 探测单个源，返回时延（毫秒）；不可用 / 超时返回 null。
     *
     * 加速源只要**连得上**就算可用（根路径未必返回 200，回 404 也说明服务是活的）；
     * 查询源则要求接口真的可用。
     */
    private fun probe(source: Source): Long? {
        val start = System.nanoTime()
        return try {
            val conn = open(source.probe, PROBE_TIMEOUT_MS)
            try {
                if (source.api.isNotEmpty() && conn.responseCode >= 400) return null
            } finally {
                conn.disconnect()
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * **并行**探测给定更新源，按时延升序返回可用源。
     *
     * 必须并行：任一源不通都要等满超时（8 秒），5 个源串行最坏能拖到 40 秒，
     * 界面就会长时间卡在「正在检查 / 正在测速」（用户实测抱怨过）。
     * 并行后总耗时约等于最慢的那一个。
     */
    suspend fun probeSources(sources: List<Source>): List<Probed> = coroutineScope {
        sources.map { source ->
            async(Dispatchers.IO) { probe(source)?.let { Probed(source, it) } }
        }.awaitAll().filterNotNull().sortedBy { it.latencyMs }
    }

    // ------------------------------------------------------------------
    // 更新清单（OSS 上的 update.json）
    // ------------------------------------------------------------------

    /**
     * 解析清单 JSON 成端上要用的部分：镜像节点（转成可探测 / 拼接的 [Source]）、
     * 网盘入口、Release 页；结构不合法时抛异常（由 [fetchManifest] 兜）。
     * 字段规则见《UPDATE-JSON.md》。
     */
    fun parseManifest(body: String): Manifest {
        val root = JSONObject(body)

        val mirrors = mutableListOf<Source>()
        val mirrorArray = root.optJSONArray("mirrors")
        if (mirrorArray != null) {
            for (i in 0 until mirrorArray.length()) {
                val item = mirrorArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val prefix = item.optString("prefix").trim()
                if (name.isEmpty() || prefix.isEmpty()) continue
                mirrors += Source(name, prefix, prefix = prefix)
            }
        }

        val section = root.optJSONObject("platforms")?.optJSONObject(MANIFEST_PLATFORM)
        val netdisks = mutableListOf<Netdisk>()
        val netdiskArray = section?.optJSONArray("netdisks")
        if (netdiskArray != null) {
            for (i in 0 until netdiskArray.length()) {
                val item = netdiskArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                netdisks += Netdisk(name, url)
            }
        }

        val page = section?.optString("release_page")?.trim().orEmpty()
        return Manifest(listOf(OFFICIAL_SOURCE) + mirrors, netdisks, page.ifEmpty { RELEASES_URL })
    }

    /** 拉取并解析 OSS 上的 update.json；任何失败都返回 null（调用方改用 [defaultManifest]）。 */
    fun fetchManifest(timeoutMs: Int = REQUEST_TIMEOUT_MS): Manifest? = try {
        get(MANIFEST_URL, timeoutMs)?.let { parseManifest(it) }
    } catch (_: Throwable) {
        null
    }

    /** 拉不到清单时的内置兜底：用内置镜像，网盘为空，Release 页用仓库内置地址。 */
    fun defaultManifest(): Manifest = Manifest(
        listOf(OFFICIAL_SOURCE) + DEFAULT_MIRRORS.map { Source(it.name, it.prefix, prefix = it.prefix) },
        emptyList(),
        RELEASES_URL,
    )

    /**
     * 可用于「查询 Release」的源，按探测时延排序；官方 API 永远兜底在最后。
     *
     * 探测失败不等于查询失败（代理 / VPN 首包慢、瞬时抖动都会误判），
     * 所以哪怕一个源都没探测通过，也把官方 API 带上真试一次。
     */
    fun apiCandidates(available: List<Probed>): List<Source> {
        val candidates = available.map { it.source }.filter { it.api.isNotEmpty() }.toMutableList()
        if (OFFICIAL_SOURCE !in candidates) candidates += OFFICIAL_SOURCE
        return candidates
    }

    /**
     * 列出可选的下载地址：**加速源在前，GitHub 原址最后兜底**。
     *
     * [available] 是本次检查已经探测好的结果（传进来就不必再探一遍）；界面只展示源站名
     * 与 [Candidate.latencyMs]（时延，未知为 -1），不展示拼接后的完整地址。
     */
    fun downloadOptions(
        assetUrl: String,
        available: List<Probed> = emptyList(),
        sources: List<Source> = defaultManifest().sources,
    ): List<Candidate> {
        val latencies = available.associate { it.source.name to it.latencyMs }
        val ordered = available.map { it.source }.filter { it.prefix.isNotEmpty() }.toMutableList()
        sources.forEach { if (it.prefix.isNotEmpty() && it !in ordered) ordered += it }
        val items = mutableListOf<Candidate>()
        ordered.forEach { source ->
            items += Candidate(source.name, source.prefix + assetUrl, latencies[source.name] ?: -1L)
        }
        items += Candidate(OFFICIAL_SOURCE.name, assetUrl,
            latencies[OFFICIAL_SOURCE.name] ?: -1L)
        return items
    }

    /** 把用户选中的源排到最前，其余按原顺序跟随（选中源失败时自动换下一个）。 */
    fun preferFirst(options: List<Candidate>, name: String): List<Candidate> =
        options.filter { it.name == name } + options.filter { it.name != name }

    // ------------------------------------------------------------------
    // Release 查询
    // ------------------------------------------------------------------

    /**
     * 查询指定通道的最新 Release；没有可用发布时返回 null。
     *
     * 稳定版取 GitHub 的 `latest`（不含预发布，全为预发布时返回 404）；
     * 公测版取发布列表里第一条非草稿（含预发布）。
     */
    fun fetchRelease(
        source: Source,
        channel: String,
        repo: String = REPO,
        timeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): Release? {
        if (channel == CHANNEL_BETA) {
            val body = get("${source.api}/repos/$repo/releases?per_page=20", timeoutMs) ?: return null
            val items = JSONArray(body)
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (!item.optBoolean("draft")) return toRelease(item)
            }
            return null
        }
        val body = get("${source.api}/repos/$repo/releases/latest", timeoutMs) ?: return null
        return toRelease(JSONObject(body))
    }

    private fun toRelease(item: JSONObject): Release {
        val tag = item.optString("tag_name").ifEmpty { item.optString("name") }
        val assets = mutableListOf<Asset>()
        val array = item.optJSONArray("assets")
        if (array != null) {
            for (i in 0 until array.length()) {
                val asset = array.optJSONObject(i) ?: continue
                assets += Asset(
                    name = asset.optString("name"),
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size"),
                )
            }
        }
        return Release(
            version = tag.trimStart('v', 'V'),
            tag = tag,
            page = item.optString("html_url").ifEmpty { RELEASES_URL },
            prerelease = item.optBoolean("prerelease"),
            assets = assets,
        )
    }

    // ------------------------------------------------------------------
    // 选包与下载
    // ------------------------------------------------------------------

    /** 本机首选 ABI（MuMu 之类的转译环境取到的可能不是真实机型架构，只作倾向使用）。 */
    fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * 按当前架构选安装包：
     * 1. 优先选文件名带本机 ABI 关键字的那一个（体积最小）；
     * 2. 找不到就退回**不带任何架构标识**的双架构通用包；
     * 3. 再找不到只能取第一个 apk（保持可用）。
     */
    fun pickAsset(release: Release, abi: String = deviceAbi()): Asset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val key = when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armeabi"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> abi
        }
        apks.firstOrNull { it.name.contains(key, ignoreCase = true) }?.let { return it }
        val markers = listOf("arm64", "armeabi", "x86_64", "x86", "v7a")
        return apks.firstOrNull { apk -> markers.none { apk.name.contains(it, ignoreCase = true) } }
            ?: apks.first()
    }

    /**
     * 下载文件；[onProgress] 回调 (已下载字节, 总字节)，总长未知时为 0。
     * [isCancelled] 返回 true 时抛 [CancellationException] 中断下载并清理临时文件。
     */
    fun download(
        url: String,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = open(url, DOWNLOAD_TIMEOUT_MS)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw CancellationException("update download stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest
    }

    /**
     * 按给定顺序逐个尝试候选地址下载，返回实际使用的源名。
     *
     * 前一个地址失败就换下一个；用户主动停止时立刻中断，不再往下试；
     * 全部失败时抛出最后一个异常，由界面提示。
     */
    fun downloadFrom(
        candidates: List<Candidate>,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String {
        var lastError: Throwable? = null
        for (item in candidates) {
            if (isCancelled()) throw CancellationException("update download stopped")
            try {
                download(item.url, dest, isCancelled, onProgress)
                return item.name
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("所有更新源都无法下载")
    }

    /**
     * 拉起系统安装器安装已下载的 APK。
     *
     * 需要 manifest 里的 `REQUEST_INSTALL_PACKAGES`；若用户尚未允许「安装未知应用」，
     * 系统会自行引导，这里不代为跳转设置页。
     */
    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, Platform.authority(), file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ------------------------------------------------------------------
    // HTTP 基础
    // ------------------------------------------------------------------

    /** 发起 GET 并返回响应体；非 2xx（含 404）返回 null。 */
    private fun get(url: String, timeoutMs: Int): String? {
        val conn = open(url, timeoutMs)
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return conn
    }
}
