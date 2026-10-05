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
"""日志桥接：把 jmcomic / jm2pdf 的 logging 输出转发到界面日志区。

桌面版由 Flet 界面传入 sink；安卓端 sink 指向 Kotlin 的 ``LogBridge``（见
:func:`install_kotlin_sink`），由 Kotlin 侧负责刷新日志面板。
"""

import logging
import sys

# jmcomic 下载时对**每张图**都会打这几行，属逐图噪声：大本子下轻松上万行，
# 而每一行都要经 jclass 跨一次 JNI 到 Kotlin。逐图进度在正式 UI 里由
# core.progress_plugin 回传的进度条承担，不需要占用日志面板与开销。
# 需要逐图排错时，把对应前缀从这里删掉即可恢复。
_NOISY_PREFIXES = (
    "调用插件:",
    "图片准备下载:",
    "图片下载完成:",
)


class NoiseFilter(logging.Filter):
    """丢掉逐图级别的噪声日志，只留章节 / 本子级节点与错误。"""

    def filter(self, record):
        try:
            message = record.getMessage()
        except Exception:
            return True
        return not message.startswith(_NOISY_PREFIXES)


class UiLogHandler(logging.Handler):
    """把日志记录交给界面回调（sink）处理。"""

    def __init__(self, sink):
        super().__init__()
        self._sink = sink

    def emit(self, record):
        try:
            self._sink(self.format(record))
        except Exception:
            pass


def install_kotlin_sink(level=logging.INFO):
    """把日志出口接到 Kotlin 的日志面板。

    只接管 ``jmcomic`` 与 ``jm2pdf`` 两个 logger 的等级，其记录经 root handler
    转发到 Kotlin；第三方库（urllib3 等）仍按 root 的 WARNING 级别，不刷屏。

    桌面环境下 ``java`` 模块不存在，直接返回 None，不影响本模块单独调试。
    """
    try:
        from java import jclass
    except ImportError:
        return None

    bridge = jclass("com.j2pmobile.android.LogBridge")

    def _sink(msg):
        # 回流失败不能静默：Chaquopy 的 stderr 会进 logcat，便于排查
        try:
            bridge.append("LOG", msg)
        except Exception as exc:
            print("LogBridge.append failed: %r" % (exc,), file=sys.stderr)

    handler = UiLogHandler(_sink)
    handler.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
    handler.setLevel(level)
    # 过滤器挂在 handler 上：被丢掉的行不会走到 sink，省下一次 JNI 跨语言调用
    handler.addFilter(NoiseFilter())
    logging.getLogger().addHandler(handler)

    for name in ("jmcomic", "jm2pdf"):
        logger = logging.getLogger(name)
        logger.setLevel(level)
        logger.propagate = True
    return handler