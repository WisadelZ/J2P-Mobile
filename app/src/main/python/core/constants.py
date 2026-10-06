# -*- coding: utf-8 -*-
# Copyright (C) 2026 WisadelZ
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""全局常量：应用元信息、状态颜色、路由。

安卓端说明：桌面版的窗口尺寸、界面字体（Microsoft YaHei UI）与 AppBar 布局宽度
属于 Windows/Flet 专有概念，在安卓端由 Compose 主题与窗口系统承担，故不随迁。
"""

APP_NAME = "J2P Mobile"
APP_VERSION = "v2.4.4"

# 项目信息（帮助页展示用）
APP_AUTHOR = "WisadelZ"
PROJECT_URL = "https://github.com/WisadelZ/J2P-Mobile"
ISSUES_URL = PROJECT_URL + "/issues"
LICENSE_NAME = "GPL-3.0-or-later"
LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

# 状态颜色
COLOR_OK = "#27ae60"
COLOR_ERR = "#c0392b"
COLOR_IDLE = "#666666"

# 路由：首页固定为探索页，下载页与任务中心都是二级页，由首页顶栏 / 下载页进入
ROUTE_MAIN = "/"
ROUTE_DOWNLOAD = "/download"
ROUTE_TASKS = "/tasks"
ROUTE_ACCOUNT = "/account"
ROUTE_FAVORITE = "/favorite"
ROUTE_SETTINGS = "/settings"
ROUTE_EXPLORER = "/explorer"
ROUTE_HELP = "/help"
ROUTE_ALBUM = "/album"