# Java 爬虫 · TVBox 网盘订阅复刻

从零学 Java 爬虫到产出可用订阅的一套完整工程：TVBox 配置解析、Jsoup/HttpClient 采集、dex 插件字符串分析、本地 JSON 接口服务、网盘配置中心、盘搜与「搜完点开即播」直链代理，以及三批站点复刻。

仓库：<https://github.com/tlwj01/tvbox-java-spider>

## 订阅地址（推送成功后即可用）

| 用途 | 地址 |
| --- | --- |
| 全量聚合订阅（26 个复刻站点，推荐） | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica-subscription.json` |
| 第二批（综合 4 站）订阅 | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica2-subscription.json` |
| 第三批（17 个 4K 站）订阅 | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica3-subscription.json` |
| 原始网盘全量订阅（49 个 type:3 站点，未做可用性验证） | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/pan-subscription.json` |

订阅内站点均为 `type:1`、`spider` 留空，依赖仓库内的本地接口服务（8898）解析，不需要额外 jar。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/main/java/com/github/catvod/` | 爬虫教学、配置解析、并发调度、MD5、Spider 契约 |
| `src/main/java/com/github/catvod/api/` | 8899 影视采集 JSON 接口服务 |
| `src/main/java/com/github/catvod/pan/` | 8898 网盘服务：配置中心、盘搜、流代理、三批复刻站点 |
| `src/main/java/com/github/catvod/lab/` | dex 分析、Jar 检查、写文件原子化实验 |
| `config/` | TVBox 配置样本（已脱敏） |
| `lab/` | 抓取样本、dex、接口响应留档 |
| `output/` | 生成的订阅、站点对照、自检报告 |
| `scripts/` | 自动同步、脱敏、设备授权脚本 |
| `workflows/` | 可选的 GitHub Actions 自动更新模板 |
| `lib/` | 离线编译依赖（ECJ 3.36 / Jsoup / Gson / CFR） |

## 编译与运行

无需 Maven，本机 JDK 21 + 目录内 `lib/*.jar` 即可：

```bash
CP=$(python -c "import glob; print(';'.join(sorted(glob.glob('lib/*.jar'))))")
java -jar lib/ecj-3.36.0.jar -encoding UTF-8 -21 -cp "$CP" -d out $(find src/main/java -name '*.java')

java -cp "out;$CP" com.github.catvod.Runner                       # 教学全流程
java -cp "out;$CP" com.github.catvod.api.JsonApiServer           # 8899 影视接口
java -cp "out;$CP" com.github.catvod.pan.PanApiServer            # 8898 网盘接口 + 配置中心
java -cp "out;$CP" com.github.catvod.pan.ReplicaSites            # 重新生成三批订阅
```

Windows 下 classpath 用 `;`；Linux / GitHub Actions 下用 `:`（模板里已处理）。

## 配置 Cookie

复制空模板后填入自己的 Cookie，**该文件已被 .gitignore 排除，不会入库**：

```bash
cp output/pan/cookies.example.json output/pan/cookies.json
```

| 键 | 用途 |
| --- | --- |
| `quark_cookie` | 夸克播放必填（pan.quark.cn，可扫码） |
| `baidu_cookie` | 百度网盘播放 |
| `pan115_cookie` | 115 播放 |
| `uc_cookie` | 可选，UC 直链目前由接口自动下发 `__pugs` |
| `kfzy_cookie` | 夸父资源社抓帖；留空则走免登兜底 |
| `host` | 全盘搜主站，留空用 `https://quanpan.xyz` |

也可启动 `PanApiServer` 后访问 `http://127.0.0.1:8898/api/config` 用网页表单填写，写入为 UTF-8 无 BOM + 原子写 + 读回校验。

## TVBox「配置取得失败 443」排查

443 是 HTTPS 端口，这个报错基本都是「地址取不到」，不是订阅格式问题：

1. **填的是 GitHub raw 直链**：`raw.githubusercontent.com` 在国内常被阻断。改用上面的 jsDelivr 地址（`@main` 也可换成具体 commit 固定版本）；仍不通就加镜像前缀，例如
   `https://ghproxy.net/https://raw.githubusercontent.com/tlwj01/tvbox-java-spider/main/output/replica-subscription.json`。
2. **填的是 `127.0.0.1:8898`**：在电视/手机上 127.0.0.1 指向设备自己。改成电脑局域网 IP（如 `http://192.168.1.10:8898/api/replica/config`），并用 `java -cp "out;lib/*" com.github.catvod.pan.PanApiServer 8898 lan` 启动（默认只绑 127.0.0.1），同时放行 Windows 防火墙 8898 端口。
3. **仓库为空 / 分支名写错**：确认 `https://github.com/tlwj01/tvbox-java-spider` 的 `main` 分支已有 `output/*.json`；TVBox 里地址不要带多余空格或引号。
4. 订阅内站点是 `type:1`、`spider` 留空，需要 8898 服务在线才能搜索播放；只把订阅文件拷到电视上能加载配置但搜不到内容。

## 后续自动更新

两层机制，默认启用第一层：

1. **本地定时任务**（WorkBuddy 自动化，每 6 小时）：执行 `bash scripts/sync-github.sh` —— ECJ 编译 → 运行 `ReplicaSites` 重新生成订阅 → 推送前 `scripts/sanitize.py` 脱敏 → 有变更才 commit，再由 `scripts/gh-api-sync.py` 经 `api.github.com` 的 Git Data API 增量同步到 `main`（本机 `github.com` 的 git 通道被代理拦成 502，所以不走 `git push`）。日志在 `logs/sync.log`（不入库）。
2. **云端 GitHub Actions（可选）**：`workflows/auto-update.yml` 是模板，需要令牌具备 `workflow` 权限时再复制到 `.github/workflows/`：

```bash
mkdir -p .github/workflows && cp workflows/auto-update.yml .github/workflows/
```

默认不放进 `.github/workflows/`，避免首次推送因令牌缺少 workflow 权限被 GitHub 拒绝。

首次发布或凭据过期时：运行 `bash scripts/gh-device-poll.sh`（需先有 `.gh-device.json`），在 <https://github.com/login/device> 输入终端给出的 user_code 完成授权，令牌自动保存到 `.gh-token`；随后 `bash scripts/gh-publish.sh` 会创建仓库、把凭据写入 Git Credential Manager 并推送 `main`。之后定时同步即可无人值守运行。

## 安全与脱敏

- 仓库内所有 `cookie` 字段中的会话值（`bbs_sid` / `bbs_token` / `__pugs`）已清空，`DEFAULT_KFZY_COOKIE` 改为空串。
- `output/pan/cookies.json`、`lab/replica/aipanso-cookie.txt`、`.gh-device.json`、`.gh-token` 均被 `.gitignore` 排除。
- 每次自动同步提交前都会跑一次 `scripts/sanitize.py`，防止后续填入的 Cookie 被误提交。

## 已知限制

- 夸克 / 百度 / 115 的真实播放仍需用户自己的 Cookie；UC 免登链路可直连播放（实测 `206 video/mp4`）。
- `wexshinidie.guard` 加固壳未脱壳；爱盘搜 `/cv/` 动态码、观影/七味 PoW 未绕过，命中时由兜底库接管。
- `output/pan-subscription.json` 里的 49 个 `type:3` 原始站点未逐一做可用性验证。
- 8899 影视采集组的站点未合并进 8898 全量聚合订阅。
