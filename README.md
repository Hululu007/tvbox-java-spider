# Java 爬虫 · TVBox 网盘订阅复刻

从零学 Java 爬虫到产出可用订阅的一套完整工程：TVBox 配置解析、Jsoup/HttpClient 采集、dex 插件字符串分析、本地 JSON 接口服务、网盘配置中心、盘搜与「搜完点开即播」直链代理，以及三批站点复刻。

仓库：<https://github.com/tlwj01/tvbox-java-spider>

## 订阅地址

**推荐：本机关机也能用 / 国内网直连**（零本机依赖，见下一节）：

```
https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/cloud-subscription.json
```

| 用途 | 地址 |
| --- | --- |
| **云端版（27 站点，推荐）** | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/cloud-subscription.json` |
| 本机/局域网版（26 个复刻站点，需 8898 常开） | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica-subscription.json` |
| 第二批（综合 4 站）订阅 | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica2-subscription.json` |
| 第三批（17 个 4K 站）订阅 | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/replica3-subscription.json` |
| 原始网盘全量订阅（49 个 type:3 站点，未做可用性验证） | `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/pan-subscription.json` |

本机/局域网版的站点是 `type:1`，`api` 指向 `127.0.0.1:8898`，**必须电脑常开**；云端版是 `type:3`，在电视/手机上自己跑 JS 插件，与电脑无关。

## 本机关机也能用（国内网）

云端版把「服务端」整个移到 TVBox 客户端，链路里没有这台电脑：

```
TVBox ──jsDelivr──> 订阅 JSON + cloud_spider.js（国内直连 200）
    ──直连────────> PanSou 免登实例 /api/search（国内直连 200）
    ──直连────────> UC / 夸克 官方接口取真实直链
    ──直连播放────> 网盘 OSS（play 返回带 header 的直链，无需流代理）
```

- 订阅里 **27 个站点全部 `type:3`**，`api` 指向 `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/spider/cloud_spider.min.js`（仓库里只存 `cloud_spider.js`，`.min.js` 由 jsDelivr 用 Terser 实时压缩：73KB → 45KB，国内下载更稳，也绕开 `@main` 的 7 天缓存）。零 `127.0.0.1`、零本机 8898、零 `play_proxy`。备用未压缩地址把 `.min.js` 换回 `.js` 即可。
- 插件 = `lab/quanpan/quanpan_spider.js` + `spider/cloud-overlay.js`。覆盖层把默认后端从需要令牌且国内返回 403 的 `quanpan.xyz` 换成免登 PanSou 实例，并让首页用热门词聚合；取链逻辑原样保留，`play()` 直接返回 `{parse:0, url:直链, header:{...}}`。
- UC 免登可播；夸克/百度/115 需 Cookie。填法：在订阅 JSON 里把对应站点的 `ext` 改成
  `{"host":"http://107.173.211.148","site":"HuaJuan4K","cookies":{"quark_cookie":"你的 Cookie"}}`（`ext` 是 JSON 字符串）。**不要提交带 Cookie 的订阅**，`scripts/sanitize.py` 会清空会话值。
- `spider` 字段沿用原配置的 JS 引擎 jar（`yi.jar`，国内实测 302→200）；壳自带 JS 引擎时可留空。

国内网备用地址（`raw.githubusercontent.com` / `ghproxy.net` 直连实测不通，别用）：

```
https://fastly.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/cloud-subscription.json
https://gcore.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/cloud-subscription.json
```

本机复核：`python scripts/build-cloud.py` 重新生成产物，`node scripts/verify-cloud-spider.js 凡人修仙传` 模拟 TVBox 跑 `init → home → search → detail → play`。

| | 云端版 | 本机/局域网版 |
| --- | --- | --- |
| 电脑关机 | 可用 | 不可用 |
| 跨设备 / 外网 | 可用 | 需同一局域网 |
| 逐站复刻后端 | 统一走免登聚合 | 26 个站点各自适配器 + 兜底目录 |
| 点开即播 | UC 免登直链；夸克需 Cookie | 本机流代理自动补鉴权头 |

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
4. **本机关机 / 不在同一局域网**：用 `output/cloud-subscription.json`（云端版），里面没有 `127.0.0.1`；`replica-subscription.json` 是本机版，站点 `type:1` 且 `api` 指向 8898，电脑不开机就搜不到内容。

## Hub 免费版 4.0.1「报天神」排查

