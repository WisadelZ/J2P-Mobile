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
"""热更补丁（hotfix）：运行时装载、清单检查、下载校验与本地状态维护。

设计原则（保守、可自愈）：

- 补丁**只做运行时 monkeypatch**，绝不修改 site-packages 里的依赖源码；
- 补丁装在一个独立的 ``<config_dir()>/hotfix/`` 目录里，与应用版本**强绑定**：
  一旦 ``installed.json`` 里的 ``app_version`` 与当前 ``APP_VERSION`` 不一致、
  或超出清单给出的 ``app_min`` / ``app_max`` 区间，就**删除整个目录**并回退到无补丁状态；
- 装载前先 ``compile()`` **预检**，预检不通过绝不执行任何补丁代码；
- 任何异常都被吞掉并计入 ``failed.json``（供界面提示「失败多次，建议清除补丁」），
  **绝不让补丁问题影响 Python 初始化**。

目录结构（均在应用私有 ``filesDir/config`` 内，不另建目录）::

    <config_dir()>/hotfix/
        patch.py        补丁本体（模块级 ``apply()``）
        installed.json  已安装补丁的元信息
        failed.json     最近一次失败信息 + 累计失败次数

补丁包命名固定为 ``v<软件版本>-hotfix.<序号>.zip``（例 ``v2.4.4-fix.1-hotfix.1.zip``），
zip 根目录必须含 ``patch.py``，可选 ``meta.json``；移动端所有 ABI 共用同一份补丁（纯 Python）。
"""

import hashlib
import importlib.util
import json
import logging
import os
import re
import shutil
import time
import zipfile

from core import constants
from core import config

_logger = logging.getLogger("jm2pdf")

HOTFIX_DIRNAME = "hotfix"
PATCH_FILENAME = "patch.py"
INSTALLED_FILENAME = "installed.json"
FAILED_FILENAME = "failed.json"

# 平台键（清单里的 platform 字段；小写）
HOTFIX_PLATFORM = "android"

# 补丁包大小上限与网络超时
MAX_PATCH_BYTES = 2 * 1024 * 1024
HOTFIX_TIMEOUT_SECONDS = 20

STATUS_NONE = "none"
STATUS_OK = "ok"
STATUS_ERROR = "error"


# ---------------------------------------------------------------------------
# 路径与 JSON 读写
# ---------------------------------------------------------------------------

def hotfix_dir():
    """补丁目录（位于 config_dir() 下，不在别处新建目录）。"""
    return os.path.join(config.config_dir(), HOTFIX_DIRNAME)


def patch_path():
    return os.path.join(hotfix_dir(), PATCH_FILENAME)


def _installed_path():
    return os.path.join(hotfix_dir(), INSTALLED_FILENAME)


def _failed_path():
    return os.path.join(hotfix_dir(), FAILED_FILENAME)


