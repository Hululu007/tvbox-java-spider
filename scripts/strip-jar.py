# -*- coding: utf-8 -*-
"""清除所有产物订阅里会触发「天神」宿主校验的字段。

背景：yi.jar 是加固壳，加载时会校验宿主 App 包名/签名，非「天神 / 原版开源
空壳」就弹「本接口仅支持天神…9秒后自动退出」。该 jar 对 type:3 + .js 站点
完全不必要（App 内置 QuickJS），因此把 output 下所有 JSON 中任意层级的
spider=yi.jar 清空，并删除会弹公告的 warningText。

只处理 output/，不动 config/ 下的原始学习样本；原子写，失败不影响原文件。
"""
import json
import os
import pathlib
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "output"
BAD = "yi.jar"


def clean(node, stats):
    if isinstance(node, dict):
        for key in list(node.keys()):
            val = node[key]
            if key in ("spider", "jar") and isinstance(val, str) and BAD in val:
                # 站点级 jar 字段同样会让 TVBox 去加载 yi.jar
                node[key] = ""
                stats["spider"] += 1
            elif key == "warningText":
                del node[key]
                stats["warning"] += 1
            else:
                clean(val, stats)
    elif isinstance(node, list):
        for item in node:
            clean(item, stats)


def atomic_write(path, text):
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".strip-", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def main():
    stats = {"spider": 0, "warning": 0, "files": 0}
    for p in sorted(OUT.rglob("*.json")):
        try:
            raw = p.read_text(encoding="utf-8")
            data = json.loads(raw)
        except Exception:
            continue
        before = json.dumps(data, ensure_ascii=False, sort_keys=True)
        clean(data, stats)
        after = json.dumps(data, ensure_ascii=False, sort_keys=True)
        if after != before:
            atomic_write(p, json.dumps(data, ensure_ascii=False, indent=2))
            stats["files"] += 1
            print("已清理: %s" % p.relative_to(ROOT))
    print("共清理 %d 个文件，spider=%d 处，warningText=%d 处" %
          (stats["files"], stats["spider"], stats["warning"]))


if __name__ == "__main__":
    main()
