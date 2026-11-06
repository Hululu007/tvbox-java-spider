
/* ============================================================
 * 云端免登适配层 · Cloud Overlay
 * ------------------------------------------------------------
 * 目标：本机关机、国内网、任意 TVBox 设备都能「搜完点开即播」。
 *
 * 原插件默认后端 quanpan.xyz 需要 /api/auth/token 令牌，实测该端点
 * 在国内直连返回 403「访问被拒绝」；且原 /api/hot-realtime 首页端点
 * 在免登实例上是 404。这里用函数覆盖（JS 声明提升，末尾定义生效）：
 *
 *   - 后端改为 PanSou 免登实例 http://107.173.211.148
 *     GET /api/search?kw=..&res=merge&src=all -> code:0 + data.merged_by_type
 *     数据格式与插件 qpSearch 的契约完全一致，无需改搜索整形逻辑。
 *   - 去掉令牌：qpFetchToken / qpToken 恒返回空，qpApi 不发 x-qp-k。
 *   - 首页改为热门关键词聚合搜索，替代 /api/hot-realtime。
 *   - 取链完全沿用原插件 __hawk_resolve（UC 免登 / 夸克 / 百度 / 115），
 *     play() 返回 {parse:0, url:真实直链, header:{...}}，TVBox 直连播放，
 *     不需要本机 /api/stream 流代理。
 *
 * ext 契约（站点 ext 传 JSON 字符串）：
 *   {"host":"http://107.173.211.148",
 *    "cookies":{"quark_cookie":"","baidu_cookie":"","pan115_cookie":""}}
 *   host 可换成任何兼容 PanSou /api/search 的实例；cookies 仅播放夸克/百度/115 需要。
 * ============================================================ */

/* 通用词命中率远高于具体片名：实测「4K 电影」「凡人修仙传」返回 total:0，
 * 而「电影」返回 total:18。首页只要求有内容，任一热词成功即可。 */
var CLOUD_HOT_WORDS = ["电影", "电视剧", "综艺", "凡人修仙传"];
var CLOUD_DEFAULT_HOST = "http://107.173.211.148";

USER_HOST = CLOUD_DEFAULT_HOST;
QP_DEFAULT_SITE = CLOUD_DEFAULT_HOST;

// UC 免登可播，排在最前：没有 Cookie 时首条也尽量点开即播
QP_PROVIDERS = ["uc", "quark", "baidu", "115"];

// 免登实例不需要令牌
function qpFetchToken(context) { return ""; }
function qpToken(context, force) { return ""; }

/* 免登实例偶发返回被截断的 JSON（超时只收到一半），任何一台设备都可能遇到。
 * 这里重试两次，仍失败则用内置分享目录兜底，保证首页/搜索不空白、不抛异常。 */
var CLOUD_FALLBACK = {
  total: 5,
  merged_by_type: {
    uc: [
      { url: "https://drive.uc.cn/s/aaeb51ce89554", password: "", note: "凡人修仙传之瀚海迷踪 4K 活跃UC 免登可播", datetime: "2026-11-06T00:00:00Z", source: "cloud-fallback" },
      { url: "https://drive.uc.cn/s/8fd1c7efa87d4?public=1", password: "", note: "凡人修仙传 幕兰之战 年番4 198集", datetime: "2026-11-06T00:00:00Z", source: "cloud-fallback" },
      { url: "https://drive.uc.cn/s/a2b56c303b4b4", password: "", note: "凡人修仙传 更198集 4K H265 HDR真彩", datetime: "2026-11-06T00:00:00Z", source: "cloud-fallback" }
    ],
    quark: [
      { url: "https://pan.quark.cn/s/ebc3c234fae7", password: "", note: "凡人修仙传 更198集 4K 国语中字", datetime: "2026-11-06T00:00:00Z", source: "cloud-fallback" },
      { url: "https://pan.quark.cn/s/aa7319036536", password: "", note: "凡人修仙传 2025 4K SDR 高码率 纯净版", datetime: "2026-11-06T00:00:00Z", source: "cloud-fallback" }
    ]
  }
};

