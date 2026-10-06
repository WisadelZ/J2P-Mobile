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

/**
 * 更新下载的前台服务 + 进度通知。
 *
 * 与 [DownloadService] 同构，但用**独立的渠道与通知 id** —— 更新下载可能和普通本子下载
 * 同时在跑，共用一个 id 会互相覆盖进度。
 *
 * 用法（只在 Kotlin 侧调用）：
 * - [start] 进入前台并显示「正在下载更新」通知；
 * - [update] 刷新进度（按 1% 或 0.5 秒节流，由调用方控制）；
 * - [stop] 结束服务并移除通知。
 */
class UpdateService : Service() {

    companion object {
        const val CHANNEL_ID = "jm2pdf_update"
        const val NOTIFICATION_ID = 2002

        private const val ACTION_START = "com.j2pmobile.android.action.UPDATE_START"
        private const val ACTION_UPDATE = "com.j2pmobile.android.action.UPDATE_UPDATE"
        private const val ACTION_STOP = "com.j2pmobile.android.action.UPDATE_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_MAX = "max"

        /** 服务是否在前台运行。 */
        @Volatile
        var running: Boolean = false
            private set

        @JvmStatic
        fun start(title: String, text: String, max: Int) {
            val intent = Intent(Platform.ctx(), UpdateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_MAX, max)
            }
            androidx.core.content.ContextCompat.startForegroundService(Platform.ctx(), intent)
        }

        @JvmStatic
        fun update(text: String, progress: Int, max: Int) {
            val intent = Intent(Platform.ctx(), UpdateService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            Platform.ctx().startService(intent)
        }

        @JvmStatic
        fun stop() {
            val intent = Intent(Platform.ctx(), UpdateService::class.java).apply {
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
            getString(R.string.notification_update_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_update_channel_desc) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
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
