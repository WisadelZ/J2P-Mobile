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
"""加载源（禁漫移动端 API 域名）管理：获取候选源、测延迟、排序与切换。

禁漫有好几个移动端 API 域名，哪一个能通、哪一个快会随网络环境变化。本模块负责：

1. **取候选源**：优先用 jmcomic 自动更新过的最新域名列表
   （``JmModuleConfig.DOMAIN_API_UPDATED_LIST``，由 jmcomic 在客户端初始化时去官方
   域名服务器拉取），拿不到时退回内置的 ``JmModuleConfig.DOMAIN_API_LIST``；
2. **测延迟**：对每个候选源发一个极小的 ``/setting`` 请求，超过
   :data:`TEST_TIMEOUT` 秒视为不可用；测速并发进行，整体耗时约等于最慢的那一个；
3. **排序**：按延迟升序取前 :data:`MAX_NODES` 个作为可选节点，延迟最低的设为当前节点；
4. **切换**：把选中的域名排到 jmcomic 域名列表的最前面 —— 只影响**之后新建的**
   客户端（即后续的新请求），并丢弃本会话缓存的登录客户端，让需要登录态的请求
   在新节点上重新登录。

界面只展示「节点 1..N」和延迟毫秒数，**不展示域名本身**；节点序号只是本次测速的
排序结果，不与具体域名绑定。
"""

import threading
import time
from concurrent.futures import ThreadPoolExecutor

import jmcomic

# 单个源的延迟测试超时（秒）：超过即视为不可用
TEST_TIMEOUT = 3.0

# 最多对外提供几个可选节点
MAX_NODES = 5

# 测速用的接口：只回一小段 JSON，能反映域名是否可用与大致延迟
_PROBE_PATH = "/setting"

_lock = threading.Lock()
_measure_lock = threading.Lock()   # 同一时刻只允许一次测速在跑
_ranked = []              # [{"domain": 域名, "delay": 毫秒}]，按延迟升序
_current_domain = None    # 当前使用的域名
_epoch = 0                # 代次：每次切换自增，缓存了客户端的界面据此重建


def epoch():
    """加载源代次：每次切换自增。

    界面（探索页 / 检索客户端缓存）把它跟缓存一起记下来，代次变了就重建客户端，
    否则切源后仍然用着旧域名建好的客户端，要等页面重建才生效。
    """
    with _lock:
        return _epoch


def snapshot():
    """当前节点快照：``{"nodes": [{"index", "delay"}], "current": 序号}``。

    ``current`` 为 0 表示还没测速过（界面上没有选中项）。
    """
    with _lock:
        return _snapshot_locked()


def select(position):
    """按节点序号切换当前加载源；成功返回 True。只影响之后的新请求。"""
    try:
        index = int(position) - 1
    except (TypeError, ValueError):
        return False
    with _lock:
        if not 0 <= index < len(_ranked):
            return False
        _apply(_ranked[index]["domain"])
    return True


def refresh(conf, timeout=TEST_TIMEOUT, limit=MAX_NODES):
    """取候选源 → 并发测延迟 → 排序取前几个，并把最快的一个设为当前节点。

    界面上的「延迟测试」也走这里：它一定会**重新取一次候选源并重测**，
    不是只把上次的结果拿出来看。若已有一次测速在跑，这次会先等它结束
    （否则并发的两次测速会互相插队，界面还可能拿到「一个节点都没有」的空结果）。
    """
    global _ranked
    with _measure_lock:
        domains = _candidate_domains(conf)
        measured = _measure(conf, domains, timeout)
        with _lock:
            if measured:
                _ranked = measured[:limit]
                _apply(_ranked[0]["domain"])
            return _snapshot_locked()


def snapshot():
    """当前节点快照：``{"nodes": [{"index", "delay"}], "current": 序号}``。

    ``current`` 为 0 表示还没测速过（界面上没有选中项）。
    """
    with _lock:
        return _snapshot_locked()


def _snapshot_locked():
    """按当前排名拼出快照（调用方必须已持 ``_lock``）。"""
    nodes = [{"index": i + 1, "delay": item["delay"]}
             for i, item in enumerate(_ranked)]
    current = 0
    for i, item in enumerate(_ranked):
        if item["domain"] == _current_domain:
            current = i + 1
            break
    return {"nodes": nodes, "current": current}