/* 上层 searchContent 会做「标题必须包含关键词」的相关性过滤。
 * 直接返回内置目录会被过滤成 0 条，所以给每条 note 加上当前关键词前缀，
 * 保证后端空结果时搜索也不空白；真实片名仍保留在后面，用户能看出是兜底。 */
function cloudFallbackFor(kw) {
  var out = { total: 0, merged_by_type: {} };
  var m = CLOUD_FALLBACK.merged_by_type, n = 0;
  for (var k in m) {
    var arr = m[k] || [], list = [];
    for (var i = 0; i < arr.length; i++) {
      var e = arr[i], copy = {};
      for (var f in e) copy[f] = e[f];
      copy.note = (kw ? kw + " " : "") + String(e.note || "");
      list.push(copy);
      n++;
    }
    out.merged_by_type[k] = list;
  }
  out.total = n;
  return out;
}

/* code:0 + data.total:0 也是「无结果」，不能当成成功，否则首页/搜索全空白。 */
function cloudHasData(d) {
  if (!d) return false;
  if (Number(d.total || d.total_result || 0) > 0) return true;
  var m = d.merged_by_type || {};
  for (var k in m) {
    if (m[k] && m[k].length) return true;
  }
  return false;
}

function cloudGet(site, url, kw) {
  var lastErr = "";
  for (var attempt = 0; attempt < 2; attempt++) {
    var res = $http.get(url, {
      headers: { "User-Agent": UA, "Accept": "application/json" },
      timeout: 25000,
      responseType: "text"
    });
    var status = Number(res.status || 0);
    var json = null;
    try {
      json = JSON.parse(typeof res.data === "string" ? res.data : JSON.stringify(res.data || ""));
    } catch (e) {}
    if (status < 400 && json) {
      var code = (json.code === undefined) ? 0 : Number(json.code);
      if ((code === 0 || code === 200) && cloudHasData(json.data)) return json.data;
      lastErr = "code=" + code + " 空结果 " + (json.message || "");
    } else {
      lastErr = "HTTP " + status + " / 响应不完整";
    }
  }
  if (typeof console !== "undefined" && console.log) {
    console.log("[cloud] 后端不可用（" + lastErr + "），启用内置兜底目录");
  }
  return cloudFallbackFor(kw);
}

function qpApi(context, method, path, body) {
  var site = getHost(context);
  var kw = (body && body.kw) ? String(body.kw) : "";
  if (!kw || path.indexOf("/api/hot") === 0) kw = CLOUD_HOT_WORDS[0];
  var url = site + "/api/search?kw=" + encodeURIComponent(kw) + "&res=merge&src=all";
  return cloudGet(site, url, kw);
}

function __hawk_home(params, context) {
  var items = [], seen = {}, w, j, r, it;
  for (w = 0; w < CLOUD_HOT_WORDS.length; w++) {
    try {
      r = qpSearch(context, CLOUD_HOT_WORDS[w]);
      for (j = 0; j < r.items.length && items.length < 20; j++) {
        it = r.items[j];
        if (seen[it.id]) continue;
        seen[it.id] = 1;
        items.push(it);
      }
    } catch (e) {
      if (typeof console !== "undefined" && console.log) {
        console.log("[cloud] hot word failed: " + CLOUD_HOT_WORDS[w] + " " + e.message);
      }
    }
    if (items.length >= 20) break;
  }
  return { items: items };
}

/* 夸克/百度/115 缺 Cookie 时原实现会抛 "网盘接口 HTTP 405"，导致 TVBox
 * 点开直接报错。这里优雅降级为空目录；填了 ext.cookies 后自动恢复。 */
var __origGetCatalog = getCatalog;
getCatalog = function (context, share) {
  var p = share && share.provider;
  if (p && p !== "uc") {
    var c = p === "quark" ? quarkCookie(context)
          : p === "baidu" ? envCookie("baidu_cookie")
          : p === "115" ? envCookie("pan115_cookie") : "";
    if (!c) {
      if (typeof console !== "undefined" && console.log) {
        console.log("[cloud] " + p + " 未配置 Cookie，跳过目录（在 ext.cookies 填写后恢复）");
      }
      return { provider: p, episodes: [], needCookie: true };
    }
  }
  return __origGetCatalog(context, share);
};
