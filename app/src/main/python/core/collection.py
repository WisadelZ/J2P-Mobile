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
"""本地收藏层：tag 收藏（含本地收藏夹）与浏览历史。

官方没有 tag 收藏接口，因此 tag 收藏（以及它的收藏夹）完全存在本地；浏览历史同理。
两者都写进配置目录下的**同一个加密文件**（``collection.dat``，与 conf.yml /
account.dat 同级），加密方式复用 :mod:`core.account`（安卓优先 Keystore、桌面退回
内置密钥），磁盘上不出现任何明文。

约定：

- **不按账号隔离**：所有账号共用这一个文件；退出登录、切换账号都不影响它，
  只有卸载应用才会随应用数据一起消失。
- 解密后的结构：

  .. code-block:: json

     {
       "version": 1,
       "tag_folders": [{"id": "1", "name": "", "default": true, "created": 0}],
       "tags": [{"name": "巨乳", "folder_id": "1", "created": 0, "updated": 0}],
       "history": [{"id": "1462837", "name": "...", "author": "...", "created": 0}]
     }

- ``tags`` 与 ``history`` 都**按时间倒序**存（最新在最前）；超上限时从尾部
  （最早的一条）开始丢，即「覆盖最早的」。
"""

import functools
import json
import os
import threading
import time

from core import account as secure
from core.config import config_dir

COLLECTION_FILENAME = "collection.dat"
COLLECTION_VERSION = 1

# 「全部」筛选项的 id（与收藏列表页的 FOLDER_ALL 一致）
FOLDER_ALL = "0"
# 首次使用自动建好的默认收藏夹
DEFAULT_FOLDER_ID = "1"
DEFAULT_FOLDER_NAME = "默认收藏夹"

# 上限与分页（界面按这些常量展示）
TAG_LIMIT = 100             # tag 收藏上限，超出覆盖最早的
HISTORY_LIMIT = 360         # 浏览历史上限
TAG_PREVIEW = 8             # 账号页 tag 收藏预览条数
HISTORY_PREVIEW = 9         # 账号页浏览历史预览条数
TAG_PAGE_SIZE = 10          # tag 收藏完整页每页条数
HISTORY_PAGE_SIZE = 18      # 浏览历史完整页每页条数

# 所有对外函数都串行执行：本文件的操作都是「读整个文件 → 改 → 写回」，两个调用
# 并发时后写的会覆盖前写的（丢更新），甚至撞在同一个临时文件上而报文件不存在。
_LOCK = threading.RLock()


def _serialized(func):
    """给对外函数加同一把可重入锁（内部互相调用也不会自锁）。"""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        with _LOCK:
            return func(*args, **kwargs)
    return wrapper


def collection_path():
    """本地收藏文件路径：config/ 目录下（与 conf.yml / account.dat 同级）。"""
    return os.path.join(config_dir(), COLLECTION_FILENAME)


def _now():
    return int(time.time())


def _empty():
    return {
        "version": COLLECTION_VERSION,
        "tag_folders": [],
        "tags": [],
        "history": [],
    }


def _normalize(data):
    """把读到的内容整理成结构完整的字典，逐字段丢弃非法行。"""
    data = data if isinstance(data, dict) else {}
    out = _empty()

    folders = []
    seen_folders = set()
    for row in data.get("tag_folders") or []:
        if not isinstance(row, dict):
            continue
        folder_id = str(row.get("id") or "").strip()
        if not folder_id or folder_id in seen_folders:
            continue
        seen_folders.add(folder_id)
        folders.append({
            "id": folder_id,
            "name": str(row.get("name") or ""),
            "default": bool(row.get("default")),
            "created": int(row.get("created") or 0),
        })
    out["tag_folders"] = folders

    tags = []
    seen_tags = set()
    for row in data.get("tags") or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        if not name or name in seen_tags:
            continue
        seen_tags.add(name)
        tags.append({
            "name": name,
            "folder_id": str(row.get("folder_id") or DEFAULT_FOLDER_ID),
            "created": int(row.get("created") or 0),
            "updated": int(row.get("updated") or 0),
        })
    out["tags"] = tags[:TAG_LIMIT]

    history = []
    seen_albums = set()
    for row in data.get("history") or []:
        if not isinstance(row, dict):
            continue
        album_id = str(row.get("id") or "").strip()
        if not album_id or album_id in seen_albums:
            continue
        seen_albums.add(album_id)
        history.append({
            "id": album_id,
            "name": str(row.get("name") or ""),
            "author": str(row.get("author") or ""),
            "created": int(row.get("created") or 0),
        })
    out["history"] = history[:HISTORY_LIMIT]
    return out


