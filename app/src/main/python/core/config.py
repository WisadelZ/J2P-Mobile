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
"""配置层：路径解析、conf.yml 的读取 / 保存 / 导入 / 导出。

本模块不依赖 UI 框架，可单独测试。

安卓端改造（第 1 步）：程序数据目录与配置目录不再由 ``sys.frozen`` / ``__file__``
推导，而是由运行环境在启动时调用 :func:`set_dirs` 注入。未注入时回退到桌面源码
方式，便于在桌面直接调试本模块。
"""

import copy
import os
import sys

import yaml

CONF_FILENAME = "conf.yml"
CONFIG_DIRNAME = "config"

CONF_HEADER = """# J2P Mobile 配置文件
# 界面中的任何修改都会自动同步保存到本文件；也可手动编辑后重启程序生效。
# 相对路径基于应用数据目录解析。

"""

# 程序内兜底默认配置：磁盘配置缺失字段时以此补齐
DEFAULT_CONF_TEXT = """
version: v2.4.4-fix.1
app:
  download_dir: ./download
  to_pdf: true
  thread_image: 30
  thread_photo: 16
  task_concurrency: 2
  theme_mode: dark
  language: zh_cn
  auto_login: false
  update_channel: stable
  auto_update: false
  preview_pages: true
mail:
  enable: false
  server: smtp.qq.com
  port: 465
  sender: ''
  password: ''
  receiver: ''
  subject: downloaded comic
  body: jmcomic download successfully.
option:
  log: true
  download:
    image:
      suffix: .jpg
  dir_rule:
    base_dir: ./download
  plugins:
    after_photo:
      - plugin: jm2pdf_meta_pdf
        kwargs:
          pdf_dir: ./download
          filename_rule: Pname
"""

# 界面支持的取值
THEME_MODES = ("light", "dark", "system")

# 账号密码改由 core.account 加密保存，conf.yml 里不再保留（旧配置写盘时一并剔除）
_CREDENTIAL_KEYS = ("username", "password")

# 桌面回退用：core/config.py -> 项目根目录
_PACKAGE_DIR = os.path.dirname(os.path.abspath(__file__))
_SOURCE_ROOT = os.path.dirname(_PACKAGE_DIR)

# 运行时注入的目录（安卓端）
_BASE_DIR = None
_CONFIG_DIR = None


def set_dirs(base_dir=None, config_dir=None):
    """由运行环境注入目录（安卓端在 App 启动时调用一次）。

    :param base_dir: 程序数据目录，用于解析配置里的相对路径
                     （安卓端为应用外部私有目录 ``getExternalFilesDir``）
    :param config_dir: 运行态配置目录，conf.yml / account.dat / queue.json 所在处
                       （安卓端为应用内部私有目录 ``filesDir/config``）
    """
    global _BASE_DIR, _CONFIG_DIR
    if base_dir:
        _BASE_DIR = os.path.abspath(base_dir)
    if config_dir:
        _CONFIG_DIR = os.path.abspath(config_dir)


def app_dir():
    """程序数据目录：安卓端为注入值；桌面源码运行时为项目根目录。"""
    if _BASE_DIR:
        return _BASE_DIR
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return _SOURCE_ROOT


def bundle_dir():
    """打包资源目录（桌面 onefile 模式下为解压临时目录）。"""
    return getattr(sys, "_MEIPASS", app_dir())


def config_dir():
    """运行态配置目录：conf.yml / account.dat / queue.json 所在处，必要时创建。"""
    path = _CONFIG_DIR if _CONFIG_DIR else os.path.join(app_dir(), CONFIG_DIRNAME)
    try:
        os.makedirs(path, exist_ok=True)
    except OSError:
        pass
    return path


def conf_path():
    return os.path.join(config_dir(), CONF_FILENAME)


def resolve_path(path):
    """把配置中的相对路径解析为基于程序数据目录的绝对路径。"""
    path = (path or "").strip()
    if not path:
        path = "download"
    if not os.path.isabs(path):
        path = os.path.join(app_dir(), path)
    return os.path.normpath(path)


def _deep_merge(base, override):
    out = dict(base)
    for key, value in (override or {}).items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def _dump_conf(conf):
    return CONF_HEADER + yaml.safe_dump(
        conf, allow_unicode=True, sort_keys=False, default_flow_style=False)


def _strip_credentials(data):
    """剔除 app 段里的账号密码，避免旧配置或导入的配置把它们明文写回磁盘。"""
    app_conf = data.get("app")
    if isinstance(app_conf, dict):
        for key in _CREDENTIAL_KEYS:
            app_conf.pop(key, None)
    return data


def ensure_conf_file():
    """确保 conf.yml 存在；首次运行时从打包资源或默认模板生成。"""
    path = conf_path()
    if os.path.isfile(path):
        return path
    text = None
    bundled = os.path.join(bundle_dir(), CONFIG_DIRNAME, CONF_FILENAME)
    if os.path.isfile(bundled):
        try:
            with open(bundled, "r", encoding="utf-8") as f:
                text = f.read()
        except OSError:
            text = None
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(text if text else _dump_conf(yaml.safe_load(DEFAULT_CONF_TEXT)))
    except OSError:
        pass
    return path


def load_conf():
    """读取配置并与默认值深度合并，保证调用方拿到的字段完整。"""
    defaults = yaml.safe_load(DEFAULT_CONF_TEXT)
    path = ensure_conf_file()
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    return _deep_merge(defaults, data)


def save_conf(conf):
    """写回 conf.yml，并始终把 version 更新为当前程序版本。"""
    from core.constants import APP_VERSION

    data = _strip_credentials(copy.deepcopy(conf))
    data["version"] = APP_VERSION
    with open(conf_path(), "w", encoding="utf-8") as f:
        f.write(_dump_conf(data))


def update_conf(partial):
    """把一段局部配置深度合并进当前配置并保存，返回合并后的完整配置。

    供界面按字段改配置使用（例如只改 ``app.language``）。
    """
    merged = _deep_merge(load_conf(), partial or {})
    save_conf(merged)
    return load_conf()


def export_conf(conf, target_path):
    """把当前配置写到用户指定的文件（用于备份 / 迁移）。"""
    from core.constants import APP_VERSION

    data = _strip_credentials(copy.deepcopy(conf))
    data["version"] = APP_VERSION
    with open(target_path, "w", encoding="utf-8") as f:
        f.write(_dump_conf(data))


def read_conf_file(source_path):
    """读取并校验一个外部配置文件，返回补齐默认值后的配置字典。

    校验失败时抛出 ValueError。
    """
    with open(source_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict) or not data:
        raise ValueError("invalid or empty config file")
    defaults = yaml.safe_load(DEFAULT_CONF_TEXT)
    return _deep_merge(defaults, data)