# -*- coding: utf-8 -*-
"""生成「本机关机也能用」的云端版产物。

1) spider/cloud_spider.js = 原 quanpan_spider.js + cloud-overlay.js
   （客户端 JS 插件，TVBox 自己跑，不经过本机 8898）
2) output/cloud-subscription.json = 纯云端订阅，零 127.0.0.1 / 零本机代理
3) output/cloud/Cloud-drive.txt = 云端版配置中心子规则

国内直连实测：jsDelivr 200、PanSou 免登实例 200、原 spider jar 200。
"""
import json
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
GH = "tlwj01/tvbox-java-spider"
CDN = "https://cdn.jsdelivr.net/gh/" + GH + "@main/"
# jsDelivr 动态压缩地址（仓库里只有 .js，.min.js 由 CDN 用 Terser 实时生成）：
# 73KB -> 45KB，国内网下载更稳，也绕开 @main 的 7 天旧缓存。
JS_URL = CDN + "spider/cloud_spider.min.js"
SUB_URL = CDN + "output/cloud-subscription.json"
HOST = "http://107.173.211.148"          # PanSou 免登实例，/api/search 返回 merged_by_type
JAR = "https://meow.668yun.top/c/rEkDf4vxeobu/yi.jar"  # 原 download1 配置的 JS 引擎 jar


def build_spider():
    src = (ROOT / "lab/quanpan/quanpan_spider.js").read_text(encoding="utf-8")
    overlay = (ROOT / "spider/cloud-overlay.js").read_text(encoding="utf-8")
    head = ("/* 生成物，请勿直接编辑：python scripts/build-cloud.py\n"
            " * 源：lab/quanpan/quanpan_spider.js + spider/cloud-overlay.js\n"
            " * 说明：TVBox 客户端直跑，零本机依赖，国内网可用。\n"
            " * ============================================================ */\n")
    out = ROOT / "spider/cloud_spider.js"
    out.write_text(head + src + "\n" + overlay, encoding="utf-8")
    return out, len(head + src + overlay)


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
        "warningText": ("云端版｜本机关机/关机后仍可用｜国内网直连｜零 127.0.0.1、零本机 8898｜"
                        "UC 免登点开即播；夸克/百度/115 需在 ext.cookies 填本人 Cookie"),
        "sites": sites,
        "parses": [],
        "lives": [],
        "rules": [],
        "doh": [],
        "ads": [],
        "flags": [],
    }
    out = ROOT / "output/cloud-subscription.json"
    out.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    return out, len(sites)


def build_cloud_drive():
    src = (ROOT / "tvfan/Cloud-drive.txt").read_text(encoding="utf-8")
    shares = [ln for ln in src.splitlines() if ln.startswith("share=")]
    lines = [
        "# ==========================================================",
        "# 云端版「🗂我的云盘┃配置」子规则（不依赖本机 8898）",
        "# 订阅: " + SUB_URL,
        "# ==========================================================",
        "config_center=" + CDN + "output/cloud/",
        "spider=" + JS_URL,
        "subscription=" + SUB_URL,
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
    p2, n2 = build_subscription()
    p3, n3 = build_cloud_drive()
    text = p2.read_text(encoding="utf-8")
    leaks = re.findall(r"https?://[^\"\\ ]*(?:127\.0\.0\.1|192\.168\.|localhost)[^\"\\ ]*", text)
    print("cloud_spider.js      : %s (%d bytes)" % (p1.relative_to(ROOT), n1))
    print("cloud-subscription   : %s (%d sites)" % (p2.relative_to(ROOT), n2))
    print("cloud/Cloud-drive.txt: %s (%d shares)" % (p3.relative_to(ROOT), n3))
    print("本机地址残留          : %d" % len(leaks))
    if leaks:
        raise SystemExit("订阅中仍有本机地址: " + ", ".join(leaks[:5]))
    print("CDN 订阅地址          : " + SUB_URL)


if __name__ == "__main__":
    main()
