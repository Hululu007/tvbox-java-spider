# -*- coding: utf-8 -*-
"""生成「本机关机也能用」的云端版产物。

1) spider/cloud_spider.js = 原 quanpan_spider.js + cloud-overlay.js
   （客户端 JS 插件，TVBox 自己跑，不经过本机 8898）
2) output/cloud-subscription.json / output/safe-subscription.json
   纯云端订阅，零 127.0.0.1 / 零本机代理 / 零 spider jar / 零 warningText
3) output/cloud/Cloud-drive.txt = 云端版配置中心子规则

重要：原 download1 的 yi.jar 是加固壳（assets/dex.enc + libsecure.so +
com.github.catvod.secure.NativeSecure + getPackageName/exit/Toast），会做宿主
白名单校验。HUB 免费版 4.0.1 等不在白名单的 App 加载它时会弹
「本接口仅支持天神及部分原版开源空壳软件…9秒后自动退出」并杀进程。
本订阅 27 个站点全是 type:3 + .js，由 App 内置 QuickJS 执行，不需要 spider jar，
因此 spider 一律留空、且不写 warningText（避免任何公告弹窗）。

safe-subscription.json 是全新文件名，用于绕开 jsDelivr @main 的 7 天旧缓存。
"""
import json
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
GH = "tlwj01/tvbox-java-spider"
CDN = "https://cdn.jsdelivr.net/gh/" + GH + "@main/"
# jsDelivr 动态压缩地址（仓库里只有 .js，.min.js 由 CDN 用 Terser 实时生成）：
# 73KB -> 45KB，国内网下载更稳，也绕开 @main 的 7 天旧缓存。
# 插件也用全新文件名：jsDelivr 对 @main 旧路径缓存 7 天，purge.jsdelivr.net
# 只允许 GET 且对旧路径无效，实测 cloud_spider.min.js 仍返回旧代码。
# 新文件名首次请求必然拉仓库最新版；.min.js 由 CDN 对同名 .js 实时压缩生成。
JS_NAME = "cloud_spider_v2.js"
JS_URL = CDN + "spider/" + JS_NAME.replace(".js", ".min.js")
SAFE_NAME = "safe-subscription.json"
SAFE_URL = CDN + "output/" + SAFE_NAME
HOST = "http://107.173.211.148"          # PanSou 免登实例，/api/search 返回 merged_by_type
JAR = ""                                  # 禁用 yi.jar，见文件头说明


def build_spider():
    src = (ROOT / "lab/quanpan/quanpan_spider.js").read_text(encoding="utf-8")
    overlay = (ROOT / "spider/cloud-overlay.js").read_text(encoding="utf-8")
    head = ("/* 生成物，请勿直接编辑：python scripts/build-cloud.py\n"
            " * 源：lab/quanpan/quanpan_spider.js + spider/cloud-overlay.js\n"
            " * 说明：TVBox 客户端直跑，零本机依赖，国内网可用。\n"
            " * ============================================================ */\n")
    text = head + src + "\n" + overlay
    for name in ("cloud_spider.js", JS_NAME):
        (ROOT / "spider" / name).write_text(text, encoding="utf-8")
    return ROOT / "spider" / JS_NAME, len(text)


def ext_for(key):
    return json.dumps({"host": HOST, "site": key, "cookies": {}}, ensure_ascii=False)


def build_subscription():
    replica = json.loads((ROOT / "output/replica-subscription.json").read_text(encoding="utf-8"))
    sites = [{
        "key": "CloudQuanPan4K",
        "name": "🌸全盘搜｜云端4K（免本机）",
        "type": 3,
        "api": JS_URL,
        "searchable": 1,
        "quickSearch": 1,
        "filterable": 0,
        "changeable": 1,
        "ext": ext_for("QuanPan4K"),
    }]
    for s in replica.get("sites", []):
        m = re.search(r"/api/cms/([^/]+)/", str(s.get("api", "")))
        key = m.group(1) if m else re.sub(r"\W", "", s.get("name", ""))[:18]
        sites.append({
            "key": "Cloud_" + key,
            "name": s.get("name"),
            "type": 3,
            "api": JS_URL,
            "searchable": 1,
            "quickSearch": 1,
            "filterable": 0,
            "changeable": 1,
            "ext": ext_for(key),
        })

    cfg = {
        "spider": JAR,
        "wallpaper": "",
        "logo": "",
        "sites": sites,
        "parses": [],
        "lives": [],
        "rules": [],
        "doh": [],
        "ads": [],
        "flags": [],
    }
    text = json.dumps(cfg, ensure_ascii=False, indent=2)
    for p in (ROOT / "output/cloud-subscription.json",
              ROOT / "output" / SAFE_NAME):
        p.write_text(text, encoding="utf-8")
    return ROOT / "output/cloud-subscription.json", len(sites), text


def build_cloud_drive():
    src = (ROOT / "tvfan/Cloud-drive.txt").read_text(encoding="utf-8")
    shares = [ln for ln in src.splitlines() if ln.startswith("share=")]
    lines = [
        "# ==========================================================",
        "# 云端版「🗂我的云盘┃配置」子规则（不依赖本机 8898）",
        "# 订阅: " + SAFE_URL,
        "# spider 留空：yi.jar 带宿主白名单校验，非天神版会弹窗并退出",
        "# ==========================================================",
        "config_center=" + CDN + "output/cloud/",
        "spider=",
        "subscription=" + SAFE_URL,
        "backend=" + HOST,
        "quark_cookie=未配置（夸克播放请在订阅 ext.cookies.quark_cookie 填本人 Cookie）",
        "kfzy_cookie=云端版不使用",
        "# ---- 自建分享库（UC 免登可播，夸克需 Cookie）----",
    ] + shares
    out = ROOT / "output/cloud/Cloud-drive.txt"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out, len(shares)


def main():
    p1, n1 = build_spider()
    p2, n2, text = build_subscription()
    p3, n3 = build_cloud_drive()
    leaks = re.findall(r"https?://[^\"\\ ]*(?:127\.0\.0\.1|192\.168\.|localhost)[^\"\\ ]*", text)
    bad = [k for k in ("yi.jar", "warningText", "天神") if k in text]
    print("cloud_spider.js      : %s (%d bytes)" % (p1.relative_to(ROOT), n1))
    print("cloud-subscription   : %s (%d sites)" % (p2.relative_to(ROOT), n2))
    print("safe-subscription    : output/%s (同一份内容，新文件名绕开 CDN 缓存)" % SAFE_NAME)
    print("cloud/Cloud-drive.txt: %s (%d shares)" % (p3.relative_to(ROOT), n3))
    print("本机地址残留          : %d" % len(leaks))
    print("jar/公告/天神残留     : %s" % (", ".join(bad) if bad else "无"))
    if leaks:
        raise SystemExit("订阅中仍有本机地址: " + ", ".join(leaks[:5]))
    if bad:
        raise SystemExit("订阅中仍有触发弹窗的字段: " + ", ".join(bad))
    print("推荐订阅地址          : " + SAFE_URL)


if __name__ == "__main__":
    main()
