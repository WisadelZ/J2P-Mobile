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
"""账号层：登录信息的本地加密存储与运行时凭据。

登录信息（账号 / 密码 / 昵称 / 头像地址）统一加密后写入配置目录下的单个文件
（account.dat），磁盘上与 conf.yml 里都不出现任何明文；密钥由内置常量结合本机
标识派生，文件拷到其它机器上无法解密。

文件解密后的内容是一个「账号记录列表」，以支持多账号；列表中被标记
``current=True`` 的那条即当前登录账号。运行时的账号 / 密码只留在内存里
（:data:`_credentials`），供下载层登录使用。

安卓端改造（第 3 步）：机器因子不再取「主机名 + 系统用户 + 架构 + 系统名」
（在安卓上这些字段要么为空、要么恒为固定值，起不到绑定作用），改为**首次运行时
生成一个随机 ID、存进配置目录**（``device.id``）。语义与桌面一致 —— 账号文件与
该 ID 一起绑定本机安装，拷到别处无法解密。
"""

import base64
import hashlib
import json
import os
import uuid

from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes

from core.config import config_dir

ACCOUNT_FILENAME = "account.dat"
# 本机 / 本次安装的随机标识（首次使用时生成）
INSTALL_ID_FILENAME = "device.id"

# 文件头：标记加密格式与版本，解密前先校验
_MAGIC = b"JM2PDF-ACCOUNT-V1"
# 安卓端 V2 格式：文件头之后是 Android Keystore 硬件密钥加密的 base64 密文
_MAGIC_V2 = b"J2P-MOBILE-ACCOUNT-V2\n"
# 内置主密钥：与机器标识一起参与密钥派生，本身不出现在磁盘上
_APP_SECRET = "jm2pdf::account::v1::3f9a1c7e5b2d48a6"
# 密钥派生与分组加密参数
_KDF_ROUNDS = 200_000
_SALT_SIZE = 16
_NONCE_SIZE = 12
_TAG_SIZE = 16
_KEY_SIZE = 32

# 运行时凭据：只在内存里，供下载层登录使用
_credentials = {"username": "", "password": ""}


def account_path():
    """账号文件路径：config/ 目录下（与 conf.yml 同级）。"""
    return os.path.join(config_dir(), ACCOUNT_FILENAME)


def install_id_path():
    return os.path.join(config_dir(), INSTALL_ID_FILENAME)


def _machine_factor():
    """本机标识：让加密文件绑定本机，换机器或换安装后无法解密。"""
    path = install_id_path()
    try:
        with open(path, "r", encoding="utf-8") as f:
            value = f.read().strip()
        if value:
            return hashlib.sha256(value.encode("utf-8")).digest()
    except OSError:
        pass
    value = uuid.uuid4().hex
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(value)
        os.replace(tmp, path)
    except OSError:
        pass
    return hashlib.sha256(value.encode("utf-8")).digest()


def _derive_key(salt):
    material = _APP_SECRET.encode("utf-8") + _machine_factor()
    return hashlib.pbkdf2_hmac("sha256", material, salt, _KDF_ROUNDS, dklen=_KEY_SIZE)


