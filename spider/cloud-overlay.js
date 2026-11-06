
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

var CLOUD_HOT_WORDS = ["4K 电影", "凡人修仙传", "电视剧 4K", "纪录片 4K"];
var CLOUD_DEFAULT_HOST = "http://107.173.211.148";

USER_HOST = CLOUD_DEFAULT_HOST;
QP_DEFAULT_SITE = CLOUD_DEFAULT_HOST;

// UC 免登可播，排在最前：没有 Cookie 时首条也尽量点开即播
QP_PROVIDERS = ["uc", "quark", "baidu", "115"];

// 免登实例不需要令牌
function qpFetchToken(context) { return ""; }
function qpToken(context, force) { return ""; }

function cloudGet(site, url) {
  var res = $http.get(url, {
    headers: { "User-Agent": UA, "Accept": "application/json" },
    timeout: 30000,
    responseType: "text"
  });
  var status = Number(res.status || 0);
  var json = null;
  try {
    json = JSON.parse(typeof res.data === "string" ? res.data : JSON.stringify(res.data || ""));
  } catch (e) {}
  if (status >= 400) throw new Error("云端搜索接口 HTTP " + status);
  if (!json) throw new Error("云端搜索接口返回非 JSON");
  var code = (json.code === undefined) ? 0 : Number(json.code);
  if (code !== 0 && code !== 200) {
    throw new Error("云端搜索接口返回 " + (json.message || code));
  }
  return json.data || {};
}

function qpApi(context, method, path, body) {
  var site = getHost(context);
  var kw = (body && body.kw) ? String(body.kw) : "";
  if (!kw || path.indexOf("/api/hot") === 0) kw = CLOUD_HOT_WORDS[0];
  var url = site + "/api/search?kw=" + encodeURIComponent(kw) + "&res=merge&src=all";
  return cloudGet(site, url);
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
