# -*- coding: utf-8 -*-
"""生成 Hub 免费版 4.0.1 专用的极简兼容订阅。

用途：排查「报天神」。天神是另一份第三方配置（0.wdzb.eu.cc/tvbox/天神.json）
里的站点名「🌹天神｜小屋」，不是错误码。若 App 里出现它，说明当前加载的仍是
旧配置/云控回退配置。本订阅只留 1 个站点、去掉 warningText（避免弹窗被误认
为报错）、字段形态对齐原 download1.json，方便用「粘贴配置」直接验证。

产出：
  output/hub-subscription.json          带原版 JS 引擎 jar
  output/hub-nospider-subscription.json spider 留空（自带 JS 引擎的分支）
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
GH = "tlwj01/tvbox-java-spider"
CDN = "https://cdn.jsdelivr.net/gh/" + GH + "@main/"
JS_URL = CDN + "spider/cloud_spider.min.js"
HOST = "http://107.173.211.148"
JAR = "https://meow.668yun.top/c/rEkDf4vxeobu/yi.jar"


def build_site():
    return {
        "key": "HubQuanPan4K",
        "name": "🌸全盘搜｜云端4K",
        "type": 3,
        "api": JS_URL,
        "searchable": 1,
        "quickSearch": 1,
        "filterable": 0,
        "ext": json.dumps({"host": HOST, "site": "QuanPan4K", "cookies": {}},
                          ensure_ascii=False),
    }


def build_cfg(spider):
    return {
        "spider": spider,
        "wallpaper": "",
        "logo": "",
        "sites": [build_site()],
        "parses": [],
        "lives": [],
        "rules": [],
        "doh": [],
        "ads": [],
        "flags": [],
    }


def main():
    for name, spider in (("hub-subscription.json", JAR),
                         ("hub-nospider-subscription.json", "")):
        cfg = build_cfg(spider)
        text = json.dumps(cfg, ensure_ascii=False, indent=2)
        if "天神" in text or "warningText" in text:
            raise SystemExit("Hub 订阅不应含天神/warningText")
        if re.search(r"https?://[^\"\\ ]*(127\.0\.0\.1|192\.168\.|localhost)", text):
            raise SystemExit("Hub 订阅仍含本机地址")
        p = ROOT / "output" / name
        p.write_text(text, encoding="utf-8")
        print("%-38s %5d bytes, sites=1, spider=%s" %
              (name, len(text.encode("utf-8")), "jar" if spider else "empty"))
    print("CDN 地址: " + CDN + "output/hub-subscription.json")


if __name__ == "__main__":
    main()