# ---------------------------------------------------------------------------
# 内部实现
# ---------------------------------------------------------------------------

def _candidate_domains(conf):
    """所有候选域名：优先用自动更新过的最新列表，否则用内置列表。"""
    updated = getattr(jmcomic.JmModuleConfig, "DOMAIN_API_UPDATED_LIST", None)
    if isinstance(updated, list) and updated:
        return list(dict.fromkeys(updated))
    _trigger_domain_update(conf)
    updated = getattr(jmcomic.JmModuleConfig, "DOMAIN_API_UPDATED_LIST", None)
    if isinstance(updated, list) and updated:
        return list(dict.fromkeys(updated))
    return list(dict.fromkeys(jmcomic.JmModuleConfig.DOMAIN_API_LIST))


def _trigger_domain_update(conf):
    """让 jmcomic 去官方域名服务器拉一次最新域名。

    它把结果缓存进 ``DOMAIN_API_UPDATED_LIST``（拉不到就缓存空列表，避免反复重试）；
    这里只是触发，任何异常都不该影响界面。
    """
    if getattr(jmcomic.JmModuleConfig, "DOMAIN_API_UPDATED_LIST", None) is not None:
        return
    try:
        _new_client(conf)
    except Exception:
        pass


def _measure(conf, domains, timeout):
    """并发测各个域名的延迟，返回按延迟升序的可用项。"""
    if not domains:
        return []
    pool = ThreadPoolExecutor(max_workers=len(domains))
    futures = [(pool.submit(_probe, conf, domain, timeout), domain)
               for domain in domains]
    results = []
    for future, domain in futures:
        try:
            delay = future.result(timeout=timeout)
        except Exception:
            delay = None            # 超时（线程还在跑，让它自己结束）
        if delay is not None:
            results.append({"domain": domain, "delay": delay})
    # 不等待没跑完的线程：界面上这一轮测速到此为止
    pool.shutdown(wait=False)
    results.sort(key=lambda item: item["delay"])
    return results


def _probe(conf, domain, timeout):
    """测一个域名的延迟（毫秒）；不可用返回 None。"""
    started = time.monotonic()
    try:
        # 只留这一个域名：延迟与可用性都只反映这个源
        _new_client(conf, [domain]).req_api(_PROBE_PATH, timeout=timeout)
    except Exception:
        return None
    return max(1, int((time.monotonic() - started) * 1000))


def _new_client(conf, domain_list=None):
    """建一个「不重试」的检索客户端；测速与触发域名更新都用它。

    ``retry_times`` 必须改在 option 上：``new_jm_client`` 的关键字参数会被当作
    「覆盖 meta_data」，传进去会直接变成 postman 的请求参数而报 TypeError。
    """
    from core.downloader import build_option

    option = build_option(conf, with_login=False)
    option.client.retry_times = 0      # 一个源不通就立刻算不可用，别耗在重试上
    return option.new_jm_client(domain_list=domain_list)


def _apply(domain):
    """把域名排到 jmcomic 域名列表最前，并丢弃本会话的登录客户端。调用方已持锁。"""
    global _current_domain, _epoch
    switched = _current_domain is not None and _current_domain != domain
    updated = getattr(jmcomic.JmModuleConfig, "DOMAIN_API_UPDATED_LIST", None)
    if isinstance(updated, list):
        _move_first(updated, domain)
    _move_first(jmcomic.JmModuleConfig.DOMAIN_API_LIST, domain)
    _current_domain = domain
    _epoch += 1
    if not switched:
        # 首次选定（或仍是同一个源）：不动已有会话，省掉一次重新登录
        return
    # 登录态是按域名隔离的，而已登录的客户端被钉在登录时那个域名上：
    # 换了节点就丢掉它，让下次「需要登录」的请求在新节点上重新登录
    try:
        from core.downloader import reset_session_client

        reset_session_client()
    except Exception:
        pass


def _move_first(domains, domain):
    """把 domain 移到列表最前（不在列表里则不动）。"""
    if not isinstance(domains, list) or domain not in domains:
        return False
    domains.remove(domain)
    domains.insert(0, domain)
    return True
