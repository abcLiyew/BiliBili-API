#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B 站端点「开工前预检」（preflight）—— 计划 §3 附录承诺的可复跑脚本。

为什么需要它
─────────────────────────────────────────────────────────────────────────
B 站端点变动频繁，官方文档经常过时。本仓库已经踩过 5 次「文档说有、实测没有」：
`archive/stat` 404、`pgc/web/timeline` 返回 null、`space/wbi/acc/info` **需签名「且」需凭据**
（09-15 误记成"只需签名"，09-21 翻案）、`search/suggest` 404、以及
`search/all/v2`+`search/type` **文档标 Wbi 实测免签名**。所以 `INTERFACE_PLAN.md` §5 定了一条纪律：
**每批次实现前先跑一遍本脚本**，确认端点仍然可用，不要做到一半才发现端点没了。

⚠️ **本脚本不做 WBI 签名**，所以它只能覆盖「匿名 vs 带凭据」两格；涉及签名端点的
**2×2（匿名/凭据 × 无签/签名）** 需要另用库内的 `sign/WbiSigner`（见 `API_FACTS.md` §2.12）。

失败形态必须分开看（都见过）
─────────────────────────────────────────────────────────────────────────
| 形态 | 含义 | 处置 |
|---|---|---|
| HTTP 404 且 body 是 HTML | **端点已下线** | 整项降级，别硬做 |
| HTTP 200、`code` 是业务错误码 | 端点活着，是**参数/权限**问题 | 换真实 id；`-101` 才是真需登录 |
| HTTP 200、`code=0` 但 `data` 是空对象/空数组 | **静默风控** 或 **需登录**（最难查） | 见下条，两步都要做 |
| HTTP 200 但 body 不是 JSON | 多半是**压缩**（deflate/gzip） | 本脚本会自动解压后再判断 |

🔴 第三条必须走两步，只做第一步就会误判（2026-09-21 真实教训）
─────────────────────────────────────────────────────────────────────────
`x/space/upstat` 匿名返回 `code=0` + `data={}`，当时判成"静默风控"、排进了不做的那一档。
后来带上真实凭据一测：`archive.view=9065` / `likes=408` —— **它其实只是"需登录"**。
两者的响应**长得一模一样**，唯一区别是"带不带凭据"。所以判据必须是：

    ① 换一个"确信有数据"的真实 id 复验  ->  排除"真没数据"
    ② 再带凭据复验一次（本脚本自动做 A/B）->  区分"需登录"与"风控"
    ③ 两步都空  ->  才判风控

**推论：任何返回集合字段的端点，都必须把长度打出来**（`items=N` / `list=N`）。
`code=0` + `items=[]` 与 `code=0` + `items=13` 只看 code 是分不出来的 ——
本脚本的 `count_collections()` 就是为这个存在的。

用法
─────────────────────────────────────────────────────────────────────────
    python tools/endpoint-preflight.py                         # 匿名层（约 20 个请求，600ms 间隔）
    python tools/endpoint-preflight.py --no-cookie             # 强制匿名，用于纯匿名验证
    python tools/endpoint-preflight.py --cookie-file=<路径>     # 指定凭据

Cookie 自动发现顺序：`.workbuddy/bili-anon-cookie.txt` → `.workbuddy/bili-cookie.txt`。
前者是匿名设备身份（用 `tools/cdp-cookie-bridge/CdpCookieBridge.java cookies` 生成）；
后者是真实登录凭据 —— **一旦发现 `SESSDATA` 就自动启用「凭据层」探测**
（= 计划 §4-B3.5 那 6 项，外加对 `space/upstat` 与 `playurl` 的两组 **A/B 对照**）。
那一段会读你自己的账号数据，**但全部是只读 GET，不碰任何写操作**。
没有任何 Cookie 文件也能跑，只是风控概率更高。

输出是**纯 ASCII**：本脚本会在 Git Bash / IDE / cmd 之间反复跑，三者 stdout 编码不一致。

覆盖范围（按批）
─────────────────────────────────────────────────────────────────────────
| 段 | 内容 | 联网 |
|---|---|---|
| bootstrap | 取真实 bvid/aid/cid + live room（**失败则整表不可读**，会明确报警） | ✅ |
| positive control | `popular` 必须 `code=0`，否则下面每行 `-352/-403` 都是"你的出口" | ✅ |
| B1 | 匿名高频 11 项（其中 video stat 借 `view/detail` 顺带，0 额外请求） | ✅ |
| B2 | 匿名中频 7 项（含 deflate 解压、集合长度） | ✅ |
| **B4** | **边界批**：1 个可用（`article/viewinfo`）+ **6 个"做不动"留成可重跑复查**（**只打印**） | ✅ |
| **B5** | **能力面补充**：`space/top/arc`（含 `mid` 误参反证）、`note/is_forbid`（含 `aid=1` 陷阱格） | ✅ |
| **B5 算法** | **`bvid ⇄ aid` 纯算法交叉校验（第二实现 + 对照实现）** | ❌ **0 请求** |
| credential layer | B3.5 那 6 项 + `space/upstat` / `playurl` 的 A/B 对照 | ✅ |

