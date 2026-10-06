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
"""PDF 元数据：生成 PDF 时把本子资料写进 DocInfo，并提供读回解析。

本模块只处理 PDF 文件，图片文件不做任何改动（不写 EXIF、不写侧车文件、不重新编码）。
模块导入时会向 jmcomic 注册插件，因此必须早于 ``create_option_by_str`` 被导入
（由 ``core.downloader`` 顶部触发）。

安卓端改造（第 3 步）：原桌面版用 ``pikepdf``，安卓无对应轮子，改用 ``pypdf``
（已在第 0 步验证等价：写 / 读 DocInfo、按页取图、取原始流）。
"""

import io
import os

import jmcomic
from pypdf import PdfReader, PdfWriter

from core.constants import APP_VERSION

# 插件 key：conf.yml 中 after_photo 的 plugin 项填这个值
PLUGIN_KEY = "jm2pdf_meta_pdf"

# Keywords 里的三段前缀
_KEY_ID = "id"
_KEY_PAGES = "pages"
_KEY_CHAPTER = "chapter"


def build_docinfo(album, photo):
    """把 JmAlbumDetail / JmPhotoDetail 映射为 PDF DocInfo 字段。"""
    docinfo = {
        "title": album.name,
        "author": ", ".join(album.authors or []),
        "creator": "J2P Mobile %s" % APP_VERSION,
        # keywords 必须是 list，且元素内不能有逗号：img2pdf 内部用逗号拼接
        "keywords": [
            "%s:%s" % (_KEY_ID, album.album_id),
            "%s:%d" % (_KEY_PAGES, len(photo)),
            "%s:%d" % (_KEY_CHAPTER, photo.album_index),
        ],
    }
    tags = [str(tag) for tag in (album.tags or [])]
    if tags:
        # tags 为空时整项不传，img2pdf 会跳过 Subject 字段
        docinfo["subject"] = ", ".join(tags)
    return docinfo


def apply_docinfo(pdf_path, docinfo):
    """补写 DocInfo。

    与桌面版（pikepdf 原地补写、页数与体积不变）不同，pypdf 需要整体重写文件，
    因此先写临时文件再原子替换，避免中途失败留下半个 PDF；页数不变，
    体积可能有微小变化。
    """
    with open(pdf_path, "rb") as f:
        data = f.read()
    writer = PdfWriter(clone_from=PdfReader(io.BytesIO(data)))
    metadata = {}
    for key, value in docinfo.items():
        metadata["/" + key.capitalize()] = (
            value if isinstance(value, str) else ",".join(value))
    writer.add_metadata(metadata)
    tmp = pdf_path + ".tmp"
    with open(tmp, "wb") as f:
        writer.write(f)
    os.replace(tmp, pdf_path)


def read_metadata(pdf_path):
    """读回 PDF 里的漫画元数据，返回界面需要的字段字典。

    如果 PDF 里没有任何本工具写入的元数据则返回 None（例如旧版本生成的 PDF）。
    文件损坏、被加密无法读取等情况会抛出异常，由调用方处理。
    """
    reader = PdfReader(pdf_path)
    info = reader.metadata or {}
    docinfo = {str(key): value for key, value in info.items()}
    page_count = len(reader.pages)

    title = _text(docinfo.get("/Title"))
    author = _text(docinfo.get("/Author"))
    tags = _text(docinfo.get("/Subject"))
    fields = _parse_keywords(_text(docinfo.get("/Keywords")))
    if not any((title, author, tags, fields)):
        return None
    return {
        "title": title,
        "author": author,
        "tags": tags,
        "album_id": fields.get(_KEY_ID, ""),
        "chapter": fields.get(_KEY_CHAPTER, ""),
        # 元数据里没有 pages 时退回 PDF 的实际页数
        "pages": fields.get(_KEY_PAGES) or str(page_count),
    }


def _text(value):
    """把 DocInfo 里的值统一转成去空白的字符串。"""
    return str(value).strip() if value is not None else ""


def write_import_metadata(pdf_path, album_id, fallback_title=""):
    """给导入的 PDF 补写元数据：本子 ID（Keywords 的 ``id:``）+ 页数 + 章节序号。

    字段与「软件下载生成」的 PDF 完全一致（都在 DocInfo 的 Keywords 里），因此资源管理器
    的按 ID 搜索、元数据面板，以及基于本子 ID 的书签文件都能直接沿用。原有的标题 / 作者 /
    标签尽量保留，没有标题时用文件名兜底。
    """
    reader = PdfReader(pdf_path)
    info = reader.metadata or {}
    title = _text(info.get("/Title")) or str(fallback_title or "").strip()
    docinfo = {
        "title": title,
        "creator": "J2P Mobile %s" % APP_VERSION,
        "keywords": [
            "%s:%s" % (_KEY_ID, album_id),
            "%s:%d" % (_KEY_PAGES, len(reader.pages)),
            "%s:%d" % (_KEY_CHAPTER, 1),
        ],
    }
    author = _text(info.get("/Author"))
    if author:
        docinfo["author"] = author
    subject = _text(info.get("/Subject"))
    if subject:
        docinfo["subject"] = subject
    apply_docinfo(pdf_path, docinfo)


def _parse_keywords(keywords):
    """解析 ``id:1,pages:2,chapter:3`` 形式的关键字。"""
    fields = {}
    for part in keywords.split(","):
        key, sep, value = part.partition(":")
        if sep:
            fields[key.strip()] = value.strip()
    return fields


class MetaImg2pdfPlugin(jmcomic.Img2pdfPlugin):
    """继承官方 img2pdf 插件：先正常生成 PDF，再补写本子元数据。"""

    plugin_key = PLUGIN_KEY
    plugin_dependencies = ("img2pdf",)

    def invoke(self, photo=None, album=None, downloader=None, pdf_dir=None,
               filename_rule="Pid", dir_rule=None, **kwargs):
        super().invoke(photo=photo, album=album, downloader=downloader, pdf_dir=pdf_dir,
                       filename_rule=filename_rule, dir_rule=dir_rule, **kwargs)

        # 本插件只用于 after_photo；album 模式下 photo 为 None，不做处理
        if photo is None:
            return
        try:
            # 与父类 invoke 用的是同一套参数，算出的路径完全一致
            pdf_path = self.decide_filepath(album, photo, filename_rule, "pdf", pdf_dir, dir_rule)
            if os.path.isfile(pdf_path):
                apply_docinfo(pdf_path, build_docinfo(photo.from_album, photo))
        except Exception as exc:      # 元数据写入失败不能影响下载主流程
            self.log("写入 PDF 元数据失败：%s" % exc, "error")


jmcomic.JmModuleConfig.register_plugin(MetaImg2pdfPlugin)