def _ensure_default_folder(data):
    """确保默认收藏夹存在（首次使用时建一个）；返回是否有改动。"""
    if data["tag_folders"]:
        return False
    data["tag_folders"] = [{
        "id": DEFAULT_FOLDER_ID,
        "name": DEFAULT_FOLDER_NAME,
        "default": True,
        "created": _now(),
    }]
    return True


def _load():
    """读取并整理本地收藏；文件不存在时给一份空结构（并落默认收藏夹）。"""
    out = _normalize(secure.read_secure(collection_path()))
    if _ensure_default_folder(out):
        _save(out)
    return out


def _save(data):
    secure.write_secure(collection_path(), data)


def _next_folder_id(data):
    """分配一个没用过的收藏夹 id（从 "1" 起递增，不复用已删的）。"""
    used = {folder["id"] for folder in data["tag_folders"]}
    index = 1
    while str(index) in used:
        index += 1
    return str(index)


# ---------------------------------------------------------------------------
# 收藏夹
# ---------------------------------------------------------------------------

@_serialized
def folders():
    """全部 tag 收藏夹（含默认收藏夹）。"""
    return list(_load()["tag_folders"])


@_serialized
def create_folder(name):
    """新建 tag 收藏夹；同名直接复用已有的，返回该收藏夹。"""
    name = str(name or "").strip()
    if not name:
        raise ValueError("收藏夹名称不能为空")
    data = _load()
    for folder in data["tag_folders"]:
        if folder["name"] == name:
            return folder
    folder = {"id": _next_folder_id(data), "name": name, "default": False,
              "created": _now()}
    data["tag_folders"].append(folder)
    _save(data)
    return folder


# ---------------------------------------------------------------------------
# tag 收藏
# ---------------------------------------------------------------------------

def _folder_names(data):
    return {folder["id"]: folder["name"] for folder in data["tag_folders"]}


def _tag_json(data, tag):
    return {
        "name": tag["name"],
        "folder_id": tag["folder_id"],
        "folder_name": _folder_names(data).get(tag["folder_id"], ""),
    }


def _resolve_folder(data, folder_id):
    folder_id = str(folder_id or DEFAULT_FOLDER_ID)
    if folder_id not in {folder["id"] for folder in data["tag_folders"]}:
        return DEFAULT_FOLDER_ID
    return folder_id


@_serialized
def add_tag(name, folder_id=DEFAULT_FOLDER_ID):
    """收藏一个 tag（重复收藏即更新所属收藏夹并提到最前）。

    超出 :data:`TAG_LIMIT` 时从尾部丢掉最早的一条（覆盖最早的）。
    """
    name = str(name or "").strip()
    if not name:
        raise ValueError("标签为空")
    data = _load()
    _ensure_default_folder(data)
    folder_id = _resolve_folder(data, folder_id)
    now = _now()
    data["tags"] = [tag for tag in data["tags"] if tag["name"] != name]
    data["tags"].insert(0, {"name": name, "folder_id": folder_id,
                            "created": now, "updated": now})
    del data["tags"][TAG_LIMIT:]
    _save(data)
    return True


@_serialized
def remove_tag(name):
    """取消收藏一个 tag；原本就不存在时返回 False。"""
    name = str(name or "")
    data = _load()
    remaining = [tag for tag in data["tags"] if tag["name"] != name]
    if len(remaining) == len(data["tags"]):
        return False
    data["tags"] = remaining
    _save(data)
    return True


