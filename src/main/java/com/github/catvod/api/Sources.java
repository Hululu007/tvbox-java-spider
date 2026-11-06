package com.github.catvod.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 5 课：把用户给的一批真实站点统一成一个 JSON 接口的数据层。
 *
 * 三类数据源：
 *   1. cms   —— 苹果 CMS v10 采集接口（cj.lziapi.com），返回 code/msg/page/pagecount/total/list/class
 *   2. html  —— 普通影视站，用 Jsoup 抽详情链接（seedhub / 午夜 / 量子资源网）
 *   3. spa   —— 前端渲染站，首页无静态条目，如实返回空列表
 *
 * 所有输出统一成苹果 CMS JSON，TVBox 的 type:1 站点可以直接订阅。
 */
public final class Sources {

    public static final String CMS = "https://cj.lziapi.com/api.php/provide/vod/";
    public static final String CMS_BAK = "https://cj.lzcaiji.com/api.php/provide/vod/";
    public static final String ART = "https://cj.lziapi.com/api.php/provide/art/";
    public static final String ACTOR = "https://cj.lziapi.com/api.php/provide/actor/";
    public static final String PLAYER = "https://lziplayer.com/?url=";
    public static final String XML_LIANGZI = "https://cj.lziapi.com/api.php/provide/vod/from/liangzi/at/xml/";
    public static final String XML_LZM3U8 = "https://cj.lziapi.com/api.php/provide/vod/from/lzm3u8/at/xml/";
    public static final String XML_DOWN = "https://cj.lziapi.com/api.php/providedown/vod/at/xml/";

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** key / 显示名 / 原始 URL / 类型 / 适配器 / 备注 */
    public record Site(String key, String name, String url, String kind, String adapter, String note) {}

    /** 用户给的 26 个目标，去重后仍全部保留，含不可达的（如实标注） */
    public static final List<Site> SITES = List.of(
        new Site("wogg",      "玩偶哥哥",       "https://www.wogg.net/",                 "html", "none", "首页显示「维护中」"),
        new Site("xiaoban",   "小斑4K",        "http://103.40.14.172:12512/",          "image", "none", "随机图片接口，页面注明自用勿爬"),
        new Site("labi",      "蜡笔",           "http://tvpanpan.site/",                "html", "none", "页面注明自用勿爬"),
        new Site("dbku",      "独播库",         "http://www.dbku.tv/",                  "html", "none", "探测超时 13.7s"),
        new Site("duoduo",    "多多4K",        "https://pan.yydsys.de/",               "image", "none", "随机图片接口"),
        new Site("quanpan",   "全盘搜",         "https://quanpan.xyz/",                 "spa",  "spa",  "前端渲染，首页无静态条目"),
        new Site("muou",      "木偶",           "https://www.wogg.net/",                "html", "none", "与玩偶哥哥同域，维护中"),
        new Site("wogg-mirror","玩偶镜像",      "https://333.333291.xyz/",              "html", "none", "首页显示「跑路了」"),
        new Site("dami",      "大米",           "http://damxq22.com/",                  "html", "none", "HTTP 502"),
        new Site("seedhub",   "SeedHub",       "https://sidhub.cc/",                   "html", "html", "详情链接 /movies/N/"),
        new Site("seedhub-b", "SeedHub备用",   "https://seedog.cc/seedhub",            "html", "none", "HTTP 404"),
        new Site("wuye",      "午夜影视",       "https://xu--5us88dtla.wuye.mom/",      "html", "html", "详情链接 /voddetail/N.html"),
        new Site("lziroot",   "量子资源网",     "https://cj.lziapi.com/",               "html", "html", "详情链接 /index.php/vod/detail/id/N.html"),
        new Site("lzi",       "量子JSON采集",   CMS,                                     "cms",  "cms",  "主数据源，162833 条"),
        new Site("liangzi",   "量子liangzi",   XML_LIANGZI,                            "xml",  "cms",  "XML 三路之一，按 play_from 过滤"),
        new Site("lzm3u8",    "量子lzm3u8",    XML_LZM3U8,                             "xml",  "cms",  "XML 三路之一，按 play_from 过滤"),
        new Site("lzi-down",  "量子下载XML",    XML_DOWN,                               "xml",  "cms",  "providedown 路径"),
        new Site("lzi-bak",   "量子JSON备用",   CMS_BAK,                                "cms",  "none", "SSL EOF，不可用"),
        new Site("art",       "量子文章接口",   ART,                                     "cms",  "art",  "total 6548"),
        new Site("actor",     "量子演员接口",   ACTOR,                                  "cms",  "actor","total 4436"),
        new Site("player",    "M3U8解析接口",  "https://lziplayer.com/?url=",          "parse","parse","lziplayer.com/?url="),
        new Site("dushe",     "毒蛇",           "http://m.xnhrsb.com/",                 "html", "none", "HTTP 408"),
        new Site("libvio",    "Libvio",        "https://libvio.cam/",                  "html", "html", "SSL 握手偶发超时"),
        new Site("huajuan",   "花卷",           "http://hjzhencai.top/",                "html", "none", "HTTP 502"),
        new Site("huban",     "虎斑",           "http://103.40.14.172:16968/",          "image","none", "随机图片接口，页面注明自用勿爬"),
        new Site("shandian",  "闪电",           "https://shandian.blog/",               "html", "none", "探测超时（用户已注明挂了）")
    );

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Sources() {}

