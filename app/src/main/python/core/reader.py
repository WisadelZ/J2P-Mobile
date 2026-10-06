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
"""浏览支持：把已下载的 PDF 或图片文件夹统一成「逐页取图」。

PDF 由本工具用 img2pdf 生成，每一页就是原图本身（JPEG 直接以 DCTDecode 内嵌），
所以不需要引入 PDF 渲染引擎，直接取出页面里的图片流即可：

- DCTDecode 的流本身就是 JPEG 字节，直接交给界面显示；
- 其它编码（例如内嵌 PNG 产生的 FlateDecode）用 Pillow 解出像素后重新编码成 PNG。

供资源管理器页的内置浏览功能使用。

安卓端改造（第 3 步）：``pikepdf`` → ``pypdf``（安卓无 pikepdf 轮子）。
取页图的两条路径与原实现一一对应：``read_raw_bytes()`` → 流对象的原始字节，
``read_bytes()`` → :meth:`pypdf.generic.StreamObject.get_data`（按 Filter 解压）。
"""

import io
import os
import re
import threading

from PIL import Image
from pypdf import PdfReader
from pypdf.generic import ArrayObject, NameObject

# 可浏览的图片后缀：图片文件夹里只挑这些文件
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

# 未压缩像素只处理 8 位深，其它位深不做转换
_BITS_PER_COMPONENT = 8

# PDF 色彩空间 -> Pillow 像素模式
_COLOR_MODES = {
    "/DeviceGray": "L",
    "/DeviceRGB": "RGB",
    "/DeviceCMYK": "CMYK",
}


class ReaderError(Exception):
    """打不开文件或取不出页面时抛出，由界面提示用户。"""


def is_pdf(path):
    """是否是 PDF 文件。"""
    return os.path.isfile(path) and path.lower().endswith(".pdf")


def list_images(folder):
    """列出文件夹里的图片（按名称中的数字自然排序），没有图片时返回空列表。"""
    if not os.path.isdir(folder):
        return []
    names = [name for name in os.listdir(folder)
             if name.lower().endswith(IMAGE_SUFFIXES)]
    return [os.path.join(folder, name) for name in sorted(names, key=_sort_key)]


def _sort_key(name):
    """名称里的数字段按数值比较：1.jpg 排在 10.jpg 前面。"""
    parts = re.split(r"(\d+)", name.lower())
    return [(0, int(part)) if part.isdigit() else (1, part) for part in parts]


def _resolve(obj):
    """把 pypdf 的间接引用解开。"""
    if obj is None:
        return None
    getter = getattr(obj, "get_object", None)
    return getter() if callable(getter) else obj


def _raw_stream(obj):
    """流对象的原始（未解码）字节 —— DCTDecode 时就是 JPEG 本身。"""
    data = getattr(obj, "_data", None)
    if data is None:
        return bytes(obj.get_data())
    return bytes(data)


class Reader:
    """一次浏览会话：PDF 与图片文件夹统一成「逐页取图」。

    页面索引从 0 开始。``size(index)`` 给出图片的像素尺寸（竖式滚动定位当前页用），
    ``render(index)`` 返回可以直接显示的图片字节。pypdf 的 Reader 不是线程安全的，
    取页时加锁。
    """

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.kind = "pdf" if is_pdf(path) else "images"
        # 本子 ID：保存单页 / 书签归属时用来标识这一本（PDF 取元数据里的 id）
        self.album_id = self._resolve_album_id()
        # PDF 每页没有名字，右下角显示「当前页/总页数」；图片文件夹显示文件名
        self.numbered_pages = self.kind == "pdf"
        self._files = []
        self._pdf = None
        self._sizes = {}
        self._lock = threading.Lock()
        if self.kind == "images":
            self._files = list_images(path)
        else:
            try:
                self._pdf = PdfReader(path)
            except Exception as exc:
                raise ReaderError(exc)
        if not self.page_count:
            raise ReaderError("文件里没有可显示的页面")

    @property
    def page_count(self):
        return len(self._files) if self.kind == "images" else len(self._pdf.pages)

    def _resolve_album_id(self):
        """本子 ID：PDF 优先取元数据里的 id，其余退回文件 / 文件夹名。"""
        if self.kind == "pdf":
            try:
                from core import pdf_metadata

                meta = pdf_metadata.read_metadata(self.path) or {}
                album = str(meta.get("album_id") or "").strip()
                if album:
                    return album
            except Exception:
                pass          # 读元数据只是为了让命名更准确，失败不影响浏览
            return os.path.splitext(self.name)[0]
        return os.path.basename(self.path.rstrip("\\/")) or self.name

    def page_name(self, index):
        """当前页的名称：图片是文件名；PDF 用不到（界面显示页码）。"""
        return os.path.basename(self._files[index]) if self.kind == "images" else self.name

    def size(self, index):
        """该页图片的像素尺寸。"""
        cached = self._sizes.get(index)
        if cached is not None:
            return cached
        if self.kind == "images":
            with Image.open(self._files[index]) as image:
                size = image.size
        else:
            with self._lock:
                obj = self._page_image(index)
                size = (int(obj.get("/Width")), int(obj.get("/Height")))
        self._sizes[index] = size
        return size

    def render(self, index):
        """该页的图片字节（JPEG 或 PNG），可直接交给界面显示。"""
        if self.kind == "images":
            with open(self._files[index], "rb") as file:
                return file.read()
        with self._lock:
            obj = self._page_image(index)
            if str(obj.get("/Filter")) == "/DCTDecode":
                # 内嵌的 JPEG 原图：直接取原始流，不必重新编码，画质与体积都不变
                return _raw_stream(obj)
            return _encode_png(obj)

    def _page_image(self, index):
        """取出该页里的图片对象（本工具生成的 PDF 每页就是一张整页图）。"""
        try:
            page = self._pdf.pages[index]
            resources = _resolve(page.get("/Resources"))
            xobject = _resolve(resources.get("/XObject")) if resources is not None else None
            if xobject is not None:
                for _, ref in xobject.items():
                    obj = _resolve(ref)
                    if str(obj.get("/Subtype")) == "/Image":
                        return obj
        except ReaderError:
            raise
        except Exception as exc:
            raise ReaderError(exc)
        raise ReaderError("该页没有可显示的图片")


def _encode_png(obj):
    """非 JPEG 内嵌的图片：解码成像素后重新编码为 PNG。"""
    data = bytes(obj.get_data())            # pypdf 按 Filter 解压
    width, height = int(obj.get("/Width")), int(obj.get("/Height"))
    bits = int(obj.get("/BitsPerComponent", 8))
    mode, palette = _pixel_mode(obj.get("/ColorSpace"))
    if mode is None or bits != _BITS_PER_COMPONENT:
        raise ReaderError("不支持的图片编码，无法显示该页")
    image = Image.frombytes(mode, (width, height), data)
    if palette is not None:
        image.putpalette(palette)
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def _pixel_mode(space):
    """把 PDF 色彩空间映射成 Pillow 像素模式，返回 ``(模式, 调色板或 None)``。"""
    space = _resolve(space)
    if isinstance(space, NameObject) or isinstance(space, str):
        return _COLOR_MODES.get(str(space)), None
    if isinstance(space, ArrayObject) or isinstance(space, (list, tuple)):
        if len(space) >= 2:
            if str(_resolve(space[0])) == "/Indexed" and len(space) >= 4:
                return "P", bytes(_resolve(space[3]))   # /Indexed [基础空间 上限 调色板]
            return _pixel_mode(space[1])                # ICCBased 等：按基础空间处理
    return None, None