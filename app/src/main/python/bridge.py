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
"""Kotlin ↔ Python 的统一调用入口（第 1 步桥接层）。

调用方向：

- **Kotlin → Python**：Kotlin 侧统一走
  ``Python.getInstance().getModule("bridge").callAttr(func, ...)``（见 ``PythonBridge``）。
- **Python → Kotlin**：日志经 ``java.jclass("com.j2pmobile.android.LogBridge")`` 回流
  （见 :func:`core.logging_bridge.install_kotlin_sink`）。

返回约定：本模块每个函数都返回 **JSON 字符串**，结构固定为

    成功: {"ok": true, ...}
    失败: {"ok": false, "error": "...", "traceback": "..."}

这样 Kotlin 侧用 ``org.json`` 解析即可，无需处理 Python 异常对象。
"""

import base64
import json
import logging
import os
import shutil
import sys
import threading
import time
import traceback
import uuid

import core.config as config
from core import constants, logging_bridge

_logger = logging.getLogger("jm2pdf")


def _ok(**kwargs):
    payload = {"ok": True}
    payload.update(kwargs)
    return json.dumps(payload, ensure_ascii=False)


def _fail(exc):
    return json.dumps({
        "ok": False,
        "error": "%s: %s" % (type(exc).__name__, exc),
        "traceback": traceback.format_exc(),
    }, ensure_ascii=False)


def _remove_quietly(path):
    """删掉一个临时文件，失败不理会（清理动作不该把主流程带崩）。"""
    try:
        os.remove(path)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Python 侧少量「用户可见日志文案」的本地化
#
# 界面文案已整体迁到 Kotlin 资源；只有 TaskQueue / send_mail 会自己产出的十几条
# 日志行留在 Python 侧，这里给它们配一份三语简表（键与桌面版 utils/i18n.py 同名）。
# 语言取自 conf.yml 并缓存，避免每条日志都解析一次 YAML。
# ---------------------------------------------------------------------------

_LOG_TEXTS = {
    "zh_cn": {
        "log_download_dir": "下载目录：{path}",
        "log_task_start": "开始下载 [{id}] {name}",
        "log_task_done": "已完成 [{id}]：{pdfs} 个 PDF",
        "log_download_failed": "下载失败 [{id}]：{error}",
        "log_retry_with_login": "以下本子需要登录才能查看，正用登录状态重试：{ids}",
        "log_cancel_cleaned": "已取消 [{id}]，并清理本任务未完成的文件：{images} 张图片、{pdfs} 个 PDF（已移入回收站）",
        "log_cancel_kept_folder": "以下文件夹里还有本任务没写过、属于既有下载的文件，已保留：{path}",
        "log_cancel_clean_failed": "清理取消任务的未完成文件失败（可到资源管理器手动删除）：{error}",
        "log_sending_mail": "正在发送邮件...",
        "log_mail_sent": "邮件发送成功！",
        "log_mail_failed": "邮件发送失败：{error}",
        "log_attach_added": "已添加附件：{name}",
        "log_attach_failed": "警告：附件 {path} 读取失败，已跳过（{error}）",
        "log_queue_restored": "已恢复 {count} 个未完成任务，可在任务中心继续",
        "log_search_result": "搜索结果：{text}",
    },
    "zh_tw": {
        "log_download_dir": "下載目錄：{path}",
        "log_task_start": "開始下載 [{id}] {name}",
        "log_task_done": "已完成 [{id}]：{pdfs} 個 PDF",
        "log_download_failed": "下載失敗 [{id}]：{error}",
        "log_retry_with_login": "以下本子需要登入才能查看，正用登入狀態重試：{ids}",
        "log_cancel_cleaned": "已取消 [{id}]，並清理本任務未完成的檔案：{images} 張圖片、{pdfs} 個 PDF（已移入資源回收筒）",
        "log_cancel_kept_folder": "以下資料夾裡還有本任務沒寫過、屬於既有下載的檔案，已保留：{path}",
        "log_cancel_clean_failed": "清理取消任務的未完成檔案失敗（可到資源管理器手動刪除）：{error}",
        "log_sending_mail": "正在傳送郵件...",
        "log_mail_sent": "郵件傳送成功！",
        "log_mail_failed": "郵件傳送失敗：{error}",
        "log_attach_added": "已加入附件：{name}",
        "log_attach_failed": "警告：附件 {path} 讀取失敗，已跳過（{error}）",
        "log_queue_restored": "已恢復 {count} 個未完成任務，可在任務中心繼續",
        "log_search_result": "搜尋結果：{text}",
    },
    "en": {
        "log_download_dir": "Download directory: {path}",
        "log_task_start": "Start downloading [{id}] {name}",
        "log_task_done": "Finished [{id}]: {pdfs} PDF(s)",
        "log_download_failed": "Download failed [{id}]: {error}",
        "log_retry_with_login": "These comics need sign-in, retrying with your account: {ids}",
        "log_cancel_cleaned": "Canceled [{id}] and cleaned this task's unfinished files: {images} image(s), {pdfs} PDF(s) (moved to Recycle Bin)",
        "log_cancel_kept_folder": "Kept a folder that still holds files not written by this task (previously downloaded): {path}",
        "log_cancel_clean_failed": "Failed to clean unfinished files of the canceled task (delete them manually in the Explorer): {error}",
        "log_sending_mail": "Sending email...",
        "log_mail_sent": "Email sent successfully!",
        "log_mail_failed": "Failed to send email: {error}",
        "log_attach_added": "Attached: {name}",
        "log_attach_failed": "Warning: failed to read attachment {path}, skipped ({error})",
        "log_queue_restored": "Restored {count} unfinished task(s); continue them in the Task Center",
        "log_search_result": "Search result: {text}",
    },
}

_language = None


def _lang():
    """当前界面语言（取自 conf.yml，带缓存）。"""
    global _language
    if _language is None:
        try:
            _language = str((config.load_conf().get("app") or {}).get("language") or "zh_cn")
        except Exception:
            _language = "zh_cn"
    return _language


def _t(key, **kwargs):
    """取一条本地化的 Python 侧日志文案。"""
    table = _LOG_TEXTS.get(_lang()) or _LOG_TEXTS["en"]
    template = table.get(key) or _LOG_TEXTS["en"].get(key) or key
    try:
        return template.format(**kwargs)
    except Exception:
        return template


def init(base_dir, config_dir, log_level="INFO"):
    """应用启动时调用一次：注入目录、接通日志、准备配置文件、恢复下载队列。"""
    global _language
    try:
        config.set_dirs(base_dir=base_dir, config_dir=config_dir)
        level = getattr(logging, str(log_level).upper(), logging.INFO)
        logging_bridge.install_kotlin_sink(level)
        config.ensure_conf_file()
        _language = None

        python_version = sys.version.split()[0]
        _logger.info("Python %s 就绪", python_version)
        _logger.info("配置目录：%s", config.config_dir())
        # 队列在首次用到时惰性创建（见 _queue_instance），这里提前建好以便启动即恢复
        _queue_instance()
        # 把已保存账号的凭据装进内存：否则重启后界面显示「已登录」，实际请求却是游客
        _restore_account()
        return _ok(version=constants.APP_VERSION,
                   python=python_version,
                   config_path=config.conf_path(),
                   config=config.load_conf())
    except Exception as exc:
        return _fail(exc)