def _encrypt(payload):
    """明文 JSON -> 文件头 + 盐 + 随机数 + 认证标签 + 密文。"""
    salt = get_random_bytes(_SALT_SIZE)
    cipher = AES.new(_derive_key(salt), AES.MODE_GCM, nonce=get_random_bytes(_NONCE_SIZE))
    ciphertext, tag = cipher.encrypt_and_digest(
        json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    return _MAGIC + salt + cipher.nonce + tag + ciphertext


def _decrypt(raw):
    """还原 _encrypt 的产物；密钥不符或内容被改动时抛异常。"""
    offset = len(_MAGIC) + _SALT_SIZE + _NONCE_SIZE
    salt = raw[len(_MAGIC):len(_MAGIC) + _SALT_SIZE]
    nonce = raw[len(_MAGIC) + _SALT_SIZE:offset]
    tag = raw[offset:offset + _TAG_SIZE]
    cipher = AES.new(_derive_key(salt), AES.MODE_GCM, nonce=nonce)
    return json.loads(cipher.decrypt_and_verify(raw[offset + _TAG_SIZE:], tag).decode("utf-8"))


def _seal(records):
    """把账号列表加密成落盘字节。

    安卓端优先用 **Android Keystore 的硬件密钥**加密（V2 格式）：密钥在系统密钥库内生成、
    不可导出，也不依赖任何写在源码里的常量；桌面端或 Keystore 不可用时，退回下面的
    内置密钥派生方案（V1 格式）。
    """
    from core import platform_bridge
    plain = base64.b64encode(
        json.dumps(records, ensure_ascii=False).encode("utf-8")).decode("ascii")
    token = platform_bridge.keystore_encrypt(plain)
    if token:
        return _MAGIC_V2 + token.encode("ascii")
    return _encrypt(records)


def _read_payload():
    """读取并解密账号列表；文件不存在、损坏或解不开（换机/换安装）时返回 []。"""
    try:
        with open(account_path(), "rb") as f:
            raw = f.read()
    except OSError:
        return []
    try:
        if raw.startswith(_MAGIC_V2):
            from core import platform_bridge
            plain = platform_bridge.keystore_decrypt(raw[len(_MAGIC_V2):].decode("ascii"))
            if not plain:
                return []
            data = json.loads(base64.b64decode(plain).decode("utf-8"))
        elif raw.startswith(_MAGIC):
            data = _decrypt(raw)
        else:
            return []
    except Exception:
        return []
    # 旧版本只存单个账号（dict）：迁移成列表，并把它标记为当前账号
    if isinstance(data, dict):
        data = [dict(data, current=True)]
    if not isinstance(data, list):
        return []
    return [record for record in data if isinstance(record, dict)]


def _write_payload(records):
    """加密写入账号列表；列表为空时直接删掉文件，不在磁盘上留空壳。

    先写临时文件再原子替换：直接以 ``wb`` 覆盖会先截断文件，若此刻另一个
    线程正在读取（例如界面重建时读账号列表），就会读到空文件而误判为「没有
    已保存账号」。
    """
    if not records:
        _remove_file()
        return
    path = account_path()
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(_seal(records))
    os.replace(tmp, path)


def _remove_file():
    try:
        os.remove(account_path())
    except FileNotFoundError:
        pass


def _same_username(record, username):
    return (record.get("username") or "").strip() == username


def load_accounts():
    """全部已保存的账号记录（按加入顺序），供登录页列表展示。"""
    return _read_payload()


def load_account():
    """当前登录账号的记录；没有（或文件损坏）时返回 None。"""
    for record in _read_payload():
        if record.get("current"):
            return record
    return None


def get_account(username):
    """按用户名取一条已保存的账号记录；不存在时返回 None。"""
    username = (username or "").strip()
    for record in _read_payload():
        if _same_username(record, username):
            return record
    return None


def save_account(data):
    """把一个账号写入列表并标记为当前账号，同时同步运行时凭据。

    同名的旧记录就地替换（保持原有顺序），不存在则追加到末尾。
    """
    username = (data.get("username") or "").strip()
    record = dict(data)
    record["username"] = username
    records = _read_payload()
    for item in records:
        item["current"] = False
    record["current"] = True
    for index, item in enumerate(records):
        if _same_username(item, username):
            records[index] = record
            break
    else:
        records.append(record)
    _write_payload(records)
    set_credentials(username, record.get("password"))


def remove_account(username):
    """清除指定账号：从列表中删掉它的全部凭证；账号不存在时返回 False。

    删掉的若正是当前账号，则同时清空运行时凭据。
    """
    username = (username or "").strip()
    records = _read_payload()
    target = next((record for record in records if _same_username(record, username)), None)
    if target is None:
        return False
    remaining = [record for record in records if not _same_username(record, username)]
    _write_payload(remaining)
    if target.get("current"):
        set_credentials("", "")
    return True


def clear_all_accounts():
    """清除全部登录：删掉整个账号文件并清空运行时凭据。"""
    _remove_file()
    set_credentials("", "")


def remove_current_account():
    """退出当前账号：只删掉当前账号的凭证，其它已保存账号原样保留。"""
    records = _read_payload()
    remaining = [record for record in records if not record.get("current")]
    if len(remaining) != len(records):
        _write_payload(remaining)
    set_credentials("", "")


def set_credentials(username, password):
    _credentials["username"] = (username or "").strip()
    _credentials["password"] = password or ""


def get_credentials():
    """下载层登录用的 (账号, 密码)；未登录时均为空串。"""
    return _credentials["username"], _credentials["password"]


def is_logged_in():
    """是否已有可用的登录凭据（用于「必要时才用登录态」的判断）。"""
    username, password = get_credentials()
    return bool(username and password)