# -*- coding: utf-8 -*-
"""生成 Hub 免费版 4.0.1 专用的极简兼容订阅。

「报天神」的真因：原 download1 配置里的 spider=yi.jar 是加固壳，
带宿主白名单校验（NativeSecure / libsecure.so / assets/dex.enc），
在 HUB 免费版等非「天神 / 原版开源空壳」App 上初始化时会弹
「本接口仅支持天神及部分原版开源空壳软件，使用其他版本软件将在9秒后自动退出」
并倒计时杀进程。文案不在 APK、不在 jar 明文、也不在任何订阅里，
由加密 dex / native / 鉴权接口运行时返回。

本订阅所有产出：spider 留空、无 warningText、单站点，供「粘贴配置」直接验证。
站点是 type:3 + .js，由 App 内置 QuickJS 执行，不需要 spider jar。

产出：
  output/hub-subscription.json            单站点、spider 留空（推荐粘贴）
  output/hub-nospider-subscription.json   同上，保留旧文件名兼容
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
GH = "tlwj01/tvbox-java-spider"
CDN = "https://cdn.jsdelivr.net/gh/" + GH + "@main/"
JS_URL = CDN + "spider/cloud_spider_v2.min.js"  # 新文件名，绕开 @main 旧缓存
HOST = "http://107.173.211.148"


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


def build_cfg():
    return {
        "spider": "",
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
    cfg = build_cfg()
    text = json.dumps(cfg, ensure_ascii=False, indent=2)
    for bad in ("天神", "warningText", "yi.jar"):
        if bad in text:
            raise SystemExit("Hub 订阅不应含 " + bad)
    if re.search(r"https?://[^\"\\ ]*(127\.0\.0\.1|192\.168\.|localhost)", text):
        raise SystemExit("Hub 订阅仍含本机地址")
    for name in ("hub-subscription.json", "hub-nospider-subscription.json"):
        p = ROOT / "output" / name
        p.write_text(text, encoding="utf-8")
        print("%-38s %5d bytes, sites=1, spider=empty" %
              (name, len(text.encode("utf-8"))))
    print("CDN 地址: " + CDN + "output/hub-subscription.json")
    print("粘贴配置内容: output/hub-subscription.json")


if __name__ == "__main__":
    main()
