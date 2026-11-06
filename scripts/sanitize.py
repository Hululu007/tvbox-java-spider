#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""推送前脱敏：清空仓库文本里残留的会话 Cookie，防止误提交他人会话凭据。

规则：
  1. 所有 .json/.java/.js/.txt/.md/.html/.yml 中 "cookie": "<含 bbs_sid / bbs_token / __pugs 的值>" 置空；
     图片 URL 等不带会话特征的值保留。
  2. ReplicaSources.java 的 DEFAULT_KFZY_COOKIE 常量强制为 ""，改由配置中心读取。
  3. 本地凭据文件 output/pan/cookies.json 跳过（已被 .gitignore 排除）。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP_PARTS = {".git", "out", "node_modules", "logs"}
EXTS = {".json", ".java", ".js", ".txt", ".md", ".html", ".yml", ".yaml"}
SESSION = re.compile(r"(bbs_sid=|bbs_token=|__pugs=|__pus=)", re.I)
COOKIE_FIELD = re.compile(r'("cookie"\s*:\s*")([^"]*)(")')
# ext 里转义过的 cookie：{\"cookie\":\"bbs_sid=...; bbs_token=...\"}
EMBEDDED_SESSION = re.compile(r"bbs_sid=[A-Za-z0-9_]+;\s*bbs_token=[A-Za-z0-9_]+")
HARDCODED = re.compile(
    r'private static final String DEFAULT_KFZY_COOKIE\s*=\s*"[^"]*"\s*\+\s*"[^"]*"\s*;',
    re.S,
)


def main():
    changed = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in EXTS:
            continue
        if SKIP_PARTS.intersection(path.parts) or path.name == "cookies.json":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

        def replace(m):
            value = m.group(2)
            return m.group(1) + ("" if SESSION.search(value) else value) + m.group(3)

        new = COOKIE_FIELD.sub(replace, text)
        new = EMBEDDED_SESSION.sub("", new)
        if path.name == "ReplicaSources.java":
            new = HARDCODED.sub('private static final String DEFAULT_KFZY_COOKIE = "";', new)
        if new != text:
            path.write_text(new, encoding="utf-8")
            changed.append(str(path.relative_to(ROOT)).replace("\\", "/"))

    for name in changed:
        print("已脱敏: " + name)
    print("脱敏完成，共处理 %d 个文件" % len(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
