#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 api.github.com 的 Git Data API 同步本地 git 历史到 GitHub。

适用场景：github.com 的 git-upload-pack / git-receive-pack 被网络代理阻断
（CONNECT tunnel 502），但 api.github.com 可达。首次全量上传 blob/tree/commit
并建立 refs/heads/main；之后按 base_tree 只上传变化文件，增量更新 ref。

依赖：项目根目录 .gh-token（设备授权得到的 OAuth 令牌）。
"""
import base64
import concurrent.futures
import datetime
import json
import pathlib
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OWNER, REPO, BRANCH = "tlwj01", "tvbox-java-spider", "main"
API = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)
TOKEN = (ROOT / ".gh-token").read_text(encoding="utf-8").strip()
OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({}),
    urllib.request.HTTPSHandler(context=ssl.create_default_context()),
)
MAP_FILE = ROOT / ".git" / "gh-sha-map.json"
TREE_MAP_FILE = ROOT / ".git" / "gh-tree-map.json"


def load_map(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_map(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def req(method, path, payload=None, retries=2):
    url = path if path.startswith("http") else API + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "tvbox-java-spider-sync",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    for attempt in range(retries + 1):
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with OPENER.open(request, timeout=120) as resp:
                body = resp.read()
                return resp.status, (json.loads(body) if body else {})
        except urllib.error.HTTPError as e:
            body = e.read()
            try:
                detail = json.loads(body)
            except Exception:
                detail = {"message": body[:300].decode("utf-8", "replace")}
            if e.code == 404:
                return 404, detail
            if e.code >= 500 and attempt < retries:
                time.sleep(2)
                continue
            return e.code, detail
        except Exception as e:
            if attempt < retries:
                time.sleep(2)
                continue
            return 0, {"message": str(e)}
    return 0, {"message": "unknown error"}


def git(*args, binary=False):
    p = subprocess.run(["git", *args], cwd=str(ROOT), stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE)
    if p.returncode:
        raise RuntimeError("git %s: %s" % (" ".join(args),
                                          p.stderr.decode("utf-8", "replace")))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def commit_info(sha):
    raw = git("cat-file", "-p", sha, binary=True)
    head, _, msg = raw.partition(b"\n\n")
    info = {"parents": [], "message": msg.decode("utf-8", "replace")}
    for line in head.decode("utf-8", "replace").splitlines():
        if line.startswith("tree "):
            info["tree"] = line[5:]
        elif line.startswith("parent "):
            info["parents"].append(line[7:])
        elif line.startswith("author "):
            info["author"] = line[7:]
        elif line.startswith("committer "):
            info["committer"] = line[10:]
    return info


def identity(text):
    name_email, ts, tz = text.rsplit(" ", 2)
    name, email = name_email.rsplit(" <", 1)
    sign = 1 if tz[0] == "+" else -1
    offset = datetime.timedelta(minutes=sign * (int(tz[1:3]) * 60 + int(tz[3:5])))
    dt = datetime.datetime.fromtimestamp(int(ts), datetime.timezone(offset))
    return {"name": name, "email": email.rstrip(">"), "date": dt.isoformat()}


def tree_entries(commit):
    raw = git("ls-tree", "-r", "-z", commit, binary=True)
    entries = []
    for rec in raw.split(b"\0"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        mode, kind, sha = meta.split()
        entries.append({"path": path.decode("utf-8", "replace"),
                        "mode": mode.decode(), "type": kind.decode(),
                        "sha": sha.decode()})
    return entries


def diff_entries(parent, commit, entries):
    raw = git("diff-tree", "-r", "--no-commit-id", "--name-status", "-z",
              parent, commit, binary=True)
    parts = [p.decode("utf-8", "replace") for p in raw.split(b"\0") if p]
    by_path = {e["path"]: e for e in entries}
    changed = []
    for i in range(0, len(parts) - 1, 2):
        status, path = parts[i], parts[i + 1]
        if status.startswith("D"):
            changed.append({"path": path, "mode": "100644", "type": "blob",
                            "sha": None})
        elif path in by_path:
            e = by_path[path]
            changed.append({"path": path, "mode": e["mode"], "type": "blob",
                            "sha": e["sha"]})
    return changed


def upload_blob(sha):
    content = git("cat-file", "blob", sha, binary=True)
    code, data = req("POST", "/git/blobs", {
        "content": base64.b64encode(content).decode("ascii"),
        "encoding": "base64",
    })
    if code not in (200, 201) or data.get("sha") != sha:
        raise RuntimeError("blob %s 上传失败 HTTP %s: %s" % (sha, code, data))
    return sha


def ensure_initialized():
    """完全空的仓库连 Git Data API 都会返回 409，先用 Contents API 造一个初始提交。"""
    code, _ = req("GET", "/git/ref/heads/" + BRANCH)
    if code == 200:
        return
    code, result = req("PUT", "/contents/.github-init", {
        "message": "chore: 初始化仓库",
        "content": base64.b64encode(b"init\n").decode("ascii"),
    })
    if code not in (200, 201):
        raise RuntimeError("初始化空仓库失败 HTTP %s: %s" % (code, result))
    print("空仓库已初始化（Contents API 建立 %s 分支）" % BRANCH)


def main():
    sha_map = load_map(MAP_FILE)
    tree_map = load_map(TREE_MAP_FILE)

    ensure_initialized()
    code, ref = req("GET", "/git/ref/heads/" + BRANCH)
    if code == 200:
        remote_head = ref.get("object", {}).get("sha")
    elif code == 404 or (code == 409 and "empty" in str(ref.get("message", "")).lower()):
        # 新建的空仓库返回 409 Git Repository is empty.
        remote_head = None
    else:
        print("读取远端 ref 失败 HTTP %s: %s" % (code, ref))
        return 1

    base_parent = None
    if remote_head:
        local_base = next((k for k, v in sha_map.items() if v == remote_head),
                          remote_head)
        probe = subprocess.run(["git", "cat-file", "-e", local_base + "^{commit}"],
                               cwd=str(ROOT), stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
        if probe.returncode:
            # Contents API 生成的初始化提交不在本地历史里：作为本地根提交的父提交
            base_parent = remote_head
            print("远端为初始化提交，作为基线父提交")
            revs = git("rev-list", "--reverse", "HEAD").split()
        else:
            revs = git("rev-list", "--reverse", local_base + "..HEAD").split()
    else:
        revs = git("rev-list", "--reverse", "HEAD").split()

    if not revs:
        print("远端已是最新，无需推送（HEAD=%s）" % git("rev-parse", "--short", "HEAD").strip())
        return 0
    print("待推送提交 %d 个" % len(revs))

    for idx, local_sha in enumerate(revs):
        info = commit_info(local_sha)
        entries = tree_entries(local_sha)
        parent = info["parents"][0] if info["parents"] else None

        if parent is None:
            blobs = [e["sha"] for e in entries]
            tree_payload = {"tree": entries}
        else:
            changed = diff_entries(parent, local_sha, entries)
            blobs = [e["sha"] for e in changed if e["sha"]]
            parent_tree = tree_map.get(commit_info(parent)["tree"],
                                       commit_info(parent)["tree"])
            tree_payload = {"base_tree": parent_tree, "tree": changed}

        done = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures = [pool.submit(upload_blob, sha) for sha in blobs]
            for future in concurrent.futures.as_completed(futures):
                future.result()
                done += 1
                if done % 25 == 0 or done == len(blobs):
                    print("  blob 进度 %d/%d" % (done, len(blobs)), flush=True)

        code, tree = req("POST", "/git/trees", tree_payload)
        if code not in (200, 201) or not tree.get("sha"):
            print("创建 tree 失败 HTTP %s: %s" % (code, tree))
            return 1
        if tree["sha"] != info["tree"]:
            print("注意：远端 tree sha 与本地不同，已记录映射")
            tree_map[info["tree"]] = tree["sha"]

        payload = {
            "message": info["message"],
            "tree": tree["sha"],
            "parents": [sha_map.get(p, p) for p in info["parents"]],
            "author": identity(info["author"]),
            "committer": identity(info["committer"]),
        }
        if idx == 0 and base_parent:
            payload["parents"] = [base_parent] + payload["parents"]
        code, commit = req("POST", "/git/commits", payload)
        if code not in (200, 201) or not commit.get("sha"):
            print("创建 commit 失败 HTTP %s: %s" % (code, commit))
            return 1
        if commit["sha"] != local_sha:
            print("注意：远端 commit sha 与本地不同，已记录映射")
            sha_map[local_sha] = commit["sha"]
        print("  已同步 %s -> %s（%d 个 blob）" %
              (local_sha[:8], commit["sha"][:8], len(blobs)))

    head_remote = sha_map.get(revs[-1], revs[-1])
    if remote_head:
        code, result = req("PATCH", "/git/refs/heads/" + BRANCH, {"sha": head_remote})
    else:
        code, result = req("POST", "/git/refs", {"ref": "refs/heads/" + BRANCH,
                                                "sha": head_remote})
    if code not in (200, 201) or not result.get("object"):
        print("更新 refs/heads/%s 失败 HTTP %s: %s" % (BRANCH, code, result))
        return 1
    save_map(MAP_FILE, sha_map)
    save_map(TREE_MAP_FILE, tree_map)
    print("已更新 refs/heads/%s -> %s" % (BRANCH, result["object"]["sha"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