@_serialized
def move_tag(name, folder_id):
    """把一个 tag 移到指定收藏夹；替换掉不存在的收藏夹时落到默认收藏夹。"""
    name = str(name or "")
    data = _load()
    folder_id = _resolve_folder(data, folder_id)
    changed = False
    for tag in data["tags"]:
        if tag["name"] == name:
            tag["folder_id"] = folder_id
            tag["updated"] = _now()
            changed = True
    if changed:
        _save(data)
    return changed


@_serialized
def tag_preview(limit=TAG_PREVIEW):
    """账号页预览：最前面的若干条（默认 :data:`TAG_PREVIEW` 条）。"""
    data = _load()
    return [_tag_json(data, tag) for tag in data["tags"][:max(0, int(limit))]]


@_serialized
def tag_page(page=1, folder_id=FOLDER_ALL):
    """tag 收藏完整页的一页（可按收藏夹过滤），附全部收藏夹供界面选择。"""
    data = _load()
    folder_id = str(folder_id or FOLDER_ALL)
    items = data["tags"]
    if folder_id != FOLDER_ALL:
        items = [tag for tag in items if tag["folder_id"] == folder_id]
    total = len(items)
    page = max(1, int(page))
    start = (page - 1) * TAG_PAGE_SIZE
    return {
        "items": [_tag_json(data, tag) for tag in items[start:start + TAG_PAGE_SIZE]],
        "total": total,
        "page": page,
        "page_size": TAG_PAGE_SIZE,
        "folders": list(data["tag_folders"]),
    }


# ---------------------------------------------------------------------------
# 浏览历史（只记本子）
# ---------------------------------------------------------------------------

@_serialized
def history_add(album_id, name="", author=""):
    """记录一次本子浏览：已存在就更新信息并提到最前，超出上限丢最早的。"""
    album_id = str(album_id or "").strip()
    if not album_id:
        return False
    data = _load()
    previous = next((row for row in data["history"] if row["id"] == album_id), None)
    name = str(name or "").strip() or (previous or {}).get("name", "")
    author = str(author or "").strip() or (previous or {}).get("author", "")
    data["history"] = [row for row in data["history"] if row["id"] != album_id]
    data["history"].insert(0, {"id": album_id, "name": name,
                               "author": author, "created": _now()})
    del data["history"][HISTORY_LIMIT:]
    _save(data)
    return True


@_serialized
def history_preview(limit=HISTORY_PREVIEW):
    """账号页预览：最近浏览的若干本（默认 :data:`HISTORY_PREVIEW` 本）。"""
    data = _load()
    return list(data["history"][:max(0, int(limit))])


@_serialized
def history_page(page=1, keyword=""):
    """浏览历史完整页的一页；关键词按本子 ID / 名称 / 作者匹配。"""
    data = _load()
    keyword = str(keyword or "").strip().lower()
    items = data["history"]
    if keyword:
        items = [row for row in items
                 if keyword in row["name"].lower()
                 or keyword in row["author"].lower()
                 or keyword in row["id"]]
    total = len(items)
    page = max(1, int(page))
    start = (page - 1) * HISTORY_PAGE_SIZE
    return {
        "items": items[start:start + HISTORY_PAGE_SIZE],
        "total": total,
        "page": page,
        "page_size": HISTORY_PAGE_SIZE,
    }


# ---------------------------------------------------------------------------
# 导出 / 导入（设置页；导出为可移植的明文 JSON，导入整体覆盖）
# ---------------------------------------------------------------------------

@_serialized
def export_text():
    """把本地收藏导出成 JSON 文本（明文，便于跨安装迁移 / 备份）。"""
    return json.dumps(_load(), ensure_ascii=False, indent=2)


@_serialized
def import_text(text):
    """从 JSON 文本导入本地收藏，整体覆盖现有内容。"""
    if not (text or "").strip():
        raise ValueError("导入内容为空")
    try:
        data = json.loads(text)
    except ValueError:
        raise ValueError("不是有效的本地数据文件")
    if not isinstance(data, dict):
        raise ValueError("不是有效的本地数据文件")
    out = _normalize(data)
    _ensure_default_folder(out)
    _save(out)
    return True