def load_config():
    """读取当前配置。"""
    try:
        return _ok(config=config.load_conf(), config_path=config.conf_path())
    except Exception as exc:
        return _fail(exc)


def update_config(partial_json):
    """把局部配置（JSON 对象字符串）深度合并进 conf.yml 并保存。"""
    global _language
    try:
        partial = json.loads(partial_json) if partial_json else {}
        if not isinstance(partial, dict):
            raise ValueError("partial config must be a JSON object")
        conf = config.update_conf(partial)
        _language = None            # 语言可能变了，让日志文案重新取
        _logger.info("配置已保存：%s", config.conf_path())
        return _ok(config=conf, config_path=config.conf_path())
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 探索页（检索）
# ---------------------------------------------------------------------------

_search_client = None
_search_epoch = -1              # 建上面这个客户端时的加载源代次（切源后重建）
_search_cache = {}              # (mode, keyword, sort, time, lib_page) -> (items, total)
_SEARCH_CACHE_MAX = 2           # 与桌面版一致：只留最近两个库页
_SEARCH_PER_UI_PAGE = 20        # 界面每页条数（库每页 80 条）


def _search_client_instance():
    """复用同一个检索客户端。

    每次 ``new_jm_client`` 都要先请求一次 ``/setting``（还要维护域名与 cookies），
    逐页新建的代价不小；检索只读，复用一个是安全的。切换加载源后按代次重建，
    否则新请求仍会走旧域名。
    """
    global _search_client, _search_epoch
    from core import sources
    from core import explore as explore_mod
    if _search_client is None or _search_epoch != sources.epoch():
        _search_client = explore_mod.new_client(config.load_conf())
        _search_epoch = sources.epoch()
    return _search_client


