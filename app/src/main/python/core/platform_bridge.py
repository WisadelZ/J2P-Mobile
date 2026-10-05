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
"""Python → Kotlin 平台能力桥（第 3 步用）。

桌面版里这些事分别是 Windows 专有实现或 Pillow 实现；安卓端统一改由第 2 步的
Kotlin 平台层承担：

| 本模块函数 | Kotlin 实现 | 替换的桌面实现 |
|---|---|---|
| :func:`transcode_and_descramble` | `ImageBridge.transcodeAndDescramble` | Pillow 解码 + 分条还原 |
| :func:`decode_image_bytes` | 同上（临时文件往返） | 同上 |
| :func:`image_size` | `ImageBridge.imageSize` | Pillow `.size` |
| :func:`open_path` | `FileOpener.open` | `os.startfile` |
| :func:`move_to_recycle_bin` | `RecycleBin.moveToTrash` | Windows `SHFileOperationW` |
| :func:`keystore_encrypt` / :func:`keystore_decrypt` | `AccountCrypto`（Android Keystore） | 无（桌面用内置密钥方案） |

**桌面回退**：检测不到 Chaquopy 的 ``java`` 模块时，上述函数退化为等价的本地实现
（Pillow / os），使 core 模块可以在桌面直接跑通 —— 迁移期用它做链路自测，
不必每次都上设备。
"""

import io
import os
import subprocess
import sys
import tempfile

_android = None


def is_android():
    """是否运行在 Chaquopy 环境（有 ``java`` 模块即为安卓）。"""
    global _android
    if _android is None:
        try:
            import java  # noqa: F401
            _android = True
        except ImportError:
            _android = False
    return _android


# ---------------------------------------------------------------------------
# 图片：解码 + 解扰
# ---------------------------------------------------------------------------

def transcode_and_descramble(src_path, dst_path, quality, num) -> bool:
    """把 ``src_path`` 的图片（任意安卓可解码格式，含 WebP）以 JPEG 写入 ``dst_path``；
    ``num`` > 0 时先按 jmcomic 的分条规则还原打乱的图片。"""
    if is_android():
        from java import jclass
        bridge = jclass("com.j2pmobile.android.ImageBridge")
        return bool(bridge.transcodeAndDescramble(src_path, dst_path, int(quality), int(num)))
    return _pil_transcode(src_path, dst_path, quality, num)


def image_size(data):
    """读出图片字节的像素尺寸；读不出来时给一个竖版兜底尺寸。"""
    if is_android():
        path = _write_temp(data)
        try:
            from java import jclass
            size = jclass("com.j2pmobile.android.ImageBridge").imageSize(path)
            values = list(size) if size is not None else []
            if len(values) == 2 and int(values[0]) > 0:
                return int(values[0]), int(values[1])
        except Exception:
            pass
        finally:
            _remove_quietly(path)
        return (1000, 1400)
    try:
        from PIL import Image
        with Image.open(io.BytesIO(data)) as image:
            return image.size
    except Exception:
        return (1000, 1400)


def decode_image_bytes(data, num, quality):
    """把站点图片字节还原成可显示的字节，返回 ``{"data": 字节, "size": (宽, 高)}``。

    ``num == 0``（未打乱）时直接返回原始字节，不重新编码。
    """
    if not num:
        return {"data": data, "size": image_size(data)}
    src = _write_temp(data)
    dst = src + ".jpg"
    try:
        if not transcode_and_descramble(src, dst, quality, num):
            raise ValueError("图片解码失败")
        with open(dst, "rb") as f:
            out = f.read()
    finally:
        _remove_quietly(src)
        _remove_quietly(dst)
    return {"data": out, "size": image_size(out)}


def _pil_transcode(src_path, dst_path, quality, num) -> bool:
    """桌面回退：Pillow 解码 + 分条还原后再存 JPEG（与 jmcomic 的规则一致）。"""
    try:
        from PIL import Image
        with Image.open(src_path) as source:
            image = source.convert("RGB")
        if num and num > 0:
            image = _pil_descramble(image, num)
        image.save(dst_path, "JPEG", quality=int(quality))
        return True
    except Exception:
        return False


def _pil_descramble(source, num):
    """按 jmcomic 的分条规则还原（与 ``JmImageTool.decode_and_save`` 一致）。"""
    from PIL import Image
    width, height = source.size
    target = Image.new("RGB", (width, height))
    over = height % num
    for i in range(num):
        move = height // num
        y_src = height - (move * (i + 1)) - over
        y_dst = move * i
        if i == 0:
            move += over
        else:
            y_dst += over
        target.paste(source.crop((0, y_src, width, y_src + move)),
                     (0, y_dst, width, y_dst + move))
    return target


def _write_temp(data):
    handle, path = tempfile.mkstemp(suffix=".img")
    with os.fdopen(handle, "wb") as f:
        f.write(data)
    return path


def _remove_quietly(path):
    try:
        os.remove(path)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# 打开文件 / 回收站
# ---------------------------------------------------------------------------

def open_path(path) -> bool:
    """用系统默认方式打开文件或文件夹。"""
    if is_android():
        from java import jclass
        return bool(jclass("com.j2pmobile.android.FileOpener").open(path))
    try:
        if sys.platform == "win32":
            os.startfile(path)          # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
        return True
    except Exception:
        return False


def move_to_recycle_bin(paths):
    """把文件 / 文件夹移入回收站，返回**失败**的路径列表（成功则为空列表）。"""
    targets = [os.path.abspath(path) for path in paths if path]
    if not targets:
        return []
    if is_android():
        from java import jclass
        bridge = jclass("com.j2pmobile.android.RecycleBin")
        return [path for path in targets if not bridge.moveToTrash(path)]
    return _local_trash(targets)


# ---------------------------------------------------------------------------
# 账号凭据加密（Android Keystore）
# ---------------------------------------------------------------------------

def keystore_encrypt(plain_base64) -> str:
    """用系统密钥库的硬件密钥加密（入参/出参都是 base64 字符串）；不可用时返回空串。

    返回空串表示「本环境没有 Keystore」（桌面），调用方应退回内置密钥方案。
    """
    if is_android():
        try:
            from java import jclass
            return str(jclass("com.j2pmobile.android.AccountCrypto").encryptB64(plain_base64))
        except Exception:
            return ""
    return ""


def keystore_decrypt(token_base64) -> str:
    """解密 keystore_encrypt 的产物；密钥不符或密文被改动时返回空串。"""
    if is_android():
        try:
            from java import jclass
            return str(jclass("com.j2pmobile.android.AccountCrypto").decryptB64(token_base64))
        except Exception:
            return ""
    return ""


def _local_trash(targets):
    """桌面回退：移进配置目录旁的 ``.trash``（仅用于开发期自测）。"""
    from core.config import config_dir
    import shutil
    import time
    trash = os.path.join(config_dir(), ".trash")
    os.makedirs(trash, exist_ok=True)
    failed = []
    for path in targets:
        if not os.path.exists(path):
            continue
        stamp = time.strftime("%Y%m%d_%H%M%S")
        target = os.path.join(trash, "%s_%s" % (stamp, os.path.basename(path)))
        try:
            shutil.move(path, target)
        except Exception:
            failed.append(path)
    return failed