    public static Site find(String key) {
        for (Site s : SITES) {
            if (s.key().equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }

    /* ---------------- 通用 HTTP ---------------- */

    public static String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .GET().build();
        HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + " -> " + url);
        }
        byte[] body = r.body();
        String ct = r.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (ct.contains("gbk") || ct.contains("gb2312") || ct.contains("gb18030")) {
            return new String(body, java.nio.charset.Charset.forName("GBK"));
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /* ---------------- 苹果 CMS ---------------- */

    /** 标准列表：ac=list，支持 t / pg / wd / h，fromFilter 按 vod_play_from 本地过滤 */
    public static JsonObject cmsList(String t, String pg, String wd, String h, String fromFilter)
            throws Exception {
        StringBuilder u = new StringBuilder(CMS).append("?ac=list&pg=").append(empty(pg) ? "1" : pg);
        if (!empty(t))  u.append("&t=").append(enc(t));
        if (!empty(wd)) u.append("&wd=").append(enc(wd));
        if (!empty(h))  u.append("&h=").append(enc(h));
        JsonObject src = JsonParser.parseString(fetch(u.toString())).getAsJsonObject();
        return normalizeList(src, fromFilter);
    }

    /** 详情：ac=detail&ids=，返回带 vod_play_url 的完整条目 */
    public static JsonObject cmsDetail(String ids) throws Exception {
        String url = CMS + "?ac=detail&ids=" + enc(empty(ids) ? "0" : ids);
        JsonObject src = JsonParser.parseString(fetch(url)).getAsJsonObject();
        JsonObject out = base();
        copy(src, out, "page", "pagecount", "limit", "total");
        JsonArray list = new JsonArray();
        JsonArray srcList = arr(src, "list");
        for (JsonElement e : srcList) {
            list.add(toVod(e.getAsJsonObject(), null));
        }
        out.add("list", list);
        return out;
    }

    public static JsonObject cmsOther(String which, String pg) throws Exception {
        String base = "actor".equals(which) ? ACTOR : ART;
        String url = base + "?ac=list&pg=" + (empty(pg) ? "1" : pg);
        JsonObject src = JsonParser.parseString(fetch(url)).getAsJsonObject();
        return normalizeList(src, null);
    }

    private static JsonObject normalizeList(JsonObject src, String fromFilter) {
        JsonObject out = base();
        copy(src, out, "page", "pagecount", "limit", "total");
        if (src.has("class") && src.get("class").isJsonArray()) {
            out.add("class", src.getAsJsonArray("class").deepCopy());
        }
        JsonArray list = new JsonArray();
        for (JsonElement e : arr(src, "list")) {
            JsonObject v = e.getAsJsonObject();
            if (!empty(fromFilter) && !str(v, "vod_play_from").contains(fromFilter)) {
                continue;
            }
            list.add(toVod(v, fromFilter));
        }
        out.add("list", list);
        if (!empty(fromFilter)) {
            out.addProperty("total", list.size());
            out.addProperty("msg", "按 play_from=" + fromFilter + " 过滤");
        }
        return out;
    }

    /** 统一成 TVBox 需要的 Vod 字段，缺字段补空，避免壳解析报错 */
    private static JsonObject toVod(JsonObject v, String fromFilter) {
        JsonObject o = new JsonObject();
        for (String k : List.of("vod_id", "vod_name", "type_id", "type_name", "vod_en",
                "vod_time", "vod_remarks", "vod_play_from", "vod_pic", "vod_area", "vod_lang",
                "vod_year", "vod_actor", "vod_director", "vod_class", "vod_score", "vod_state",
                "vod_content", "vod_play_url", "vod_down_url")) {
            if (v.has(k) && !v.get(k).isJsonNull()) {
                o.add(k, v.get(k).deepCopy());
            }
        }
        if (!o.has("vod_id") || o.get("vod_id").getAsString().isEmpty()) {
            o.addProperty("vod_id", "0");
        }
        if (!o.has("vod_name")) {
            o.addProperty("vod_name", "");
        }
        if (!o.has("vod_pic")) {
            o.addProperty("vod_pic", "");
        }
        if (!o.has("type_name")) {
            o.addProperty("type_name", "");
        }
        // play_from 多个来源用 $$$ 分隔，这里标记出实际过滤的那一路
        if (!empty(fromFilter)) {
            o.addProperty("vod_play_from", fromFilter);
        }
        return o;
    }

    /** 原样返回 XML 三路之一 */
    public static String xml(String which) throws Exception {
        String url = switch (which) {
            case "liangzi" -> XML_LIANGZI;
            case "down" -> XML_DOWN;
            default -> XML_LZM3U8;
        };
        return fetch(url);
    }

    /* ---------------- HTML 站点 ---------------- */

    /** 用 Jsoup 抽详情链接，输出与 CMS 同构的 list */
    public static JsonObject htmlList(Site site, String pg, String wd) throws Exception {
        JsonObject out = base();
        out.addProperty("page", empty(pg) ? 1 : Integer.parseInt(pg));
        out.addProperty("pagecount", 1);
        out.addProperty("limit", "40");
        String selector;
        switch (site.key()) {
            case "seedhub" -> selector = "a[href^=/movies/]";
            case "wuye" -> selector = "a[href^=/voddetail/]";
            case "lziroot" -> selector = "a[href*=/index.php/vod/detail/]";
            case "libvio" -> selector = "a[href*=/voddetail/], a[href*=/detail/]";
            default -> selector = "";
        }
        JsonArray list = new JsonArray();
        if ("spa".equals(site.adapter()) || selector.isEmpty()) {
            out.addProperty("msg", site.note());
            out.addProperty("total", 0);
            out.add("list", list);
            return out;
        }
        String html = fetch(site.url());
        Document doc = Jsoup.parse(html, site.url());
        Map<String, JsonObject> uniq = new LinkedHashMap<>();
        for (Element a : doc.select(selector)) {
            String href = a.attr("abs:href");
            if (href.isEmpty()) {
                continue;
            }
            // 片名优先取图片 alt / title，最后才用锚文本。
            // 苹果 CMS 模板常把「第1集」这类集数链接也指向 voddetail，必须排除。
            String name = "";
            Element img = a.selectFirst("img[alt]");
            if (img != null && !img.attr("alt").trim().isEmpty()) {
                name = img.attr("alt").trim();
            }
            if (name.isEmpty()) {
                name = a.attr("title").trim();
            }
            if (name.isEmpty()) {
                name = a.text().trim();
            }
            if (name.isEmpty() && a.parent() != null) {
                name = a.parent().text().trim();
            }
            if (name.isEmpty() || name.matches("第\\s*\\d+\\s*集|更新至第?\\s*\\d+\\s*集|全集")) {
                continue;
            }
            if (!empty(wd) && !name.toLowerCase().contains(wd.toLowerCase())) {
                continue;
            }
            String id = href.replaceAll(".*?(\\d+)\\D*$", "$1");
            if (id.equals(href)) {
                id = Integer.toHexString(href.hashCode());
            }
            JsonObject v = new JsonObject();
            v.addProperty("vod_id", id);
            v.addProperty("vod_name", name.length() > 60 ? name.substring(0, 60) : name);
            v.addProperty("vod_pic", img != null ? img.attr("abs:src") : "");
            v.addProperty("vod_remarks", site.name());
            v.addProperty("type_name", site.name());
            v.addProperty("vod_play_from", site.key());
            v.addProperty("vod_play_url", "打开原页$" + href);
            uniq.putIfAbsent(href, v);
            if (uniq.size() >= 40) {
                break;
            }
        }
        for (JsonObject v : uniq.values()) {
            list.add(v);
        }
        out.addProperty("total", list.size());
        out.addProperty("msg", uniq.isEmpty() ? "未抽到条目：" + site.note() : site.name() + " 首页");
        out.add("list", list);
        return out;
    }

    /* ---------------- 探测报告 ---------------- */

    public static JsonObject probeReport() {
        return readJson("output/probe-report.json");
    }

    public static JsonObject samples() {
        return readJson("output/source-samples.json");
    }

    private static JsonObject readJson(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                return new JsonObject();
            }
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", e.toString());
            return o;
        }
    }

    /** 站点清单：把 SITES 与真实探测状态合并 */
    public static JsonObject siteInventory() {
        JsonObject probe = probeReport();
        Map<String, JsonObject> byUrl = new LinkedHashMap<>();
        if (probe.has("results")) {
            for (JsonElement e : probe.getAsJsonArray("results")) {
                JsonObject r = e.getAsJsonObject();
                byUrl.put(str(r, "url"), r);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", SITES.size());
        root.addProperty("probed_at", str(probe, "probed_at"));
        JsonArray arr = new JsonArray();
        for (Site s : SITES) {
            JsonObject o = new JsonObject();
            o.addProperty("key", s.key());
            o.addProperty("name", s.name());
            o.addProperty("url", s.url());
            o.addProperty("kind", s.kind());
            o.addProperty("adapter", s.adapter());
            o.addProperty("note", s.note());
            JsonObject p = byUrl.get(s.url());
            if (p != null) {
                o.addProperty("http_status", p.has("status") ? p.get("status").getAsInt() : 0);
                o.addProperty("probe_title", str(p, "title"));
                o.addProperty("probe_bytes", p.has("bytes") ? p.get("bytes").getAsInt() : 0);
                o.addProperty("available", p.has("status") && p.get("status").getAsInt() >= 200
                        && p.get("status").getAsInt() < 400);
            } else {
                o.addProperty("available", false);
            }
            arr.add(o);
        }
        root.add("sites", arr);
        return root;
    }

    /* ---------------- 小工具 ---------------- */

    private static JsonObject base() {
        JsonObject o = new JsonObject();
        o.addProperty("code", 1);
        o.addProperty("msg", "ok");
        return o;
    }

    private static void copy(JsonObject src, JsonObject dst, String... keys) {
        for (String k : keys) {
            if (src.has(k)) {
                dst.add(k, src.get(k).deepCopy());
            }
        }
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