def explore_search(mode, keyword, ui_page, sort, time):
    """探索页检索：按「库页 80 条 / 界面页 20 条」取一页。

    **封面只回传 URL**，由 Kotlin 侧原生取图并缓存 —— 图片处理优先原生，
    也避免把图片字节来回跨 JNI。
    """
    try:
        from core import explore as explore_mod

        ui_page = max(1, int(ui_page))
        lib_page_size = explore_mod.LIB_PAGE_SIZE
        lib_page = (ui_page - 1) * _SEARCH_PER_UI_PAGE // lib_page_size + 1

        key = (str(mode), str(keyword), str(sort), str(time), lib_page)
        cached = _search_cache.get(key)
        if cached is None:
            page_obj = explore_mod.search(_search_client_instance(), mode, keyword,
                                          lib_page, sort, time)
            items = explore_mod.to_items(page_obj)
            total = int(getattr(page_obj, "total", 0) or 0)
            _search_cache[key] = (items, total)
            while len(_search_cache) > _SEARCH_CACHE_MAX:
                _search_cache.pop(next(iter(_search_cache)))
        else:
            items, total = cached

        offset = (ui_page - 1) % (lib_page_size // _SEARCH_PER_UI_PAGE) * _SEARCH_PER_UI_PAGE
        result = [_item_json(item) for item in items[offset:offset + _SEARCH_PER_UI_PAGE]]
        return _ok(items=result, total=total, ui_page=ui_page)
    except Exception as exc:
        return _fail(exc)





# ---------------------------------------------------------------------------
# 设置页：配置导入导出 / 清缓存 / 启动自动签到（第 4 步切片 4b）
# ---------------------------------------------------------------------------

def export_config_text():
    """把当前配置序列化成 YAML 文本（供安卓端写进 SAF 选定的文件）。

    安卓端拿到的是 ``content://`` URI，Python 打不开，所以由 Kotlin 负责读写文件、
    这里只产出 / 消费文本；桌面端仍可直接用 ``core.config.export_conf``。
    """
    try:
        from core import config as config_mod
        tmp = os.path.join(config_mod.config_dir(), "export.tmp.yml")
        try:
            config_mod.export_conf(config_mod.load_conf(), tmp)
            with open(tmp, "r", encoding="utf-8") as f:
                return _ok(text=f.read())
        finally:
            _remove_quietly(tmp)
    except Exception as exc:
        return _fail(exc)


def import_config_text(text):
    """从 YAML 文本导入配置并覆盖 conf.yml。

    只影响配置本身 —— 不改登录态、不动下载队列，与桌面版一致。
    """
    global _language
    try:
        from core import config as config_mod
        if not (text or "").strip():
            raise ValueError("导入内容为空")
        tmp = os.path.join(config_mod.config_dir(), "import.tmp.yml")
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(text)
            imported = config_mod.read_conf_file(tmp)
        finally:
            _remove_quietly(tmp)
        config_mod.save_conf(imported)
        _language = None            # 语言可能变了，让日志文案重新取
        return _ok(config=config_mod.load_conf())
    except Exception as exc:
        return _fail(exc)


def clear_cache():
    """清掉运行期内存缓存（检索页缓存 + 详情预览图字节）。

    不影响登录状态、配置与下载任务（与桌面版 `clear_cache` 的语义一致）；
    Kotlin 侧的封面 / 头像缓存由界面自己清。
    """
    try:
        _search_cache.clear()
        _preview_cache.clear()
        return _ok()
    except Exception as exc:
        return _fail(exc)


def auto_checkin():
    """启动时的自动签到：设置里开了「自动登录并签到」且本机已登录时才做。

    今天已签到、未登录、请求失败都**静默**（回 `signed=False`），只有真正签到成功
    才 `signed=True`。安卓端不弹对话框 —— 启动瞬间没有界面上下文，硬弹一个反而突兀；
    用户可在账号页点「签到」看到「今天已经签到过了」。
    """
    try:
        if not bool((config.load_conf().get("app") or {}).get("auto_login")):
            return _ok(signed=False)
        from core import checkin
        result = checkin.check_in(config.load_conf())
        if result.get("code") != checkin.CODE_SUCCESS:
            return _ok(signed=False)
        _logger.info("自动签到成功（%s）", result.get("msg") or "")
        return _ok(signed=True, result=result)
    except Exception:
        return _ok(signed=False)


# ---------------------------------------------------------------------------
# 本子 ID 搜索（下载页「搜索 ID」）
# ---------------------------------------------------------------------------

def album_search(album_id):
    """按 ID 取本子详情：返回名称、页数、章节数、标签、官网链接与封面 URL。

    **封面只回传 URL**（由 Kotlin 原生取图），与探索页保持一致。
    """
    try:
        aid = str(album_id or "").strip()
        if not aid:
            raise ValueError("请先输入本子 ID")
        from core.downloader import album_url, cover_url
        client = _search_client_instance()
        detail = client.get_album_detail(aid)
        tags = _extract_tags(detail)
        result = {
            "id": str(detail.id),
            "name": detail.title,
            "pages": int(getattr(detail, "page_count", 0) or 0),
            "chapters": len(detail),
            "tags": tags,
            "url": album_url(detail.id),
            "cover_url": cover_url(detail.id),
        }
        _logger.info(_t("log_search_result", text="%s %s" % (result["id"], result["name"])))
        return _ok(**result)
    except Exception as exc:
        return _fail(exc)


def _extract_tags(detail):
    """把本子的标签拼成一行短文本（最多 10 个）。"""
    try:
        return _tags_text(getattr(detail, "tags", None), limit=10)
    except Exception:
        return ""


def _tags_text(tags, limit=None):
    """把标签（列表或 ``{分组: [标签]}`` 字典）拼成一行文本。"""
    if isinstance(tags, dict):
        flat = []
        for value in tags.values():
            flat.extend(value if isinstance(value, list) else [value])
        tags = flat
    items = list(tags or [])
    if limit is not None and len(items) > limit:
        return ", ".join(str(item) for item in items[:limit]) + " ..."
    return ", ".join(str(item) for item in items)


# ---------------------------------------------------------------------------
# 本子详情页
# ---------------------------------------------------------------------------

# 预览图缓存：只留最近一个本子的几页（字节留在 Python 内存里，逐页 base64 回传，
# 避免一次调用把几 MB 塞进同一个 JSON）
_preview_cache = {}


def album_detail(album_id):
    """本子详情：标题 / 作者 / 标签 / 点赞数 / 观看数 / 页数 / 章节数 / 收藏状态 + 链接。

    **封面只回传 URL**（由 Kotlin 原生取图），与探索页、下载页的「搜索 ID」一致。
    """
    try:
        aid = str(album_id or "").strip()
        if not aid:
            raise ValueError("本子 ID 为空")
        from core.downloader import album_url, cover_url
        album = _search_client_instance().get_album_detail(aid)
        return _ok(
            id=str(album.album_id),
            title=album.name or "",
            author=", ".join(str(name) for name in (album.authors or [])),
            tags=_tags_text(album.tags),
            likes=_count_text(album.likes),
            views=_count_text(album.views),
            pages=int(getattr(album, "page_count", 0) or 0),
            chapters=len(album),
            favorited=bool(getattr(album, "is_favorite", False)),
            cover_url=cover_url(album.album_id),
            url=album_url(album.album_id),
        )
    except Exception as exc:
        return _fail(exc)


def album_preview(album_id):
    """取本子第一话的前几页预览（解码 + 解扰走平台层），缓存字节并只回传每页尺寸。

    图片字节留待 :func:`album_preview_page` 逐页取，避免一次调用传几 MB。
    """
    try:
        from core.downloader import fetch_preview_images
        aid = str(album_id or "").strip()
        if not aid:
            raise ValueError("本子 ID 为空")
        pages = fetch_preview_images(config.load_conf(), aid)
        _preview_cache.clear()
        _preview_cache[aid] = pages
        return _ok(pages=[
            {"width": int(page["size"][0]), "height": int(page["size"][1])}
            for page in pages
        ])
    except Exception as exc:
        return _fail(exc)


def album_preview_page(album_id, index):
    """回传第 index 页预览图的 base64 与尺寸（逐页取，调用方负责解码）。"""
    try:
        aid = str(album_id or "").strip()
        pages = _preview_cache.get(aid)
        if not pages:
            raise ValueError("预览尚未加载")
        page = pages[int(index)]
        return _ok(
            data=base64.b64encode(page["data"]).decode("ascii"),
            width=int(page["size"][0]),
            height=int(page["size"][1]),
        )
    except Exception as exc:
        return _fail(exc)


def favorite_folders():
    """当前登录账号的收藏夹列表（不含「全部」——那一项由界面自己补，便于本地化）。"""
    try:
        from core import favorite
        folders = favorite.fetch_folders(config.load_conf())
        return _ok(folders=[{"id": str(fid), "name": name} for fid, name in folders])
    except Exception as exc:
        return _fail(exc)


def favorite_add(album_id, folder_id="0"):
    """把本子加入收藏夹；``folder_id`` 为 "0" 表示默认收藏夹。"""
    try:
        from core import favorite
        favorite.add_to_folder(config.load_conf(), str(album_id), str(folder_id or "0"))
        return _ok()
    except Exception as exc:
        return _fail(exc)


def favorite_remove(album_id):
    """取消收藏本子。"""
    try:
        from core import favorite
        favorite.remove_from_favorites(config.load_conf(), str(album_id))
        return _ok()
    except Exception as exc:
        return _fail(exc)


def _count_text(value):
    """把观看数 / 点赞数格式化成 "200,000" 这样的纯数字文本（与桌面版一致）。"""
    units = {"k": 1000, "m": 1000000}
    text = str(value or "").strip().replace(",", "").replace(" ", "")
    if text.isdigit():
        return format(int(text), ",")
    unit = text[-1:].lower()
    if len(text) > 1 and unit in units:
        try:
            return format(int(float(text[:-1]) * units[unit]), ",")
        except ValueError:
            return ""
    return ""


# ---------------------------------------------------------------------------
# 账号（登录 / 切换 / 退出 / 账号状态 / 签到）
#
# 账号记录由 core.account 加密保存在 config/ 目录（account.dat），磁盘上不出现明文；
# 运行时凭据只留在内存里，供下载 / 收藏 / 签到使用。安卓端与桌面版的唯一差别是
# 机器因子改成了本机随机 ID（见 core/account.py 的改造说明）。
# ---------------------------------------------------------------------------

# 账号状态字段：登录响应里就有，登录时一并加密保存
_PROFILE_KEYS = ("level", "level_name", "exp", "next_level_exp", "exp_percent",
                 "favorites", "favorites_max", "coin")


def _avatar_url(photo, uid=""):
    """把头像字段规范成完整地址（域名用 jmcomic 运行时维护的那一组）。

    接口返回的头像只是文件名，挂在移动端域名的 ``/media/users/`` 下
    （网页域名 18comic.hk 上取不到）。
    """
    import jmcomic
    photo = (photo or "").strip()
    if photo.startswith(("http://", "https://")):
        return photo
    if not photo and uid:
        photo = "%s.jpg" % uid
    domains = jmcomic.JmModuleConfig.DOMAIN_API_LIST or []
    if not photo or not domains:
        return ""
    domain = str(domains[0]).rstrip("/")
    if not domain.startswith(("http://", "https://")):
        domain = "https://" + domain
    return "%s/media/users/%s" % (domain, photo)


def _account_record(username, password, profile, previous=None):
    """把登录响应的账号信息整理成待加密保存的记录（字段与桌面版一致）。"""
    if not isinstance(profile, dict):
        profile = {}
    prev = previous or {}
    uid = str(profile.get("uid") or prev.get("uid") or "")
    record = dict(prev)
    record.update({
        "username": username,
        "password": password,
        "uid": uid,
        "nickname": (profile.get("fname") or profile.get("nickname")
                     or profile.get("username") or username),
        "avatar_url": _avatar_url(profile.get("photo"), uid),
        "level": profile.get("level"),
        "level_name": profile.get("level_name"),
        "exp": profile.get("exp"),
        "next_level_exp": profile.get("nextLevelExp"),
        "exp_percent": profile.get("expPercent"),
        "favorites": profile.get("album_favorites"),
        "favorites_max": profile.get("album_favorites_max"),
        "coin": profile.get("coin"),
    })
    return record


def _public_account(record):
    """回给界面的账号资料（不含密码）。"""
    record = record or {}
    return {
        "username": record.get("username") or "",
        "nickname": record.get("nickname") or record.get("username") or "",
        "uid": str(record.get("uid") or ""),
        "avatar_url": record.get("avatar_url") or "",
        "level": record.get("level"),
        "level_name": record.get("level_name") or "",
        "exp": record.get("exp"),
        "next_level_exp": record.get("next_level_exp"),
        "exp_percent": record.get("exp_percent"),
        "favorites": record.get("favorites"),
        "favorites_max": record.get("favorites_max"),
        "coin": record.get("coin"),
    }


def _restore_account():
    """启动时把已保存账号的凭据装进内存，供下载 / 收藏 / 签到使用。

    没有这一步，重启后「记住的登录态」只在界面上显示，实际请求会被当成游客。
    """
    try:
        from core import account as account_store
        record = account_store.load_account()
        if record:
            account_store.set_credentials(record.get("username"), record.get("password"))
    except Exception:
        pass


def account_state():
    """账号页初始状态：是否已登录 + 账号资料（不含密码）+ 已保存账号的用户名列表。"""
    try:
        from core import account as account_store
        record = account_store.load_account()
        usernames = [
            (item.get("username") or "").strip()
            for item in account_store.load_accounts()
        ]
        return _ok(
            logged_in=bool(record),
            profile=_public_account(record) if record else None,
            accounts=[name for name in usernames if name],
        )
    except Exception as exc:
        return _fail(exc)


def account_login(username, password):
    """用**全新会话**登录并加密保存账号；返回账号资料。

    必须新建会话：复用旧客户端的 cookies 会被服务端当成「已登录」，
    直接返回上一个账号的信息（换账号时会串号）。
    """
    try:
        from core import account as account_store
        from core.downloader import fresh_login_client, reset_session_client
        username = str(username or "").strip()
        password = password or ""
        if not username or not password:
            raise ValueError("请输入账号和密码")
        reset_session_client()
        client = fresh_login_client(config.load_conf())
        resp = client.login(username, password)
        record = _account_record(username, password, getattr(resp, "res_data", None))
        account_store.save_account(record)
        return _ok(profile=_public_account(record))
    except Exception as exc:
        return _fail(exc)


def account_switch(username):
    """用已保存的凭据登录指定账号（切换账号）。"""
    try:
        from core import account as account_store
        record = account_store.get_account(str(username or ""))
        if not record:
            raise ValueError("未找到账号：%s" % username)
        return account_login(record.get("username"), record.get("password") or "")
    except Exception as exc:
        return _fail(exc)


def account_remove(username):
    """清除指定的已保存账号；一并告知它是不是当前账号（是的话界面要退出登录）。"""
    try:
        from core import account as account_store
        from core.downloader import reset_session_client
        username = str(username or "").strip()
        record = account_store.get_account(username)
        if record is None:
            return _ok(removed=False, was_current=False)
        was_current = bool(record.get("current"))
        account_store.remove_account(username)
        if was_current:
            reset_session_client()
        return _ok(removed=True, was_current=was_current)
    except Exception as exc:
        return _fail(exc)


def account_logout():
    """退出当前账号：只删当前账号的凭证，其它已保存账号原样保留。"""
    try:
        from core import account as account_store
        from core.downloader import reset_session_client
        account_store.remove_current_account()
        reset_session_client()
        return _ok()
    except Exception as exc:
        return _fail(exc)


def account_clear_all():
    """清除全部登录：删掉所有凭证并回到登录界面。"""
    try:
        from core import account as account_store
        from core.downloader import reset_session_client
        account_store.clear_all_accounts()
        reset_session_client()
        return _ok()
    except Exception as exc:
        return _fail(exc)


def account_refresh_profile():
    """重新登录一次取最新账号状态（等级 / 经验 / 收藏数 / J 币）。

    签到会改变 J 币与经验，签到成功后用它刷新；旧记录缺字段时也用它在启动后补一次。
    """
    try:
        from core import account as account_store
        from core.downloader import fresh_login_client
        username, password = account_store.get_credentials()
        if not (username and password):
            raise ValueError("未登录")
        resp = fresh_login_client(config.load_conf()).login(username, password)
        previous = account_store.get_account(username) or {}
        record = _account_record(username, password,
                                 getattr(resp, "res_data", None), previous=previous)
        account_store.save_account(record)
        return _ok(profile=_public_account(record))
    except Exception as exc:
        return _fail(exc)


def account_checkin():
    """执行每日签到，返回签到结果（含当月 / 连续天数与当天奖励）。"""
    try:
        from core import checkin
        return _ok(result=checkin.check_in(config.load_conf()))
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 收藏（账号页预览 + 收藏列表页）
# ---------------------------------------------------------------------------

def _item_json(item):
    """搜索 / 收藏条目：封面只回 URL，由 Kotlin 原生取图。"""
    from core.downloader import cover_url
    album_id = str(item.get("id") or "")
    try:
        cover = cover_url(album_id)
    except Exception:
        cover = ""
    return {
        "id": album_id,
        "name": item.get("name") or "",
        "author": item.get("author") or "",
        "cover_url": cover,
    }


def favorite_preview(limit=0):
    """账号页预览：「全部」收藏夹最前面的若干本（封面给 URL）。"""
    try:
        from core import favorite as favorite_mod
        conf = config.load_conf()
        if int(limit) > 0:
            items = favorite_mod.preview_items(conf, limit=int(limit))
        else:
            items = favorite_mod.preview_items(conf)
        return _ok(items=[_item_json(item) for item in items])
    except Exception as exc:
        return _fail(exc)


def favorite_page(page=1, folder_id="0"):
    """收藏列表页一页：条目 + 该账号的全部收藏夹 + 总数（收藏夹按库页一起返回）。"""
    try:
        from core import explore as explore_mod
        from core import favorite as favorite_mod
        page_obj = favorite_mod.fetch_page(config.load_conf(), int(page),
                                           str(folder_id or favorite_mod.FOLDER_ALL))
        return _ok(
            items=[_item_json(item) for item in explore_mod.to_items(page_obj)],
            folders=[{"id": str(fid), "name": name}
                     for fid, name in favorite_mod.folders_of(page_obj)],
            total=int(getattr(page_obj, "total", 0) or 0),
            page=int(page),
            page_size=int(favorite_mod.LIB_PAGE_SIZE),
        )
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 资源管理器（下载目录扫描 / 搜索 / 排序 / 元数据 / 删除）
#
# 扫描与过滤排序都在 core.library 里（纯内存，不重新读盘）；这里只做三件事：
# 把下载目录解析出来、在需要时把 PDF 元数据一起读完、把结果转成界面要的 JSON。
# ---------------------------------------------------------------------------

def library_list(keyword="", mode="all", sort_key="name", desc=None):
    """扫描下载目录，按「搜索方式 + 关键词 + 排序」返回条目。

    **需要 PDF 元数据时在同一次调用里读完**（按作者 / 标签 / 本子 ID / 全部搜索，
    或按页数排序）：桌面版是丢到后台线程分批判读、界面先显示原列表；安卓端一次
    JNI 往返更省事，界面在等待期间显示「正在读取元数据…」即可。
    """
    try:
        from core import library
        conf = config.load_conf()
        base_dir = config.resolve_path((conf.get("app") or {}).get("download_dir"))
        entries = library.scan_library(base_dir)
        info = {}
        if _library_needs_info(library, keyword, mode, sort_key):
            for entry in entries:
                pdf = entry.get("pdf") or ""
                if pdf and pdf not in info:
                    info[pdf] = library.read_pdf_info(pdf)
        visible = library.sort_entries(
            library.filter_entries(entries, keyword, mode, info), sort_key, desc, info)
        return _ok(
            dir=base_dir,
            total=len(entries),
            shown=len(visible),
            needs_metadata=bool(info),
            entries=[_library_entry_json(entry) for entry in visible],
        )
    except Exception as exc:
        return _fail(exc)


def _library_needs_info(library, keyword, mode, sort_key):
    """是否需要读 PDF 元数据（与桌面版 `explorer_page._needs_info` 一致）。"""
    if sort_key == "pages":
        return True
    return library.mode_needs_metadata(mode) and bool((keyword or "").strip())


def _library_entry_json(entry):
    """一条扫描结果：同一本漫画的 PDF 与图片文件夹归为一项。"""
    return {
        "name": entry.get("name") or "",
        "pdf": entry.get("pdf") or "",
        "folder": entry.get("folder") or "",
        "size": int(entry.get("size") or 0),
    }


def library_pdf_meta(pdf_path):
    """读一个 PDF 的漫画元数据（资源管理器的元数据面板）；没有元数据时 ``meta`` 为 null。"""
    try:
        from core import pdf_metadata
        path = str(pdf_path or "").strip()
        if not path:
            raise ValueError("路径为空")
        return _ok(meta=pdf_metadata.read_metadata(path))
    except Exception as exc:
        return _fail(exc)


def library_delete(paths_json):
    """把选中的文件 / 文件夹移入回收站（可从回收站还原）。

    删 PDF 时顺手清掉它的书签文件（同一本子还有其它 PDF 时保留）。
    """
    try:
        from core import library
        paths = json.loads(paths_json) if paths_json else []
        if not isinstance(paths, list):
            raise ValueError("paths must be a JSON array")
        paths = [str(path) for path in paths if str(path).strip()]
        if not paths:
            raise ValueError("请先勾选要删除的项目")
        # 书签要在删除前按 PDF 元数据定位（移进回收站后就读不到了）
        album_ids = _pdf_album_ids(paths)
        library.delete_to_recycle_bin(paths)
        _drop_orphan_bookmarks(album_ids)
        return _ok(count=len(paths))
    except Exception as exc:
        return _fail(exc)


def _pdf_album_ids(paths):
    """待删除路径里 PDF 对应的本子 ID（读不到元数据的忽略）。"""
    ids = set()
    try:
        from core import pdf_metadata
    except Exception:
        return ids
    for path in paths:
        if not str(path).lower().endswith(".pdf"):
            continue
        try:
            meta = pdf_metadata.read_metadata(path) or {}
            album_id = str(meta.get("album_id") or "").strip()
            if album_id:
                ids.add(album_id)
        except Exception:
            continue
    return ids


def _drop_orphan_bookmarks(album_ids):
    """PDF 删除后，下载目录里已无同一本子的 PDF 时，删掉它的书签文件。"""
    if not album_ids:
        return
    try:
        from core import pdf_metadata
        base = config.resolve_path((config.load_conf().get("app") or {}).get("download_dir"))
    except Exception:
        return
    remaining = set()
    try:
        names = os.listdir(base)
    except OSError:
        return
    for name in names:
        if not name.lower().endswith(".pdf"):
            continue
        try:
            meta = pdf_metadata.read_metadata(os.path.join(base, name)) or {}
            album_id = str(meta.get("album_id") or "").strip()
            if album_id:
                remaining.add(album_id)
        except Exception:
            continue
    for album_id in album_ids - remaining:
        safe = _safe_filename(album_id)
        if safe:
            _remove_quietly(os.path.join(base, "%s.json" % safe))


# ---------------------------------------------------------------------------
# 导入外部 PDF（资源管理器右上角的「+」）
#
# 复制进下载目录 → 分配一个新的八位本子 ID（从 10000000 起递增，删掉 PDF 也不复用）
# → 把 ID 写进 PDF 元数据（DocInfo 的 Keywords，与下载生成的 PDF 一致）。
# 因为 ID 的位置一致，后续打开该书签页时会自然以这个 ID 命名书签文件。
# ---------------------------------------------------------------------------

_IMPORT_ID_START = 10000000


def import_pdf(temp_path, display_name=""):
    """把外部 PDF 复制进下载目录并写入新的本子 ID；回落盘文件名、路径与 ID。"""
    try:
        source = str(temp_path or "").strip()
        if not source or not os.path.isfile(source):
            raise ValueError("导入文件不存在")
        if not source.lower().endswith(".pdf"):
            raise ValueError("只支持导入 PDF 文件")
        from core import pdf_metadata

        base = config.resolve_path((config.load_conf().get("app") or {}).get("download_dir"))
        os.makedirs(base, exist_ok=True)
        target = _unique_import_path(base, _import_name(display_name or os.path.basename(source)))
        shutil.copyfile(source, target)
        try:
            album_id = _next_import_id(base)
            pdf_metadata.write_import_metadata(
                target, album_id, os.path.splitext(os.path.basename(target))[0])
        except Exception:
            # 元数据写不进去就别在下载目录里留一个「没身份」的半成品
            _remove_quietly(target)
            raise
        return _ok(name=os.path.basename(target), path=target, album_id=album_id)
    except Exception as exc:
        return _fail(exc)


def _import_name(name):
    """导入文件的落盘名：去掉路径成分与非法字符，并补上 .pdf 后缀。"""
    cleaned = _safe_filename(os.path.basename(str(name or "")))
    if not cleaned:
        cleaned = "imported"
    if not cleaned.lower().endswith(".pdf"):
        cleaned += ".pdf"
    return cleaned


def _unique_import_path(base_dir, name):
    """同名时加「 (2)」「 (3)」后缀，绝不覆盖下载目录里已有的文件。"""
    stem, ext = os.path.splitext(name)
    candidate = os.path.join(base_dir, name)
    index = 2
    while os.path.exists(candidate):
        candidate = os.path.join(base_dir, "%s (%d)%s" % (stem, index, ext))
        index += 1
    return candidate


def _import_seq_path():
    """导入 ID 的计数器文件（放配置目录，不随下载目录清理而丢）。"""
    return os.path.join(config.config_dir(), "import_seq.json")


def _existing_album_ids(base_dir):
    """下载目录里现有 PDF 的本子 ID（导入前避让用，防止计数器丢失后撞号）。"""
    ids = set()
    try:
        names = os.listdir(base_dir)
    except OSError:
        return ids
    from core import pdf_metadata
    for name in names:
        if not name.lower().endswith(".pdf"):
            continue
        try:
            meta = pdf_metadata.read_metadata(os.path.join(base_dir, name)) or {}
            album_id = str(meta.get("album_id") or "").strip()
            if album_id:
                ids.add(album_id)
        except Exception:
            continue
    return ids


def _next_import_id(base_dir):
    """分配下一个八位本子 ID：从 10000000 起递增，**用过的号不再复用**。

    计数器落在配置目录（删掉 PDF 也不会回退）；即便计数器被清掉，也会避让下载目录
    里现有的 ID，尽量不撞号。
    """
    last = _IMPORT_ID_START - 1
    path = _import_seq_path()
    try:
        with open(path, "r", encoding="utf-8") as file:
            data = json.load(file)
        last = max(last, int((data or {}).get("last") or last))
    except Exception:
        pass
    used = _existing_album_ids(base_dir)
    candidate = last + 1
    while str(candidate) in used:
        candidate += 1
    try:
        with open(path, "w", encoding="utf-8") as file:
            json.dump({"last": candidate}, file)
    except Exception:
        pass
    return str(candidate)


# ---------------------------------------------------------------------------
# 加载源（禁漫移动端 API 域名节点）
#
# 测速要在后台线程里跑（单源最多 3 秒），界面只拿序号与延迟，不接触域名。
# ---------------------------------------------------------------------------


def source_state():
    """当前加载源节点的快照（只读，不联网）：节点序号 + 延迟 + 当前节点。"""
    try:
        from core import sources
        return _ok(**sources.snapshot())
    except Exception as exc:
        return _fail(exc)


def source_refresh():
    """测一次所有候选源并按延迟排序，延迟最低的设为当前节点。"""
    try:
        from core import sources
        return _ok(**sources.refresh(config.load_conf()))
    except Exception as exc:
        return _fail(exc)


def source_select(index):
    """按节点序号切换加载源（只影响之后的新请求）。"""
    try:
        from core import sources
        switched = sources.select(index)
        return _ok(switched=bool(switched), **sources.snapshot())
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 阅读器（切片 5b：浏览本地 PDF / 图片文件夹与在线本子）
#
# 会话对象（core.reader.Reader / core.online_reader.OnlineAlbum）本身带缓存与状态，
# 用句柄登记在进程内，Kotlin 侧只持有句柄；图片字节逐页 base64 回传
# （同切片 3 预览的做法，避免一次把几 MB 塞进同一个 JSON）。
# ---------------------------------------------------------------------------

_reader_sessions = {}
_reader_lock = threading.Lock()
_reader_seq = 0

# 量不到首页尺寸时的兜底（与 core.online_reader.DEFAULT_PAGE_SIZE 一致）
_READER_FALLBACK_SIZE = (1000, 1414)


def _reader_register(doc):
    """登记一个浏览会话，返回给它分配的句柄。"""
    global _reader_seq
    with _reader_lock:
        _reader_seq += 1
        handle = "r%d" % _reader_seq
        _reader_sessions[handle] = doc
    return handle


def _reader_first_size(doc):
    """首页的像素尺寸（界面先按它排出宽高比，取不到就给兜底值）。"""
    try:
        width, height = doc.size(0)
        if int(width) > 0 and int(height) > 0:
            return int(width), int(height)
    except Exception:
        pass
    return _READER_FALLBACK_SIZE


def _reader_chapter_count(doc):
    """会话里的章节数（本地 PDF / 图片文件夹都是单章；在线本子取章节列表长度）。

    只用来决定底栏「章节」按钮是否可用，不触发任何联网请求。
    """
    album = getattr(doc, "_album", None)
    if album is None:
        return 1
    try:
        return max(1, len(album))
    except Exception:
        return 1


def _reader_session(handle):
    """按句柄取浏览会话，已结束时报错（由界面提示用户）。"""
    doc = _reader_sessions.get(str(handle))
    if doc is None:
        raise ValueError("浏览会话已结束")
    return doc


def reader_open_local(path):
    """打开本地 PDF 或图片文件夹，返回会话句柄与页面信息。

    图片文件夹里没有图片时回 ``empty_folder=True``（界面据此提示「文件夹内无图片」），
    不建立会话。
    """
    try:
        from core import reader as reader_mod
        target = str(path or "").strip()
        if not target:
            raise ValueError("路径为空")
        if os.path.isdir(target) and not reader_mod.list_images(target):
            return _ok(empty_folder=True)
        doc = reader_mod.Reader(target)
        width, height = _reader_first_size(doc)
        return _ok(
            handle=_reader_register(doc),
            name=doc.name,
            album_id=str(getattr(doc, "album_id", "") or ""),
            online=False,
            page_count=int(doc.page_count),
            numbered_pages=bool(doc.numbered_pages),
            chapters=_reader_chapter_count(doc),
            width=width,
            height=height,
        )
    except Exception as exc:
        return _fail(exc)


def reader_open_online(album_id):
    """打开在线本子（按需取页，逐页解密），返回会话句柄与总页数。"""
    try:
        from core.online_reader import OnlineAlbum
        aid = str(album_id or "").strip()
        if not aid:
            raise ValueError("本子 ID 为空")
        doc = OnlineAlbum(config.load_conf(), aid)
        width, height = _reader_first_size(doc)
        return _ok(
            handle=_reader_register(doc),
            name=doc.name or "",
            album_id=str(getattr(doc, "album_id", "") or aid),
            online=True,
            page_count=int(doc.page_count),
            numbered_pages=True,
            chapters=_reader_chapter_count(doc),
            width=width,
            height=height,
        )
    except Exception as exc:
        return _fail(exc)


def reader_page(handle, index):
    """取某页图片字节（base64）+ 该页原始像素尺寸；图片文件夹另回文件名。"""
    try:
        doc = _reader_sessions.get(str(handle))
        if doc is None:
            raise ValueError("浏览会话已结束")
        position = int(index)
        data = doc.render(position)
        try:
            width, height = doc.size(position)
        except Exception:
            width, height = 0, 0
        name = ""
        if not getattr(doc, "numbered_pages", False):
            try:
                name = doc.page_name(position)
            except Exception:
                name = ""
        return _ok(
            data=base64.b64encode(data).decode("ascii"),
            width=int(width),
            height=int(height),
            name=name,
        )
    except Exception as exc:
        return _fail(exc)


def reader_close(handle):
    """结束浏览会话（释放 Python 侧的缓存与句柄）。"""
    try:
        with _reader_lock:
            _reader_sessions.pop(str(handle), None)
        return _ok()
    except Exception as exc:
        return _fail(exc)


def reader_meta(handle):
    """阅读器标题点击后的资料弹窗：三种来源统一成与资源管理器一致的一组字段。"""
    try:
        doc = _reader_session(handle)
        title = str(getattr(doc, "name", "") or "")
        album_id = str(getattr(doc, "album_id", "") or "")
        author = ""
        tags = ""
        chapter = ""
        pages = str(int(getattr(doc, "page_count", 0) or 0))
        if getattr(doc, "kind", "") == "pdf":
            from core import pdf_metadata
            meta = pdf_metadata.read_metadata(getattr(doc, "path", "")) or {}
            title = meta.get("title") or title
            album_id = str(meta.get("album_id") or album_id)
            author = meta.get("author") or ""
            tags = meta.get("tags") or ""
            pages = str(meta.get("pages") or pages)
            chapter = str(meta.get("chapter") or "")
        else:
            album = getattr(doc, "_album", None)
            if album is not None:
                title = str(getattr(album, "name", "") or title)
                author = ", ".join(str(name) for name in (getattr(album, "authors", None) or []))
                tags = _tags_text(getattr(album, "tags", None))
        return _ok(meta={
            "title": title,
            "album_id": album_id,
            "author": author,
            "tags": tags,
            "pages": pages,
            "chapter": chapter,
        })
    except Exception as exc:
        return _fail(exc)


def reader_chapters(handle):
    """章节列表（标题 + 起始全局页号 + 页数）；本地单章文件回空列表。"""
    try:
        doc = _reader_session(handle)
        if hasattr(doc, "chapters"):
            return _ok(chapters=doc.chapters())
        return _ok(chapters=[])
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 书签（在线阅读页与本地浏览共用；按本子 ID 落一个 JSON 到下载目录）
#
# 文件形如 ``<下载目录>/<本子ID>.json``：``{"album_id": "...", "items": [...]}``，
# 每条书签记录 ``id / page（0 起的页号）/ note / created / updated``（时间戳为秒）。
# 首次新增书签时才创建文件；删到没有书签时把文件一并删掉。
# ---------------------------------------------------------------------------

def _bookmark_key(doc):
    """书签文件名用的标识：优先本子 ID，取不到退回文件 / 文件夹名。"""
    key = ""
    if getattr(doc, "kind", "") == "pdf":
        try:
            from core import pdf_metadata
            meta = pdf_metadata.read_metadata(getattr(doc, "path", "")) or {}
            key = str(meta.get("album_id") or "").strip()
        except Exception:
            key = ""
    else:
        key = str(getattr(doc, "album_id", "") or "").strip()
    if not key:
        name = os.path.basename(str(getattr(doc, "path", "") or "")) \
            or str(getattr(doc, "name", "") or "")
        key = os.path.splitext(name)[0]
    return _safe_filename(key) or "bookmarks"


def _safe_filename(name):
    """把标识里的路径分隔符与非法字符去掉（避免拼出目录穿越的文件名）。"""
    return "".join(ch for ch in str(name or "") if ch not in '\\/:*?"<>|').strip()


def _bookmark_file(doc):
    """书签文件路径（下载目录下、以本子 ID 命名）。"""
    base = config.resolve_path((config.load_conf().get("app") or {}).get("download_dir"))
    return os.path.join(base, "%s.json" % _bookmark_key(doc))


def _read_bookmarks(path):
    """读书签文件；文件不存在或损坏时回空列表（不让坏文件挡住阅读）。"""
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as file:
            data = json.load(file)
    except Exception:
        return []
    if isinstance(data, dict):
        data = data.get("items")
    items = []
    for row in (data or []):
        if not isinstance(row, dict) or not row.get("id"):
            continue
        items.append({
            "id": str(row.get("id")),
            "page": int(row.get("page") or 0),
            "note": str(row.get("note") or ""),
            "created": int(row.get("created") or 0),
            "updated": int(row.get("updated") or 0),
        })
    return items


def _write_bookmarks(path, album_id, items):
    """原子写回书签文件（先写临时文件再替换，避免中途失败留下半个文件）。"""
    base_dir = os.path.dirname(path)
    if base_dir and not os.path.isdir(base_dir):
        os.makedirs(base_dir, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as file:
        json.dump({"album_id": album_id, "items": items}, file, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def bookmark_load(handle):
    """读取当前本子的全部书签（打开书签页时调用，每次都以文件为准）。"""
    try:
        doc = _reader_session(handle)
        return _ok(items=_read_bookmarks(_bookmark_file(doc)))
    except Exception as exc:
        return _fail(exc)


def bookmark_add(handle, page, note=""):
    """新增一条书签（首次会创建 ``<本子ID>.json``）。"""
    try:
        doc = _reader_session(handle)
        path = _bookmark_file(doc)
        items = _read_bookmarks(path)
        now = int(time.time())
        item = {
            "id": uuid.uuid4().hex,
            "page": max(0, int(page)),
            "note": str(note or ""),
            "created": now,
            "updated": now,
        }
        items.append(item)
        _write_bookmarks(path, str(getattr(doc, "album_id", "") or ""), items)
        return _ok(item=item)
    except Exception as exc:
        return _fail(exc)


def bookmark_update(handle, bookmark_id, note=""):
    """修改一条书签的备注（更新时间一并刷新）。"""
    try:
        doc = _reader_session(handle)
        path = _bookmark_file(doc)
        items = _read_bookmarks(path)
        now = int(time.time())
        found = False
        for item in items:
            if item["id"] == str(bookmark_id):
                item["note"] = str(note or "")
                item["updated"] = now
                found = True
        if found:
            _write_bookmarks(path, str(getattr(doc, "album_id", "") or ""), items)
        return _ok(updated=found)
    except Exception as exc:
        return _fail(exc)


def bookmark_delete(handle, bookmark_id):
    """删除一条书签；删空后把书签文件一并删掉。"""
    try:
        doc = _reader_session(handle)
        path = _bookmark_file(doc)
        items = [item for item in _read_bookmarks(path) if item["id"] != str(bookmark_id)]
        if items:
            _write_bookmarks(path, str(getattr(doc, "album_id", "") or ""), items)
        else:
            _remove_quietly(path)
        return _ok()
    except Exception as exc:
        return _fail(exc)


# ---------------------------------------------------------------------------
# 下载队列（下载页 + 任务页）
#
# 队列本体是 core.task_queue.TaskQueue（桌面版原样搬运），这里只做三件事：
#   1. 惰性建一个进程级单例，配置实时从 conf.yml 取（入队前界面已落盘）；
#   2. 入队后后台线程补任务名称 / 总页数；
#   3. 状态变化时通知 Kotlin（UI 刷新）与安卓下载前台服务（进度通知）。
# ---------------------------------------------------------------------------

_queue = None
_queue_lock = threading.Lock()

# 通知节流：Kotlin 侧 QueueBridge 会做合并，这里只管前台服务
_NOTIFY_MIN_INTERVAL = 1.0
_last_notify = 0.0
_notification_active = False


def _queue_instance():
    """取（必要时创建）进程级下载队列，创建时恢复上次未完成的任务。"""
    global _queue
    with _queue_lock:
        if _queue is None:
            from core import task_queue as tq
            queue = tq.TaskQueue(
                conf_provider=config.load_conf,
                log=lambda message: _logger.info("%s", message),
                t=_t,
                on_change=_queue_changed,
            )
            _queue = queue
            restored = queue.load()
            if restored:
                _logger.info(_t("log_queue_restored", count=restored))
            # 不在此启动调度线程：恢复出来的任务一律是「已暂停」，等用户点继续或
            # 直接入队时再启动（与桌面版一致，避免开屏就自动联网下载）
        return _queue


def _queue_changed():
    """队列变化回调（可能来自下载线程）：通知界面刷新，并同步前台服务通知。"""
    try:
        from java import jclass
        jclass("com.j2pmobile.android.QueueBridge").notifyChanged()
    except Exception:
        pass
    _sync_download_notification()


def _sync_download_notification():
    """把队列总体进度推给安卓下载前台服务（桌面无此能力，非安卓环境直接返回）。

    任何异常都不能往外冒：前台服务在后台启动受限（Android 12+ 抛
    ``ForegroundServiceStartNotAllowedException``）属于可预期情况，失败就等下一轮再试。
    """
    global _last_notify, _notification_active
    try:
        from java import jclass
    except ImportError:
        return
    try:
        queue = _queue
        if queue is None:
            return
        stats = queue.stats()
        busy = bool(stats.get("waiting") or stats.get("running"))
        service = jclass("com.j2pmobile.android.DownloadService")
        if not busy:
            if _notification_active:
                # 先清标记再停服务：停失败（后台限制）也不至于永远卡在「已激活」
                _notification_active = False
                try:
                    service.stop()
                except Exception:
                    pass
            return
        now = time.time()
        if _notification_active and now - _last_notify < _NOTIFY_MIN_INTERVAL:
            return
        _last_notify = now
        total = max(1, int(stats.get("total") or 0))
        ended = int(stats.get("done") or 0) + int(stats.get("failed") or 0) \
            + int(stats.get("canceled") or 0)
        percent = int(ended * 100 / total)
        text = "%d/%d · %d%%" % (ended, total, percent)
        try:
            if _notification_active:
                service.update(text, percent, 100)
            else:
                service.start("J2P Mobile", text, 100)
                _notification_active = True
        except Exception:
            _notification_active = False
    except Exception:
        pass


def _task_json(task):
    """把一条任务转成界面要的字段（进度 / 速度 / 剩余时间由 Python 侧算好）。"""
    progress = task.progress()
    eta = task.eta
    return {
        "id": task.id,
        "seq": task.seq,
        "album_id": task.album_id,
        "name": task.name or "",
        "status": task.status,
        "pages_done": task.pages_done,
        "pages_total": task.pages_total,
        "progress": progress,
        "byte_rate": task.byte_rate,
        "eta": eta,
        "error": task.error or "",
        "pdf_count": len(task.pdfs),
        "inflight": bool(task.inflight),
        "output_dir": task.output_dir or "",
    }


def queue_snapshot(keyword="", status="all", sort_key="added", desc=None):
    """任务页快照：按关键词 / 状态过滤并排序后的任务列表 + 各状态计数。"""
    try:
        from core import task_queue as tq
        queue = _queue_instance()
        tasks = queue.tasks()
        visible = tq.filter_tasks(tasks, keyword, status)
        if desc is None:
            desc = tq.SORT_DESC_DEFAULT.get(sort_key, False)
        visible = tq.sort_tasks(visible, sort_key, bool(desc))
        return _ok(
            tasks=[_task_json(task) for task in visible],
            stats=queue.stats(),
            paused=queue.is_paused(),
            shown=len(visible),
            total=len(tasks),
        )
    except Exception as exc:
        return _fail(exc)


def queue_enqueue(ids_json):
    """把一批本子 ID 加入下载队列，返回真正新增与重复的 ID 列表。"""
    try:
        ids = json.loads(ids_json) if ids_json else []
        if not isinstance(ids, list):
            raise ValueError("ids must be a JSON array")
        ids = [str(item).strip() for item in ids if str(item).strip()]
        if not ids:
            raise ValueError("请先输入至少一个本子 ID")
        queue = _queue_instance()
        added, duplicated = queue.enqueue(ids)
        if added:
            threading.Thread(target=_prefetch_details, args=(added,),
                             name="jm2pdf-prefetch", daemon=True).start()
        return _ok(added=added, duplicated=duplicated)
    except Exception as exc:
        return _fail(exc)


def _prefetch_details(ids):
    """后台给刚入队的任务补名称与总页数（取不到就跳过，不影响下载）。"""
    try:
        from core.downloader import build_option
        client = build_option(config.load_conf(), with_login=False).new_jm_client()
    except Exception:
        return
    queue = _queue
    if queue is None:
        return
    for album_id in ids:
        try:
            detail = client.get_album_detail(album_id)
        except Exception:
            continue
        try:
            queue.update_meta(album_id, getattr(detail, "name", ""),
                              getattr(detail, "page_count", 0))
        except Exception:
            continue


def queue_control(action, task_id=""):
    """队列操作：暂停 / 继续 / 重试 / 取消 / 移除单条，以及暂停全部 / 继续全部 / 清空已完成。"""
    try:
        queue = _queue_instance()
        action = str(action or "")
        task_id = str(task_id or "")
        if action == "pause_all":
            queue.pause_all()
            return _ok(applied=True)
        if action == "resume_all":
            queue.resume_all()
            queue.start()          # 若此前没有调度线程（例如刚恢复队列），这里补上
            return _ok(applied=True)
        if action == "clear_finished":
            return _ok(removed=queue.clear_finished())
        handlers = {
            "pause": queue.pause_task,
            "resume": queue.resume_task,
            "retry": queue.retry_task,
            "cancel": queue.cancel_task,
            "remove": queue.remove_task,
        }
        handler = handlers.get(action)
        if handler is None:
            raise ValueError("unknown queue action: %s" % action)
        return _ok(applied=bool(handler(task_id)))
    except Exception as exc:
        return _fail(exc)


def open_path(path):
    """用系统默认方式打开文件 / 文件夹（任务卡片「打开文件夹」）。"""
    try:
        from core import platform_bridge
        target = str(path or "").strip()
        if not target:
            raise ValueError("路径为空")
        if not platform_bridge.open_path(target):
            raise OSError("没有可用来打开该文件的应用")
        return _ok()
    except Exception as exc:
        return _fail(exc)


def queue_shutdown():
    """退出前落盘并停止调度线程（由 Kotlin 在合适时机调用）。"""
    try:
        if _queue is not None:
            _queue.shutdown()
        return _ok()
    except Exception as exc:
        return _fail(exc)


