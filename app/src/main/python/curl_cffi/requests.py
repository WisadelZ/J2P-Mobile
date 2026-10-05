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
"""``curl_cffi.requests`` 的最小占位实现。

- 同步接口（``get`` / ``post`` / ``request`` / ``Session``）尽力转发到标准
  ``requests``，并忽略 curl_cffi 专有的 ``impersonate`` 参数；
- 异步接口 ``AsyncSession`` 仅提供可导入的类，实例化即抛出，避免被误用。

注意：本项目的 HTTP 后端是 commonx 的 ``requests``，正常路径不会用到
本模块的同步/异步接口。
"""

import requests as _requests

__all__ = ["get", "post", "request", "Session", "AsyncSession"]


class Session:
    """对 ``requests.Session`` 的薄包装，忽略 ``impersonate``。"""

    def __init__(self, *args, **kwargs):
        kwargs.pop("impersonate", None)
        self._session = _requests.Session()

    def get(self, url, **kwargs):
        kwargs.pop("impersonate", None)
        return self._session.get(url, **kwargs)

    def post(self, url, **kwargs):
        kwargs.pop("impersonate", None)
        return self._session.post(url, **kwargs)

    def request(self, method, url, **kwargs):
        kwargs.pop("impersonate", None)
        return self._session.request(method, url, **kwargs)

    def __getattr__(self, item):
        return getattr(self._session, item)


def get(url, **kwargs):
    kwargs.pop("impersonate", None)
    return _requests.get(url, **kwargs)


def post(url, **kwargs):
    kwargs.pop("impersonate", None)
    return _requests.post(url, **kwargs)


def request(method, url, **kwargs):
    kwargs.pop("impersonate", None)
    return _requests.request(method, url, **kwargs)


class AsyncSession:
    """占位类：Android 上不提供 curl_cffi 的异步会话。"""

    def __init__(self, *args, **kwargs):
        raise RuntimeError(
            "curl_cffi.AsyncSession 在 Android 上不可用（占位模块）。"
            "本项目使用 commonx 的 requests 后端，请勿使用异步客户端。"
        )