# ---------------------------------------------------------------------------
# 云同步（与网站账号配合）
#
# 云端结构与本地结构的差异：
#   · 收藏夹：本地用自增 id，云端以**名字**为键（名字本地唯一，跨设备也稳定）；
#   · 收藏标签：本地用 folder_id，云端用 folder_name；
#   · 浏览记录：本地 id 即本子 ID，云端同样用本子 ID 作键。
# 两边都用「秒级时间戳」表示时间，合并时取更新的那条。
# ---------------------------------------------------------------------------

def _read_state():
    """返回 ``(data, corrupted)``。

    · 文件不存在 → 空结构（含默认收藏夹），``corrupted=False``；
    · 文件存在但解不开（换机 / 换安装 / 内容损坏）→ ``corrupted=True``，
      此时调用方应禁止一切本地操作，避免把损坏状态当成「空数据」覆盖掉。
    """
    path = collection_path()
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except OSError:
        data = _empty()
        _ensure_default_folder(data)
        return data, False
    payload = secure.open_payload(raw)
    if payload is None:
        return _empty(), True
    data = _normalize(payload)
    _ensure_default_folder(data)
    return data, False


def _counts(data):
    return {"folders": len(data["tag_folders"]),
            "tags": len(data["tags"]),
            "history": len(data["history"])}


def _canon_local(data):
    """本地数据的规范化指纹（判断一次操作后是否真的发生变化）。"""
    return (
        tuple(sorted((f["name"], bool(f.get("default"))) for f in data["tag_folders"])),
        tuple(sorted((t["name"], t["folder_id"], t["updated"]) for t in data["tags"])),
        tuple(sorted((h["id"], h["name"], h["author"], h["created"]) for h in data["history"])),
    )


def _to_cloud(data):
    """本地结构 → 云端结构（收藏夹 id 换成名字）。"""
    names = {f["id"]: f["name"] for f in data["tag_folders"]}
    default_name = next((f["name"] for f in data["tag_folders"] if f.get("default")),
                        DEFAULT_FOLDER_NAME)
    return {
        "folders": [{"name": f["name"], "is_default": bool(f.get("default")),
                     "created": int(f["created"] or 0), "updated": int(f["created"] or 0)}
                    for f in data["tag_folders"]],
        "tags": [{"name": t["name"],
                  "folder_name": names.get(t["folder_id"], default_name),
                  "created": int(t["created"] or 0), "updated": int(t["updated"] or 0)}
                 for t in data["tags"]],
        "history": [{"album_id": h["id"], "name": h["name"], "author": h["author"],
                     "created": int(h["created"] or 0)} for h in data["history"]],
    }


def _normalize_cloud(cloud):
    """把云端返回的数据整理成规范结构（逐字段丢弃非法行、按时间倒序、截断上限）。"""
    cloud = cloud if isinstance(cloud, dict) else {}

    folders = []
    seen_folders = set()
    for row in cloud.get("folders") or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        if not name or name in seen_folders:
            continue
        seen_folders.add(name)
        folders.append({"name": name, "is_default": bool(row.get("is_default")),
                        "created": int(row.get("created") or 0),
                        "updated": int(row.get("updated") or 0)})

    tags = []
    seen_tags = set()
    for row in cloud.get("tags") or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        if not name or name in seen_tags:
            continue
        seen_tags.add(name)
        tags.append({"name": name, "folder_name": str(row.get("folder_name") or "").strip(),
                     "created": int(row.get("created") or 0),
                     "updated": int(row.get("updated") or 0)})

    history = []
    seen_albums = set()
    for row in cloud.get("history") or []:
        if not isinstance(row, dict):
            continue
        album_id = str(row.get("album_id") or "").strip()
        if not album_id or album_id in seen_albums:
            continue
        seen_albums.add(album_id)
        history.append({"album_id": album_id, "name": str(row.get("name") or ""),
                        "author": str(row.get("author") or ""),
                        "created": int(row.get("created") or 0)})

    tags.sort(key=lambda t: t["updated"], reverse=True)
    history.sort(key=lambda h: h["created"], reverse=True)
    return {"folders": folders, "tags": tags[:TAG_LIMIT], "history": history[:HISTORY_LIMIT]}


