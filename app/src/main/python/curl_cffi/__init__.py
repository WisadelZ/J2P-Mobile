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
"""curl_cffi 的占位实现（Android / Chaquopy 专用）。

背景
----
`jmcomic` 声明依赖 `curl-cffi`，并在 `jm_async_client.py` 的**模块级**执行
``from curl_cffi.requests import AsyncSession`` —— 因此只要 ``import jmcomic``
就必然需要本模块存在。

而 `curl-cffi` 捆绑 libcurl-impersonate（原生库），Android 上没有可用的
预编译轮子。本项目并不使用它：HTTP 请求统一走 commonx 的 ``requests``
后端（见 ``jmcomic`` option 中的 ``client.postman.type``）。

因此这里提供一个**纯 Python 占位模块**，只为满足导入，不做真实转译。
"""

from . import requests  # noqa: F401

__version__ = "0.0.0+jm2pdf-stub"

__all__ = ["requests"]