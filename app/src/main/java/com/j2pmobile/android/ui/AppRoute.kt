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

import androidx.annotation.StringRes
import com.j2pmobile.android.R

/**
 * 页面路由：与桌面版 `core.constants` 里的 `ROUTE_*` 一一对应。
 *
 * 页面跳转逻辑保持不变（压栈 / 返回上一级），但「返回主页」按钮已移除 ——
 * 底部导航的四个入口本身就是主页快捷跳转。
 */
enum class AppRoute(@StringRes val titleRes: Int) {
    Explore(R.string.nav_explore),
    Download(R.string.nav_download),
    Explorer(R.string.nav_explorer),
    Account(R.string.nav_account),
    Album(R.string.album_title),
    Tasks(R.string.btn_tasks),
    Favorite(R.string.favorite_title),
    Settings(R.string.settings_title),
    Reader(R.string.btn_browse),
    Help(R.string.help_title);

    companion object {
        /** 底部导航的四个入口：顺序即底部按钮顺序（探索 / 下载 / 资源管理器 / 账号）。 */
        val tabs = listOf(Explore, Download, Explorer, Account)
    }
}