「天神」**不是错误码**，是另一份第三方 TVBox 配置 `https://0.wdzb.eu.cc/tvbox/天神.json`
里的站点名「🌹天神｜小屋」（104 个站点、首个 `api` 为 `csp_Market` 配置中心）。
该 APK（Android TVBox/FongMi 系，带 QuickJS + Rhino + Python 运行时）本体、
`yi.jar` 引擎包、本仓库所有订阅里都检索不到这两个字，所以它只能来自 App
当前实际加载的那份配置 —— 也就是**你填的订阅地址没生效**。

处理顺序：

1. 在 App 首页看第一个源是不是「🌹天神｜小屋」；是就说明仍在用旧配置。
2. 「设置 → 配置地址」清空历史/粘贴内容（Hub 系常见「粘贴内容 > 配置地址」优先级），
   只填 `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/cloud-subscription.json`。
3. 若仍回退，用「粘贴配置」把
   `output/hub-subscription.json` 的内容整份粘进去（1 个站点、无 `warningText`），
   绕开 URL 缓存与云控回退；这个版本地址：
   `https://cdn.jsdelivr.net/gh/tlwj01/tvbox-java-spider@main/output/hub-subscription.json`。
   自带 JS 引擎的分支若加载 `spider` jar 失败，改用
   `output/hub-nospider-subscription.json`。
4. 还显示「天神」就是 App 清数据/云控强制配置：清 App 数据后重试，或换无云控版本。
5. 备用 CDN：`fastly.jsdelivr.net` / `gcore.jsdelivr.net` 同路径；电视端用手机热点对比，
   排除家宽 DNS 拦截 jsDelivr。

## 后续自动更新

两层机制，默认启用第一层：

1. **本地定时任务**（WorkBuddy 自动化，每 6 小时）：执行 `bash scripts/sync-github.sh` —— ECJ 编译 → `ReplicaSites` 重生成三批订阅 → `scripts/build-cloud.py` 重生成云端订阅与 `cloud_spider.js` → `scripts/build-hub.py` 重生成 Hub 兼容订阅 → `scripts/sanitize.py` 脱敏 → 有变更才 commit，再由 `scripts/gh-api-sync.py` 经 `api.github.com` 的 Git Data API 增量同步到 `main`（本机 `github.com` 的 git 通道被代理拦成 502，所以不走 `git push`）。日志在 `logs/sync.log`（不入库）。**云端产物推上仓库后，本机是否开机都不影响其它设备使用**。
2. **云端 GitHub Actions（可选）**：`workflows/auto-update.yml` 是模板，需要令牌具备 `workflow` 权限时再复制到 `.github/workflows/`：

```bash
mkdir -p .github/workflows && cp workflows/auto-update.yml .github/workflows/
```

同步成功后脚本会调用 `purge.jsdelivr.net` 刷新 `@main` 的订阅与插件缓存（jsDelivr 默认缓存 7 天，不刷则电视端仍拿旧版）。

默认不放进 `.github/workflows/`，避免首次推送因令牌缺少 workflow 权限被 GitHub 拒绝。

首次发布或凭据过期时：运行 `bash scripts/gh-device-poll.sh`（需先有 `.gh-device.json`），在 <https://github.com/login/device> 输入终端给出的 user_code 完成授权，令牌自动保存到 `.gh-token`；随后 `bash scripts/gh-publish.sh` 会创建仓库、把凭据写入 Git Credential Manager 并推送 `main`。之后定时同步即可无人值守运行。

## 安全与脱敏

- 仓库内所有 `cookie` 字段中的会话值（`bbs_sid` / `bbs_token` / `__pugs`）已清空，`DEFAULT_KFZY_COOKIE` 改为空串。
- `output/pan/cookies.json`、`lab/replica/aipanso-cookie.txt`、`.gh-device.json`、`.gh-token` 均被 `.gitignore` 排除。
- 每次自动同步提交前都会跑一次 `scripts/sanitize.py`，防止后续填入的 Cookie 被误提交。

## 已知限制

- 夸克 / 百度 / 115 的真实播放仍需用户自己的 Cookie；UC 免登链路可直连播放（本机版实测 `206 video/mp4`）。
- 云端版依赖免登 PanSou 实例的分享数据，聚合来的分享链接有时效（UC 可能回 `41012 好友已取消了分享`）；插件已验证 `init / home / search` 与取链请求格式，播放需在 TVBox 上用仍有效的分享实测。夸克等缺 Cookie 时会优雅跳过目录而不是报错。
- `wexshinidie.guard` 加固壳未脱壳；爱盘搜 `/cv/` 动态码、观影/七味 PoW 未绕过，命中时由兜底库接管。
- `output/pan-subscription.json` 里的 49 个 `type:3` 原始站点未逐一做可用性验证。
- 8899 影视采集组的站点未合并进 8898 全量聚合订阅。
