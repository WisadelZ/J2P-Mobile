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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 下载前台服务 + 进度通知（第 2 步 2.4）。
 *
 * 桌面版没有对应实现，这是安卓为了「长时下载不被系统回收」而必需的新增能力：
 * 应用退到后台后，靠常驻通知把进程保活，并把进度展示给用户。
 *
 * 调用方式（Kotlin 与 Python 都走这三个静态方法）：
 * - [start] 进入前台并显示通知；
 * - [update] 刷新进度文本；
 * - [stop] 结束服务并移除通知。
 *
 * 依赖 manifest 中的 `foregroundServiceType="dataSync"` 与相应权限。
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "jm2pdf_download"
        const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.j2pmobile.android.action.DOWNLOAD_START"
        private const val ACTION_UPDATE = "com.j2pmobile.android.action.DOWNLOAD_UPDATE"
        private const val ACTION_STOP = "com.j2pmobile.android.action.DOWNLOAD_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_MAX = "max"

        /** 服务是否在前台运行（供 UI / 自检查询）。 */
        @Volatile
        var running: Boolean = false
            private set

        @JvmStatic
        fun start(title: String, text: String, max: Int) {
            val intent = Intent(Platform.ctx(), DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_MAX, max)
            }
            ContextCompat.startForegroundService(Platform.ctx(), intent)
        }

        @JvmStatic
        fun update(text: String, progress: Int, max: Int) {
            val intent = Intent(Platform.ctx(), DownloadService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            Platform.ctx().startService(intent)
        }

        @JvmStatic
        fun stop() {
            val intent = Intent(Platform.ctx(), DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            Platform.ctx().startService(intent)
        }
    }

    private var title: String = "J2P Mobile"
    private var text: String = ""
    private var max: Int = 100
    private var progress: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: title
                text = intent.getStringExtra(EXTRA_TEXT) ?: text
                max = intent.getIntExtra(EXTRA_MAX, 100)
                progress = 0
                startForeground(NOTIFICATION_ID, buildNotification())
                running = true
            }

            ACTION_UPDATE -> {
                text = intent.getStringExtra(EXTRA_TEXT) ?: text
                progress = intent.getIntExtra(EXTRA_PROGRESS, progress)
                max = intent.getIntExtra(EXTRA_MAX, max)
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
            }

            ACTION_STOP -> {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // 正式版应换成白色剪影通知图标（见《安卓迁移方案》第十节）；此处先用系统图标兜底
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)
}