def _read_json(path):
    """读一个 JSON 文件；不存在 / 损坏都回 None（不让坏文件挡住流程）。"""
    try:
        with open(path, "r", encoding="utf-8") as file:
            data = json.load(file)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def _write_json(path, data):
    """原子写一个 JSON 文件（先写临时文件再替换）。"""
    base = os.path.dirname(path)
    if base and not os.path.isdir(base):
        os.makedirs(base, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _remove_quietly(path):
    try:
        os.remove(path)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# 版本比较
# ---------------------------------------------------------------------------

def _parse_version(text):
    """把 ``v2.4.4-fix.1`` 之类解析成可比较的数字列表（取前 4 段）。"""
    return [int(number) for number in re.findall(r"\d+", str(text or ""))[:4]]


def _compare(a, b):
    """逐段比较两个数字列表（缺位补 0），返回 -1 / 0 / 1。"""
    size = max(len(a), len(b))
    for index in range(size):
        x = a[index] if index < len(a) else 0
        y = b[index] if index < len(b) else 0
        if x != y:
            return -1 if x < y else 1
    return 0


def _version_in_range(version, low, high):
    """version 是否落在闭区间 [low, high] 内。

    区间**必须都存在且可解析**：``app_min`` / ``app_max`` 缺任意一个（或不是合法版本串）
    都视为不匹配，直接跳过该补丁包。
    """
    value = _parse_version(version)
    lower = _parse_version(low)
    upper = _parse_version(high)
    if not value or not lower or not upper:
        return False
    return _compare(lower, value) <= 0 <= _compare(value, upper)


def _installed_matches(installed):
    """已安装补丁是否与当前软件版本**完全一致**（不一致要整目录删除）。

    只做严格相等判定：``installed.json`` 的 ``app_version`` 必须与当前 ``APP_VERSION``
    完全相同；``app_min`` / ``app_max`` / ``file`` 仅作留档，不参与判定。
    """
    if not installed:
        return False
    return str(installed.get("app_version") or "") == constants.APP_VERSION


# ---------------------------------------------------------------------------
# 状态读取
# ---------------------------------------------------------------------------

def res_version():
    """当前资源版本（整数，单调递增）；无补丁 / 缺 patch.py / 与当前版本不符时返回基线。"""
    if not os.path.isfile(patch_path()):
        return constants.RES_VERSION_BASE
    installed = _read_json(_installed_path())
    if not _installed_matches(installed):
        return constants.RES_VERSION_BASE
    try:
        return int(installed.get("res_version"))
    except (TypeError, ValueError):
        return constants.RES_VERSION_BASE


def failure_count():
    """累计失败次数（供界面在失败多次时把「清除补丁」作为主按钮）。"""
    data = _read_json(_failed_path()) or {}
    try:
        return int(data.get("count") or 0)
    except (TypeError, ValueError):
        return 0


def _record_failure(message):
    """把一次失败写入 failed.json，并累加次数。"""
    try:
        data = _read_json(_failed_path()) or {}
        count = int(data.get("count") or 0) + 1
    except (TypeError, ValueError):
        count = 1
    installed = _read_json(_installed_path()) or {}
    try:
        _write_json(_failed_path(), {
            "pack_id": str(installed.get("pack_id") or ""),
            "error": str(message),
            "count": count,
            "at": int(time.time()),
        })
    except OSError:
        pass


def _clear_failure():
    """成功后清掉失败记录（自愈）。"""
    _remove_quietly(_failed_path())


# ---------------------------------------------------------------------------
# 清除
# ---------------------------------------------------------------------------

def clear():
    """删除整个补丁目录（含 patch.py / installed.json / failed.json）。"""
    directory = hotfix_dir()
    if not os.path.isdir(directory):
        return True
    try:
        shutil.rmtree(directory)
        _logger.info("热更补丁已清除：%s", directory)
        return True
    except OSError as exc:
        _logger.warning("清除热更补丁失败：%s", exc)
        return False


# ---------------------------------------------------------------------------
# 启动装载
# ---------------------------------------------------------------------------

def load_at_startup():
    """应用启动时装载补丁，返回 ``(status, message)``，status 取 none / ok / error。

    绝不向外抛异常：任何意外都会被兜成 ``("error", 描述)``。
    """
    try:
        return _load()
    except Exception as exc:      # 防御：装载器自身异常也不能把启动带崩
        message = "%s: %s" % (type(exc).__name__, exc)
        _logger.warning("热更补丁装载异常（不影响启动）：%s", message)
        _record_failure(message)
        return (STATUS_ERROR, message)


def _load():
    directory = hotfix_dir()
    # 1. 无补丁目录 → none
    if not os.path.isdir(directory):
        return (STATUS_NONE, "")
    # 2. 目录在但没有 patch.py → 脏数据，清掉后按无补丁处理
    path = patch_path()
    if not os.path.isfile(path):
        clear()
        return (STATUS_NONE, "")

    # 3. installed.json 缺失 / 版本不匹配 / 超出区间 → 整目录删除
    installed = _read_json(_installed_path())
    if not installed:
        clear()
        return (STATUS_NONE, "")
    if not _installed_matches(installed):
        _logger.info("热更补丁与当前版本不匹配，已移除（%s）", installed.get("pack_id") or "?")
        clear()
        return (STATUS_NONE, "")

    # 4. 预检：只编译，不执行任何用户代码
    try:
        with open(path, "r", encoding="utf-8") as file:
            source = file.read()
        code = compile(source, path, "exec")
    except Exception as exc:
        message = "补丁预检失败：%s: %s" % (type(exc).__name__, exc)
        _logger.warning("%s", message)
        _record_failure(message)
        return (STATUS_ERROR, message)

    # 5. 执行补丁模块并调用 apply()
    try:
        spec = importlib.util.spec_from_file_location("j2p_hotfix_patch", path)
        if spec is None or spec.loader is None:
            raise ImportError("无法为补丁创建模块")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        apply_func = getattr(module, "apply", None)
        if not callable(apply_func):
            raise AttributeError("补丁缺少可调用的 apply()")
        apply_func()
    except Exception as exc:
        message = "补丁执行失败：%s: %s" % (type(exc).__name__, exc)
        _logger.warning("%s", message)
        _record_failure(message)
        return (STATUS_ERROR, message)

    # 6. 成功
    _clear_failure()
    _logger.info("热更补丁已生效：%s（资源版本 %d）",
                 installed.get("pack_id") or "?", res_version())
    return (STATUS_OK, "")


# ---------------------------------------------------------------------------
# 安装补丁包
# ---------------------------------------------------------------------------

def _is_root_patch(name):
    """zip 成员是否就是根目录下的 patch.py。"""
    return name.replace("\\", "/") == PATCH_FILENAME


def _unsafe_name(name):
    """zip 成员名是否是绝对路径或含 ``..`` 的目录穿越（安装前必须拒绝）。"""
    normalized = name.replace("\\", "/")
    return (normalized.startswith("/") or normalized.startswith("../")
            or "/../" in normalized or normalized == "..")


def install_zip(zip_path, meta=None):
    """把补丁包安装到 hotfix 目录。

    流程（与桌面端一致，可回滚）：
    1. 解压到 ``hotfix.new``：先校验 zip 成员名（根目录必须含 ``patch.py``，
       且拒绝绝对路径与含 ``..`` 的穿越路径）；
    2. 旧目录改名为 ``hotfix.old``，再把 ``hotfix.new`` 原子替换为 ``hotfix``；
       任一步失败都回滚 ``hotfix.old``；
    3. 结束后清理 ``.old`` / ``.new`` 残留，写入 ``installed.json``。

    ``meta`` 里的 ``app_min`` / ``app_max`` / ``file`` 会一并留档（不参与启动判定）。
    """
    directory = hotfix_dir()
    parent = os.path.dirname(directory)
    temp = os.path.join(parent, HOTFIX_DIRNAME + ".new")
    backup = os.path.join(parent, HOTFIX_DIRNAME + ".old")
    shutil.rmtree(temp, ignore_errors=True)
    shutil.rmtree(backup, ignore_errors=True)
    try:
        os.makedirs(parent, exist_ok=True)
        with zipfile.ZipFile(zip_path) as archive:
            names = archive.namelist()
            if not any(_is_root_patch(name) for name in names):
                raise ValueError("补丁包缺少 patch.py")
            if any(_unsafe_name(name) for name in names):
                raise ValueError("补丁包包含非法路径")
            os.makedirs(temp, exist_ok=True)
            archive.extractall(temp)
    except (zipfile.BadZipFile, OSError, ValueError) as exc:
        _logger.warning("热更补丁解压失败：%s", exc)
        shutil.rmtree(temp, ignore_errors=True)
        return False

    # 原子替换：现有目录先让位，新目录就位；失败则回滚
    try:
        if os.path.isdir(directory):
            os.replace(directory, backup)
        os.replace(temp, directory)
    except OSError as exc:
        shutil.rmtree(temp, ignore_errors=True)
        if not os.path.isdir(directory) and os.path.isdir(backup):
            try:
                os.replace(backup, directory)
            except OSError:
                pass
        _logger.warning("热更补丁替换失败：%s", exc)
        return False
    shutil.rmtree(backup, ignore_errors=True)

    record = dict(meta or {})
    record.setdefault("applied_at", int(time.time()))
    try:
        _write_json(_installed_path(), record)
    except OSError as exc:
        _logger.warning("热更补丁状态写入失败：%s", exc)
        return False
    return True


# ---------------------------------------------------------------------------
# 清单检查（在「检查更新」流程里被 Python 侧调用）
# ---------------------------------------------------------------------------

def check_for_update():
    """拉取补丁清单，命中且校验通过时下载安装；任何失败都**静默**。

    返回字典：命中 ``{"found": True, "res_version": n, "file": "...", ...}``，
    其余情况 ``{"found": False}``（无命中 / 网络失败 / 403 / JSON 非法 / 校验失败）。
    """
    try:
        import requests

        response = requests.get(constants.HOTFIX_MANIFEST_URL, timeout=HOTFIX_TIMEOUT_SECONDS)
        if response.status_code != 200:
            _logger.info("补丁清单不可用：HTTP %s", response.status_code)
            return {"found": False}
        pack = _select_pack(response.json())
        if pack is None:
            _logger.info("没有适用的热更补丁")
            return {"found": False}

        url = str(pack.get("url") or "").strip()
        expected = str(pack.get("sha256") or "").strip().lower()
        if not url or not expected:
            _logger.info("补丁条目缺少 url / sha256，已忽略")
            return {"found": False}

        data = _download(url)
        if data is None:
            return {"found": False}
        if hashlib.sha256(data).hexdigest() != expected:
            _logger.warning("补丁包 sha256 校验失败，已忽略")
            return {"found": False}

        tmp_zip = os.path.join(config.config_dir(), "hotfix.tmp.zip")
        try:
            with open(tmp_zip, "wb") as file:
                file.write(data)
            meta = {
                "pack_id": str(pack.get("pack_id") or ""),
                "res_version": int(pack.get("res_version")),
                "app_version": constants.APP_VERSION,
                "app_min": str(pack.get("app_min") or ""),
                "app_max": str(pack.get("app_max") or ""),
                "sha256": expected,
                "file": str(pack.get("file") or ""),
            }
            if not install_zip(tmp_zip, meta):
                return {"found": False}
        finally:
            _remove_quietly(tmp_zip)

        _logger.info("热更补丁已安装：%s（资源版本 %d）", meta["pack_id"], meta["res_version"])
        return {
            "found": True,
            "res_version": meta["res_version"],
            "file": meta["file"],
            "pack_id": meta["pack_id"],
            "note": str(pack.get("note") or ""),
        }
    except Exception as exc:
        _logger.info("热更补丁检查异常（静默）：%s", exc)
        return {"found": False}


def _select_pack(manifest):
    """从清单里挑出适用的补丁条目：平台为安卓、软件版本在闭区间内、资源版本更高者优先。"""
    packs = (manifest or {}).get("packs")
    if not isinstance(packs, list):
        return None
    current = res_version()
    best = None
    for item in packs:
        if not isinstance(item, dict):
            continue
        if str(item.get("platform") or "").lower() != HOTFIX_PLATFORM:
            continue
        if not _version_in_range(
                constants.APP_VERSION, item.get("app_min"), item.get("app_max")):
            continue
        try:
            item_res = int(item.get("res_version"))
        except (TypeError, ValueError):
            continue
        if item_res <= current:
            continue
        if best is None or item_res > int(best.get("res_version")):
            best = item
    return best


def _download(url):
    """下载补丁包并做大小上限保护；失败返回 None（静默）。"""
    try:
        import requests

        response = requests.get(url, timeout=HOTFIX_TIMEOUT_SECONDS, stream=True)
        if response.status_code != 200:
            _logger.info("补丁包下载失败：HTTP %s", response.status_code)
            return None
        chunks = []
        size = 0
        for chunk in response.iter_content(64 * 1024):
            if not chunk:
                continue
            size += len(chunk)
            if size > MAX_PATCH_BYTES:
                _logger.warning("补丁包超过大小上限（%d 字节），已忽略", MAX_PATCH_BYTES)
                return None
            chunks.append(chunk)
        return b"".join(chunks)
    except Exception as exc:
        _logger.info("补丁包下载异常（静默）：%s", exc)
        return None