⚠️ **B4 段的"死"是待观察状态，不是契约** ⇒ 该段**刻意不加断言**；上游修好了应由人看到表格后决定开工，
不该让预检"变红"来报警。B5 段则相反 —— 它是**已交付能力**，那两格反证（`mid`、无参）是真断言级的信息。
"""
import gzip
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zlib

COOKIE_CANDIDATES = [
    ".workbuddy/bili-anon-cookie.txt",
    ".workbuddy/bili-cookie.txt",
]
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0")
GAP_SECONDS = 0.6          # 端点之间最小间隔（与计划一致，避免触发风控）
TIMEOUT = 12

# ---- 各批固定样本（真机实测过的值，不是占位符）-------------------------------
SAMPLE_VMID = 2            # B5 space/top/arc：实测该 UP 有置顶视频（38 键）
SAMPLE_AID = 80433022      # B5 note/is_forbid：与 fixtures/note-isforbid.json 同一个样本
SAMPLE_CV = 4538122        # B4 article/viewinfo：cv4538122，实测匿名 23 键

# 常见「集合字段」名。命中就打印长度 —— 这是本脚本最重要的一个判据：
# `code=0` + `items=[]` 与 `code=0` + `items=13` 在只看 code 时长得一样，
# 而前者是静默风控/需登录、后者是真有数据。**不打长度就会误判。**
COLLECTION_KEYS = ("list", "items", "replies", "medias", "archives", "trending",
                   "durl", "edges", "cards", "sections", "packages", "upper", "top")


def load_cookie(force_anon, explicit_path=None):
    """取一份 Cookie：`--cookie-file=` 优先，否则按 COOKIE_CANDIDATES 顺序找第一个非空文件。"""
    if force_anon:
        return None
    candidates = [explicit_path] if explicit_path else COOKIE_CANDIDATES
    for path in candidates:
        if path and os.path.exists(path):
            text = io.open(path, encoding="utf-8").read().strip()
            if text:
                return text, path
    return None


def request(url, referer, anon=False):
    headers = {
        "User-Agent": UA,
        "Referer": referer,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }
    if COOKIE and not anon:
        headers["Cookie"] = COOKIE
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)
    except Exception as e:                      # noqa: BLE001 - 探测脚本要吞掉一切
        return -1, str(e).encode(), {}


def decode_body(raw, headers):
    """按 Content-Encoding 解压。dm/list.so 是 deflate，不解压会被误判成'非 JSON'。"""
    encoding = (headers.get("Content-Encoding") or "").lower()
    if "deflate" in encoding:
        for wbits in (-zlib.MAX_WBITS, zlib.MAX_WBITS):
            try:
                return zlib.decompress(raw, wbits)
            except Exception:
                continue
        return b""
    if "gzip" in encoding:
        try:
            return gzip.decompress(raw)
        except Exception:
            return b""
    return raw


def count_collections(data):
    """给 `data` 里第一个命中的集合字段补一个 `name=N`。

    存在的理由：`x/polymer/web-dynamic/v1/feed/space` 在**匿名**下会返回
    `code=0` + `items=[]`（静默风控），而在**带凭据**下返回 `code=0` + `items=13`。
    两种响应只差一个长度 —— 不打长度就没法区分，会直接排错期。

    并且要往里挖一层：有些端点的集合**不是** `data.list` 而是 `data.trending.list`
    （`search/square`）或 `data.packages`（`emote/user/panel/web`）。只看第一层会
    打印出 `keys=2 ['trending','setting']` 却**一个长度都没有** —— 那等于没打长度。
    """
    for key in COLLECTION_KEYS:
        val = data.get(key)
        if isinstance(val, list):
            return " %s=%d" % (key, len(val))
        if isinstance(val, dict):
            for inner in ("list", "items"):
                if isinstance(val.get(inner), list):
                    return " %s.%s=%d" % (key, inner, len(val[inner]))
            return " %s=OBJ(keys=%d)" % (key, len(val))
    return ""


# ─────────────────────────────────────────────────────────────────────────────
# B5: bvid ⇄ aid 纯算法 —— 这里**故意再实现一份**（与 Java 的 `parse/BvCode` 同规格、不同语言）
# ─────────────────────────────────────────────────────────────────────────────
# 为什么要抄一份？因为 Java 那份**算错了不会抛异常**：只要输入"格式合法"，它就安静地返回一个
# 内容错误的 aid，下游拿着它去请求，拿到的是空列表 —— 与"这东西真的没数据"完全同形。
# 所以它的验法只有一个：**拿第二个独立实现去对真实数据**。下面的 cross-check 就是这么用的。
# （同源道理见 skills/algorithm-port-verification：黄金向量 + 对照实现 + 外部权威源。）
BV_ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
BV_XOR = 23442827791579
BV_MASK = (1 << 51) - 1
BV_MAX_AID = 1 << 51
BV_BASE = 58


def bv_to_aid(bvid, do_swap=True):
    """bvid -> aid。`do_swap=False` 是**故意写错的对照实现**，用来证明那两次交换真的在起作用。"""
    if len(bvid) != 12 or not bvid.startswith("BV"):
        raise ValueError("bad bvid: %s" % bvid)
    c = list(bvid)
    if do_swap:
        c[3], c[9] = c[9], c[3]
        c[4], c[7] = c[7], c[4]
    tmp = 0
    for ch in c[3:]:
        idx = BV_ALPHABET.find(ch)
        if idx < 0:
            raise ValueError("illegal char %r in %s" % (ch, bvid))
        tmp = tmp * BV_BASE + idx
    return (tmp & BV_MASK) ^ BV_XOR


def aid_to_bv(aid):
    if aid <= 0 or aid >= BV_MAX_AID:
        raise ValueError("aid out of range: %d" % aid)
    arr = list("BV1" + "0" * 9)
    tmp = (BV_MAX_AID | aid) ^ BV_XOR
    i = 11
    while tmp > 0:
        arr[i] = BV_ALPHABET[tmp % BV_BASE]
        tmp //= BV_BASE
        i -= 1
    arr[3], arr[9] = arr[9], arr[3]
    arr[4], arr[7] = arr[7], arr[4]
    return "".join(arr)


def probe(label, url, referer="https://www.bilibili.com/", note=None, anon=False):
    http, raw, headers = request(url, referer, anon)
    time.sleep(GAP_SECONDS)

    if http == -1:
        print("%-26s EXC  %s" % (label, raw.decode("utf-8", "replace")[:70]))
        return None

    encoding = (headers.get("Content-Encoding") or "-").lower()
    body = decode_body(raw, headers).decode("utf-8", "replace").strip()

    if not body.startswith("{"):
        kind = "HTML/EOF" if "<!DOCTYPE" in body[:200] or "<html" in body[:200] else "NON-JSON"
        print("%-26s http=%-3s %s len=%d enc=%s" % (label, http, kind, len(raw), encoding))
        return None

    obj = json.loads(body)
    code = obj.get("code")
    msg = str(obj.get("message", obj.get("msg", "")))[:22]
    data = obj.get("data")
    if isinstance(data, dict):
        if data:
            shape = "keys=%d %s" % (len(data), sorted(data.keys())[:8])
            shape += count_collections(data)     # 关键：长度必须打出来
        else:
            shape = "!! EMPTY OBJECT (silent risk-control?)"
    elif isinstance(data, list):
        shape = "list x%d" % len(data)
    elif "data" not in obj:
        # 2026-09-22：与 "data":null 必须分开报。
        # 连 data 键都没有 ⇒ 不是"合法的空结果"，而是**参数形状就没对上**（换 id / 带凭据都救不回来）。
        # 真实案例：pgc/web/timeline 带参 code=0 但无 data 键，裸调 -400。
        shape = "!! NO 'data' KEY (shape mismatch -- NOT an empty result)"
    else:
        shape = repr(data)

    flags = []
    if http != 200:
        flags.append("HTTP%d" % http)
    if code == -101:
        flags.append("LOGIN-REQUIRED")
    if code == -403:
        # -403 有两种成因，别一律当签名问题：2026-09-21 实测 `fav/resource/list` 对**私密**收藏夹
        # 匿名访问也返回 -403 `访问权限不足` —— 那是资源权限，不是签名。
        flags.append("FORBIDDEN(-403: missing sign OR no permission)")
    if code == -352 or code == -412:
        flags.append("RISK-CONTROL")
    if code == -400:
        flags.append("BAD-PARAMS(needs reverse-engineering)")
    suffix = ("  <<< " + ",".join(flags)) if flags else ""
    print("%-26s http=%-3s code=%-6s %-24s %s%s" % (label, http, code, msg, shape, suffix))
    if note:
        print("%-26s note: %s" % ("", note))
    return obj


def api(path, **params):
    return "https://api.bilibili.com/" + path + "?" + "&".join(
        "%s=%s" % (k, v) for k, v in params.items())


def live(path, **params):
    return "https://api.live.bilibili.com/" + path + "?" + "&".join(
        "%s=%s" % (k, v) for k, v in params.items())


COOKIE = None  # 在 main 里赋值

if __name__ == "__main__":
    force_anon = "--no-cookie" in sys.argv
    explicit = None
    for _arg in sys.argv[1:]:
        if _arg.startswith("--cookie-file="):
            explicit = _arg.split("=", 1)[1].strip()

    loaded = load_cookie(force_anon, explicit)
    COOKIE = loaded[0] if loaded else None
    AUTHENTICATED = bool(COOKIE) and "SESSDATA=" in COOKIE

    print("=== identity ===")
    if loaded:
        print("cookie: loaded %d chars from %s" % (len(loaded[0]), loaded[1]))
        print("layer : %s" % ("AUTHENTICATED (SESSDATA present)" if AUTHENTICATED
                              else "anonymous (no SESSDATA)"))
    else:
        print("cookie: NONE (fully anonymous)")
    print()

    print("=== bootstrap: real ids ===")
    # 这一段的成败**决定整张表能不能读**：拿不到 id，后面每个需要 bvid/aid/cid 的探测
    # 都会变成 `-400`（参数是 None），看起来像"端点全线阵亡"。2026-09-22 真发生过两次：
    #   ① ranking/v2 用了站根 Referer -> -352（已修：改用排行榜页 Referer）
    #   ② 第一个请求 DNS 抖动 -> 抛异常 -> id 全空（本次修：**重试 + 换来源回落**）
    # 所以这里做三件事：重试、从第二个端点回落、以及**拿不到 id 就明确报警，而不是照常往下打**。
    RANK_REFERER = "https://www.bilibili.com/v/popular/rank/all"

    def boot_from(url, referer, label):
        """从一个「列表型」端点取第一条的 bvid/aid/cid；失败重试一次。"""
        for attempt in (1, 2):
            tag = label if attempt == 1 else label + " [retry]"
            obj = probe("bootstrap " + tag, url, referer=referer)
            data = (obj or {}).get("data")
            if isinstance(data, dict):
                items = data.get("list") or []
                if items:
                    it = items[0]
                    return it.get("bvid"), it.get("aid"), it.get("cid")
        return None, None, None

    bvid = aid = cid = None
    for _label, _url, _ref in (
            ("ranking/v2 [rank-page ref]",
             api("x/web-interface/ranking/v2", rid=1, type="all"), RANK_REFERER),
            ("popular [fallback]",
             api("x/web-interface/popular", ps=5, pn=1), "https://www.bilibili.com/")):
        bvid, aid, cid = boot_from(_url, _ref, _label)
        if bvid:
            print("           ids sourced from: %s" % _label)
            break
    print("           bvid=%s aid=%s cid=%s" % (bvid, aid, cid))
    if not bvid:
        print()
        print("!!! BOOTSTRAP FAILED: no bvid/aid/cid.")
        print("!!! Every probe below that needs an id WILL print -400.")
        print("!!! That is a bootstrap failure, NOT an endpoint failure -- re-run before reading.")
        print()

    room = None
    room_boot = probe("live getList", live("xlive/web-interface/v1/second/getList", platform="web",
                                           parent_area_id=1, area_id=0, sort_type="online", page=1),
                      referer="https://live.bilibili.com/",
                      note="2026-09-22: returned -352 on a healthy exit -- this room LIST endpoint is "
                           "risk-controlled; it does NOT mean the live domain is down (Room/playUrl "
                           "and Master/info are both code=0 in the same run)")
    if room_boot and isinstance(room_boot.get("data"), dict):
        rooms = room_boot["data"].get("list") or []
        if rooms:
            room = rooms[0].get("roomid")
    print("           live room=%s" % room)
    print()

    # ---- 阳性对照（positive control）----------------------------------------
    # 用一个**匿名、无需 id、且对 Referer 不敏感**的老端点当对照：popular。
    # 它 `code=0` 才说明「出口 IP 没被封」；否则下面每一行 -352/-403 读出来的
    # 都是"你的出口"而不是"端点的属性" —— 这两件事分不清时，整张表都不能用。
    # （2026-09-21：这条纪律是花了一次翻案换来的，见 API_FACTS.md §2.12）
    # ⚠️ 别图省事拿 ranking/v2 兼任对照：它对 Referer 敏感（见上面 bootstrap 的注释），
    # 一旦 Referer 用错，**对照本身会失败**，把一次正常运行读成"出口被封"。
    control = probe("positive control: popular", api("x/web-interface/popular", ps=1, pn=1))
    if control and control.get("code") == 0:
        print("[positive control] popular code=0 -> exit IP looks healthy")
    else:
        print("!!! POSITIVE CONTROL FAILED: popular is not code=0")
        print("!!! Every -352 / -403 below may be YOUR EXIT, not the endpoint.")
        print("!!! Fix connectivity/exit first -- otherwise this whole run is unreadable.")
    print()

    print("=== B1: anonymous high-frequency ===")
    detail = probe("1  view/detail", api("x/web-interface/view/detail", bvid=bvid))
    if detail and isinstance(detail.get("data"), dict):
        view = detail["data"].get("View") or {}
        stat = view.get("stat")
        print("           View.stat = %s" % (sorted(stat.keys()) if isinstance(stat, dict) else stat))
        aid = aid or view.get("aid")
        cid = cid or view.get("cid")
    probe("2  tag/archive/tags", api("x/tag/archive/tags", bvid=bvid))
    probe("3  archive/related", api("x/web-interface/archive/related", bvid=bvid))
    probe("4  player/online/total", api("x/player/online/total", bvid=bvid, cid=cid))
    print("%-26s <- sourced from view/detail response (0 extra requests)" % "5  video stat")
    probe("6  v2/reply", api("x/v2/reply", type=1, oid=aid, pn=1, ps=20, sort=2))
    probe("7  Room/playUrl", live("room/v1/Room/playUrl", cid=room or 1, qn=10000, platform="web"),
          referer="https://live.bilibili.com/")
    probe("8  Master/info", live("live_user/v1/Master/info", uid=2),
          referer="https://live.bilibili.com/")
    probe("9  relation/stat", api("x/relation/stat", vmid=2))
    probe("10 space/upstat", api("x/space/upstat", mid=654552),
          note="ANON -> code=0 + data={} ; but WITH credential it returns real data => LOGIN-GATED, NOT risk-control")
    probe("11 ranking/v2 [SITE ROOT]", api("x/web-interface/ranking/v2", rid=1, type="all"),
          note="* expect -352: this endpoint dislikes the site-root Referer (see the next row)")
    probe("11 ranking/v2 [rank page]", api("x/web-interface/ranking/v2", rid=1, type="all"),
          referer=RANK_REFERER,
          note="* what the library uses (BilibiliEndpoint#rankingReferer). Site root = -352, this = code=0")
    probe("12 popular", api("x/web-interface/popular", ps=20, pn=1),
          note="site-root Referer is FINE here -- 'bilibili needs a Referer' does NOT generalize")
    print()

    print("=== B2: mid-frequency (7 items) ===")
    # 弹幕：oid 是 **cid**（既不是 aid 也不是 bvid），且响应是 deflate 压缩的 XML，不是 JSON。
    # 这里预期打出 NON-JSON 或 HTML/EOF —— 那不代表端点坏了，只代表它不是 JSON。
    # ⚠️ 但必须**有 cid 才打**：cid=None 会让服务端回 HTTP 400，看着像端点坏了，其实是没参数。
    if cid:
        probe("B2-1 dm/list.so", api("x/v1/dm/list.so", oid=cid),
              note="expect deflate-compressed XML (NON-JSON is SUCCESS here); oid must be the cid")
    else:
        print("B2-1 dm/list.so            [skip] no cid from bootstrap -- oid=None would give HTTP 400")
    probe("B2-2 search/suggest", api("x/web-interface/search/suggest", term="bilibili"),
          note="2026-09-21: HTTP 404 -> retired, moved to B4")
    probe("B2-3 search/square", api("x/web-interface/search/square", limit=10),
          note="hot-search board; look for the 'trending' collection length")
    probe("B2-4 fav/folder/info", api("x/v3/fav/folder/info", media_id=1),
          note="placeholder media_id -> inconclusive; the real one is re-probed in the credential layer")
    probe("B2-6 fav/resource/list", api("x/v3/fav/resource/list", media_id=1, pn=1, ps=20),
          note="public folder -> readable; PRIVATE folder -> -403 (resource permission, NOT missing signature)")
    probe("B2-7 emote panel", api("x/emote/user/panel/web", business="reply"),
          note="reply-emoji panel; ANON gives code=0 + packages=null (B-shape!) -- needs a credential")
    probe("B2-8 live area getList", live("room/v1/Area/getList"),
          referer="https://live.bilibili.com/",
          note="returns the FULL tree (12 parents / 450 children); parent_area_id is IGNORED (A/B: 1/2/999 all identical)")
    if aid:
        rep = probe("B2-5a v2/reply", api("x/v2/reply", type=1, oid=aid, pn=1, ps=20, sort=2))
        replies = ((rep or {}).get("data") or {}).get("replies") or []
        if replies:
            probe("B2-5b reply/reply", api("x/v2/reply/reply", type=1, oid=aid,
                                           root=replies[0]["rpid"], pn=1),
                  note="sub-replies; NOTE: replies[].replies is null here while the pinned one is []")
    else:
        print("           [skip] no aid from bootstrap -- the two reply probes need it")
    print()

    # ---- B4 + B5 ------------------------------------------------------------
    # 2026-09-23 补：本节是 B5 交付时欠下的债 —— 本脚本当时承诺"每批开工前先跑"，
    # 却一直没有这两批的行。**缺了它，下一批开工时就没有可复跑的依据。**
    #
    # B4 = 「能力边界」批：8 个候选只活 1 个。所以本节的重点**不是**"那 1 个能不能用"
    #      （它显然能用），而是把那 6 个"做不动"的**留成可重跑的边界复查**：
    #      上游哪天修好了，这里会先看出来。⚠️ 这类行**只打印、不当断言** ——
    #      它们的"死"是待观察状态，不是契约（同 B4 冒烟第 ④ 段）。
    # B5 = 「能力面补充」批：4 条动工项全部可做（2 新端点 + 1 纯算法 + 1 注释订正）。
    print("=== B4: the boundary batch (8 candidates, ONE survived) ===")
    print("    print-only rows. 'dead' here is an OBSERVATION, not a contract -- re-check, do not assert.")
    probe("B4 article/viewinfo", api("x/article/viewinfo", id=SAMPLE_CV),
          note="<- the ONE that survived B4. anonymous + credentialed both 23 keys")
    # 6 个做不动的：URL 刻意写字面量。它们**不进 BilibiliEndpoint** —— 常量进了 src/main
    # 就会让人误以为"本库已支持"。
    probe("B4 stein/edgeinfo_v2 (no gv)", api("x/stein/edgeinfo_v2"),
          note="expect -400: graph_version is MANDATORY, not optional")
    probe("B4 stein/edgeinfo_v2 (gv=0)", api("x/stein/edgeinfo_v2", graph_version=0),
          note="expect -400 too: gv=0 is not 'a real graph_version'")
    probe("B4 pgc/web/timeline", api("pgc/web/timeline", types=1, before=6, after=6),
          note="expect code=0 but NO 'data' key at all -- NOT an empty list, unsalvageable")
    probe("B4 pgc/review/user", api("pgc/review/user"),
          note="expect -400: needs a season_id, and there is no reachable entry to get one")
    probe("B4 audio song/info", api("audio/music-service-c/web/song/info"),
          note="expect a code (4511001) -- the PATH is alive, the blocker is 'no sid entry'")
    probe("B4 note/info (cvid=1)", api("x/note/info", cvid=1),
          note="expect -101: the path is alive, it just wants a real cvid we cannot obtain")
    probe("B4 article/view (old path)", api("x/article/view", id=SAMPLE_CV),
          note="expect != 0 (risk-control): -352 and -509 both seen for the SAME call on different "
               "runs => do NOT hard-code the code value, assert 'code != 0' only")
    print()

    print("=== B5: capability top-up (4 items, all shipped) ===")
    probe("B5 space/top/arc vmid=2", api("x/space/top/arc", vmid=SAMPLE_VMID),
          note="expect code=0, 38 keys. This is the PINNED video of that UP")
    probe("B5 space/top/arc vmid=1", api("x/space/top/arc", vmid=1),
          note="expect 53016 'no pinned video' -- a BUSINESS code, NOT an error. "
               "It must NOT go through ResponseParserSupport.unwrap (that throws on code != 0)")
    wrong = probe("NEG top/arc?mid= (wrong name)", api("x/space/top/arc", mid=SAMPLE_VMID),
                  note="expect -400. **THE single most valuable row in this section**: on 2026-09-22 "
                       "the sweep script sent 'mid' here, got -400, and nearly declared this working "
                       "endpoint dead. The parameter is 'vmid'.")
    probe("B5 note/is_forbid aid=80433022", api("x/note/is_forbid", aid=SAMPLE_AID),
          note="expect code=0; response body is just {forbid_note_entrance}")
    probe("TRAP is_forbid?aid=1 (ghost aid)", api("x/note/is_forbid", aid=1),
          note="expect code=0 EVEN THOUGH aid 1 does not exist => this endpoint does NOT validate the "
               "aid; its success is NOT evidence that the video exists (contrast: view?aid=1 -> 62012)")
    probe("NEG is_forbid (no aid)", api("x/note/is_forbid"),
          note="expect -400 -- aid is required")
    if wrong and wrong.get("code") != -400:
        print("!!! WARNING: 'mid' no longer gives -400. Either the server renamed the parameter")
        print("!!! (then BilibiliEndpoint#spaceTopArcUrl's caller must change) or the check stopped.")
        print("!!! Re-verify by hand before shipping anything that depends on the parameter name.")
    print()

    # ---- B5 纯算法交叉校验（0 个请求）---------------------------------------
    # 这是本脚本里唯一**不联网**的一段，也是 B5 交付物里最该被复跑的一段。
    print("=== B5: bvid <-> aid pure algorithm (0 requests, second implementation) ===")
    _samples = [
        ("BV1L9Uoa9EUx", 111298867365120, "golden"),
        ("BV1GJ411x7h7", 80433022, "real"),
        ("BV17x411w7KC", 170001, "real + trap sample"),
        ("BV1xx411c7DS", 349, "real"),
    ]
    if bvid and aid:
        _samples.append((bvid, aid, "bootstrap"))
    _bad = 0
    for _bv, _aid, _kind in _samples:
        try:
            _got_aid = bv_to_aid(_bv)
            _got_bv = aid_to_bv(_aid)
        except ValueError as _e:
            _bad += 1
            print("  FAIL %-13s %s (%s)" % (_bv, _e, _kind))
            continue
        if _got_aid == _aid and _got_bv == _bv:
            print("  OK   %-13s <-> %-16d (%s)" % (_bv, _aid, _kind))
        else:
            _bad += 1
            print("  FAIL %-13s -> aid=%d (want %d) / aid=%d -> %s (want %s) (%s)"
                  % (_bv, _got_aid, _aid, _aid, _got_bv, _bv, _kind))
    # 对照实现：漏掉那两次字符交换会怎样。**只对"交换位字符不同"的样本才有意义** ——
    # BV17x411w7KC 的第 3、9 位都是 '7'，交换没有任何效果，所以它检测不出漏掉 swap(3,9)。
    for _bv in ("BV1L9Uoa9EUx", "BV1GJ411x7h7", "BV1xx411c7DS"):
        try:
            if bv_to_aid(_bv, do_swap=False) == bv_to_aid(_bv):
                _bad += 1
                print("  FAIL %s: the swap does NOT change the result -> this sample cannot" % _bv)
                print("       prove the swap is implemented. Pick a sample with char[3] != char[9]")
        except ValueError:
            pass
    if _samples and _samples[2][0]:
        _t = _samples[2][0]
        if len(_t) == 12 and _t[3] == _t[9]:
            print("  note trap sample %s: char[3] == char[9] == %r -> it can NOT detect a missing"
                  % (_t, _t[3]))
            print("       swap(3,9); it only covers swap(4,7). That is exactly why the list above")
            print("       keeps samples whose swap positions differ.")
    print("  result: %s (%d sample(s))" % ("ALL OK" if _bad == 0 else "%d FAILURE(S)" % _bad, len(_samples)))
    if _bad:
        print("!!! The Java parse/BvCode and this implementation DISAGREE (or a sample is bad).")
        print("!!! BvCode never throws on a legal-looking input -- it just returns a WRONG aid,")
        print("!!! and the caller silently gets an empty list. Fix before shipping.")
    print()

    if AUTHENTICATED:
        print("=== deferred layer: credential-gated (the B3.5 candidates) ===")
        print("    read-only probes against YOUR account; no write endpoint is touched.")
        print("    'A/B' rows: first WITH credential, second strictly anonymous.")
        nav = probe("nav (identity)", api("x/web-interface/nav"))
        nd = (nav or {}).get("data") or {}
        my_mid = nd.get("mid")
        print("           isLogin=%s uname=%s mid=%s" % (nd.get("isLogin"), nd.get("uname"), my_mid))

        # --- the two endpoints where credential changes the ANSWER (not just fields) ---
        if my_mid:
            print("           -- A/B: does the credential change the answer? --")
            probe("D3 upstat (WITH cred)", api("x/space/upstat", mid=my_mid))
            probe("D3 upstat (ANON)", api("x/space/upstat", mid=my_mid), anon=True,
                  note="if ANON is {} while WITH-cred has data -> it is LOGIN-GATED, not risk-control")
        if bvid and cid:
            # 🔴 库内走的是**不带 /wbi/** 的那条路径：带 /wbi/ 实测七格全 HTTP 412
            # （2026-09-22，见 API_FACTS.md §2.13）。三条都打，让差异保持可见。
            probe("D9 playurl plain (cred)", api("x/player/playurl", bvid=bvid, cid=cid,
                                                 qn=64, fnval=1),
                  note="<- the path the library actually uses (BilibiliEndpoint#playUrlPlainUrl)")
            probe("D9 playurl plain (ANON)", api("x/player/playurl", bvid=bvid, cid=cid,
                                                 qn=64, fnval=1), anon=True,
                  note="expect code=0 / qn=64 / durl -> playurl is NOT credential-gated for 720P")
            probe("D9 playurl /wbi/ (expect 412)", api("x/player/wbi/playurl", bvid=bvid, cid=cid,
                                                       qn=64, fnval=1),
                  note="expected HTTP 412 -- the /wbi/ PREFIX is what gets banned, not the ability")

        # --- the rest of the B3.5 list ---
        if my_mid:
            probe("D1 relation/followers", api("x/relation/followers", vmid=my_mid, pn=1, ps=20))
            probe("D2 relation/followings", api("x/relation/followings", vmid=my_mid, pn=1, ps=20))
            created = probe("D6 fav created list-all",
                            api("x/v3/fav/folder/created/list-all", up_mid=my_mid))
            probe("D8 dynamic feed/space", api("x/polymer/web-dynamic/v1/feed/space",
                                               host_mid=my_mid),
                  note="2026-09-21: WITH cred items=13 (ANON was code=0 + items=[] -> silent)")
            # 顺手把 B2 的悬留项收口：拿真实 media_id 复验收藏夹端点
            folders = ((created or {}).get("data") or {}).get("list") or []
            if folders:
                mid_real = folders[0].get("id")
                print("           real media_id=%s title=%r" % (mid_real, folders[0].get("title")))
                probe("B2-4 fav/folder/info", api("x/v3/fav/folder/info", media_id=mid_real))
                probe("B2-6 fav/resource/list", api("x/v3/fav/resource/list",
                                                    media_id=mid_real, pn=1, ps=20))
                probe("B2-6 fav/resource ANON", api("x/v3/fav/resource/list",
                                                    media_id=mid_real, pn=1, ps=20), anon=True,
                      note="private folder -> expect -403 anonymously; public folder -> readable")
        else:
            print("           [skip] nav gave no mid -- per-mid probes skipped")
        probe("D4 history/cursor", api("x/web-interface/history/cursor", ps=10))
        probe("D5 history/toview", api("x/v2/history/toview"))
        probe("D7 follow feed (feed/all)", api("x/polymer/web-dynamic/v1/feed/all",
                                               type="all", page=1))
        if bvid:
            probe("AI conclusion (unsigned)", api("x/web-interface/view/conclusion/get",
                                                  bvid=bvid, cid=cid),
                  note="2026-09-21: needs WBI SIGNATURE **and** LOGIN. -403 = 'no signature' only; "
                       "sign it and the code becomes -101 (not logged in). Both are missing.")
        print()

    print("=== NEGATIVE control group (expected to fail: proves the 'alive' rows mean something) ===")
    probe("archive/stat (expect 404)", api("x/web-interface/archive/stat", bvid=bvid))
    probe("seasons/list (expect 404)", api("x/polymer/web-space/seasons/list", mid=2,
                                           page_num=1, page_size=20),
          note="2026-09-21: retired; the collection chain needs a new season_id source "
               "(now: arc/search -> vlist[].season_id)")
    print()
    print("done. Reminder:")
    print("  404 + HTML          = endpoint retired")
    print("  code=0 + empty data = silent risk-control OR needs-login (check the A/B rows above)")
    print("  collection field    = ALWAYS read its LENGTH (items=N), never just the key name")
    print("  POSITIVE control    = popular code=0, else every -352/-403 is your exit, not the endpoint")
    print("  Referer             = CAN decide the outcome. ranking/v2 wants the rank PAGE,")
    print("                        NOT the site root (-352); popular accepts the site root.")
    print("  -352 vs -403        = which check runs FIRST? risk-control-first => -352 even when signed;")
    print("                        signature-first => -403, and signing reveals -352/-101")
    print("  WBI signing         = NOT covered here. This script cannot sign; a 2x2 matrix")
    print("                        (anon/cred x unsigned/signed) needs a signer (see sign/WbiSigner)")
