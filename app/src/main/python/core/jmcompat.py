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
"""jmcomic 运行时兼容层：按需适配站点响应格式变化（只 monkeypatch，不改依赖源码）。

背景：`jmcomic` 的 `JmApiClient.raise_if_resp_should_retry` 做「严格校验」——
要求响应体的**第一个有效字符必须是 `{`**，否则直接抛「请求不是json格式，强制重试！」。
一旦站点在 JSON 正文前多出任何东西（UTF-8 BOM、空白、包裹层…），整条请求链就会重试到失败。

上游随时可能改回去、也可能再换一种写法，所以这里**只做运行时探测、按需适配**：

- 先照常调用原方法，原方法通过时**零额外开销、零副作用**；
- 仅当原方法判定失败、且「状态码 < 500」且「响应体里确实能解析出一个完整 JSON 对象」时，
  才放行该响应并记一条日志 —— 这覆盖 BOM / 前后包裹 / 多余空白等“内容其实没问题、
  只是外壳不干净”的情况；
- 其余情况（500、空响应、残缺文本、站点自己的错误文案）**保持原样抛出**，绝不吞掉真实错误。

另外让 `JmcomicText.try_parse_json_object` 容忍开头的 BOM，少走一次正则兜底。

启动时（任何 jmcomic 请求之前）调用一次 [install] 即可；幂等，且任何异常都不会影响程序启动。
"""

import json
import logging

logger = logging.getLogger("jmcomic")
# jmcomic 的默认日志格式器需要 topic 字段，缺了会报 KeyError
_LOG_EXTRA = {"topic": "jmcompat"}

_INSTALLED = False


def install():
    """应用兼容层；幂等，绝不抛异常。返回是否（曾）成功应用。"""
    global _INSTALLED
    if _INSTALLED:
        return True
    try:
        _patch_client_retry()
        _patch_json_parse()
        _INSTALLED = True
        logger.info("jmcomic 运行时兼容层已就绪", extra=_LOG_EXTRA)
        return True
    except Exception as exc:          # noqa: BLE001  依赖变动时安全跳过
        logger.warning("jmcomic 运行时兼容层未就绪（不影响运行）：%s: %s",
                       type(exc).__name__, exc, extra=_LOG_EXTRA)
        return False


def _patch_client_retry():
    """放宽「首个有效字符必须是 {」的强制重试，仅对内容确实合法的响应生效。"""
    from jmcomic import jm_client_impl

    client_cls = getattr(jm_client_impl, "JmApiClient", None)
    if client_cls is None or getattr(client_cls, "_jm2pdf_compat_patched", False):
        return

    original = client_cls.raise_if_resp_should_retry

    def lenient_retry(self, resp, is_image=False):
        try:
            return original(self, resp, is_image)
        except Exception:
            # 只对「响应体里确实能解析出 JSON 对象、且不是服务端错误」的情况放行
            status = getattr(resp, "status_code", 200)
            if status < 500 and _parsable_json_object(resp):
                logger.info("检测到站点响应格式变化（JSON 带额外外壳），已自动兼容", extra=_LOG_EXTRA)
                return resp
            raise

    lenient_retry._jm2pdf_compat_patched = True
    client_cls.raise_if_resp_should_retry = lenient_retry
    setattr(client_cls, "_jm2pdf_compat_patched", True)


def _patch_json_parse():
    """让 JSON 解析容忍开头的 BOM。"""
    from jmcomic.jm_toolkit import JmcomicText

    if getattr(JmcomicText, "_jm2pdf_compat_patched", False):
        return

    original = JmcomicText.try_parse_json_object

    @classmethod
    def lenient_parse(cls, resp_text):
        text = str(resp_text or "").lstrip("\ufeff")
        return original(text)

    lenient_parse._jm2pdf_compat_patched = True
    JmcomicText.try_parse_json_object = lenient_parse
    setattr(JmcomicText, "_jm2pdf_compat_patched", True)


def _parsable_json_object(resp):
    """响应体里是否存在一个可解析的 JSON 对象（用于判断原校验是否过严）。"""
    try:
        text = resp.text or ""
    except Exception:                 # noqa: BLE001  取不到文本就不干预
        return False
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return False
    try:
        value = json.loads(text[start:end + 1])
    except ValueError:
        return False
    return isinstance(value, dict)