def _merge_cloud(base, incoming):
    """把 incoming 合并进 base（同键取时间更新的那条；收藏夹取已存在的）。"""
    folders = {f["name"]: f for f in base["folders"]}
    for f in incoming["folders"]:
        folders.setdefault(f["name"], f)
    tags = {t["name"]: t for t in base["tags"]}
    for t in incoming["tags"]:
        cur = tags.get(t["name"])
        if cur is None or t["updated"] > cur["updated"]:
            tags[t["name"]] = t
    history = {h["album_id"]: h for h in base["history"]}
    for h in incoming["history"]:
        cur = history.get(h["album_id"])
        if cur is None or h["created"] > cur["created"]:
            history[h["album_id"]] = h
    return {"folders": list(folders.values()), "tags": list(tags.values()),
            "history": list(history.values())}


def _cloud_to_local(cloud):
    """云端结构 → 本地结构（收藏夹名 → 本地自增 id），并补齐默认收藏夹。"""
    names = []
    default_name = None
    for f in cloud["folders"]:
        name = f["name"]
        if name in names:
            continue
        names.append(name)
        if f.get("is_default") and default_name is None:
            default_name = name
    if not names:
        names = [DEFAULT_FOLDER_NAME]
    if default_name is None:
        default_name = names[0]
    id_of = {name: str(index + 1) for index, name in enumerate(names)}

    out = _empty()
    out["tag_folders"] = [{"id": id_of[name], "name": name,
                           "default": (name == default_name), "created": _now()}
                          for name in names]
    out["tags"] = []
    for t in cloud["tags"]:
        folder_id = id_of.get(t["folder_name"], id_of[default_name])
        out["tags"].append({"name": t["name"], "folder_id": folder_id,
                            "created": t["created"], "updated": t["updated"]})
    out["history"] = [{"id": h["album_id"], "name": h["name"], "author": h["author"],
                       "created": h["created"]} for h in cloud["history"]]
    out["tags"].sort(key=lambda t: t["updated"], reverse=True)
    out["history"].sort(key=lambda h: h["created"], reverse=True)
    return _normalize(out)


@_serialized
def snapshot():
    """同步用：返回本地全部数据（云端结构）+ 数量；本地损坏时 ``corrupted=True``。"""
    data, corrupted = _read_state()
    if corrupted:
        return {"corrupted": True, "folders": [], "tags": [], "history": [],
                "counts": {"folders": 0, "tags": 0, "history": 0}}
    cloud = _to_cloud(data)
    return {"corrupted": False, "folders": cloud["folders"], "tags": cloud["tags"],
            "history": cloud["history"], "counts": _counts(data)}


@_serialized
def apply_cloud(mode, cloud):
    """把云端数据合并 / 覆盖到本地。

    ``mode`` 为 ``"merge"``（合并，同键取更新的）或 ``"overwrite"``（整体覆盖）。
    返回 ``{"changed": bool, "counts": {...}}``；本地损坏时抛异常（禁止本地操作）。
    """
    data, corrupted = _read_state()
    if corrupted:
        raise ValueError("本地数据已损坏，已禁止本地操作")
    incoming = _normalize_cloud(cloud)
    before = _canon_local(data)
    if str(mode or "").strip().lower() == "overwrite":
        merged = incoming
    else:
        merged = _merge_cloud(_to_cloud(data), incoming)
    out = _cloud_to_local(merged)
    _save(out)
    return {"changed": before != _canon_local(out), "counts": _counts(out)}


@_serialized
def clear_local():
    """清除本地的浏览记录与标签收藏（收藏夹一并重置为默认收藏夹）。"""
    data, corrupted = _read_state()
    if corrupted:
        raise ValueError("本地数据已损坏，已禁止本地操作")
    before = _canon_local(data)
    cleared = _counts(data)
    out = _empty()
    _ensure_default_folder(out)
    _save(out)
    return {"changed": before != _canon_local(out), "cleared": cleared,
            "counts": _counts(out)}