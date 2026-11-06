package com.github.catvod.api;

import com.github.catvod.utils.SafeFiles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 5 课：把 26 个真实目标做成一个可订阅的 JSON 接口服务。
 *
 * 启动：
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp "out;lib/jsoup-1.17.2.jar;lib/gson-2.10.1.jar" \
 *        com.github.catvod.api.JsonApiServer 8899
 *
 * 两类端点：
 *   A. 苹果 CMS 兼容（TVBox type:1 站点可直接填 api）
 *      /api/cms/<key>/?ac=list|detail&t=&pg=&wd=&h=&ids=
 *      /api.php/provide/vod/                      量子默认 JSON
 *      /api.php/provide/vod/from/<x>/at/xml/      三路 XML 原样
 *      /api.php/provide/art/  /api.php/provide/actor/
 *   B. 教学用 REST（TVBox Spider 五个方法的返回结构）
 *      /api/home  /api/category  /api/search  /api/detail  /api/play
 *      辅助：/api/sites  /api/probe  /api/samples  /api/config  /api/xml
 *
 * 设计约定：任何上游失败都返回合法空 JSON（code=1/list=[]），绝不给壳抛 5xx。
 */
public final class JsonApiServer {

    private static final GsonBuilder GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping();
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final long TTL = 60_000L;
    private static final Pattern XML_PATH =
            Pattern.compile("/api\\.php/provide/vod/from/([^/]+)/at/([^/]+)/?");

    private record Cached(long ts, String body, String type) {}

    private static int PORT = 8899;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            PORT = Integer.parseInt(args[0]);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", JsonApiServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        // 启动即把订阅配置落盘（复用第 4 课的原子 JSON 写入）
        Path cfg = SafeFiles.writeJson(Paths.get("output/tvbox-sites.json"), buildConfig());
        System.out.println("=== JSON 接口服务已启动 ===");
        System.out.println("  base      : http://127.0.0.1:" + PORT);
        System.out.println("  订阅配置  : " + cfg.toAbsolutePath());
        System.out.println("  站点数    : " + Sources.SITES.size()
                + " | 可订阅站点: " + buildConfig().getAsJsonArray("sites").size());
        System.out.println("  端点      : / /api/sites /api/probe /api/samples /api/config");
        System.out.println("            : /api/home /api/category /api/search /api/detail /api/play");
        System.out.println("            : /api/cms/<key>/ /api.php/provide/vod/... /api/xml");
    }

    /* ---------------- 路由 ---------------- */

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        try {
            Result r = route(path, q);
            reply(ex, 200, r.type, r.body);
        } catch (Exception e) {
            // 上游失败也要给壳一个合法结构
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[warn] " + path + " -> " + msg);
            JsonObject o = new JsonObject();
            o.addProperty("code", 1);
            o.addProperty("msg", "上游不可用，已返回空列表：" + msg);
            o.addProperty("page", 1);
            o.addProperty("pagecount", 1);
            o.addProperty("limit", "20");
            o.addProperty("total", 0);
            o.add("list", new JsonArray());
            reply(ex, 200, "json", pretty(o));
        }
    }

    private record Result(String type, String body) {}

    private static Result route(String path, Map<String, String> q) throws Exception {
        if (path.equals("/") || path.isEmpty()) {
            return json(index());
        }
        switch (path) {
            case "/api/sites":   return json(Sources.siteInventory());
            case "/api/probe":   return json(Sources.probeReport());
            case "/api/samples": return json(Sources.samples());
            case "/api/config":  return json(buildConfig());
            case "/api/xml":     return json(xmlToJson(q.getOrDefault("from", "lzm3u8")));
            default: break;
        }
        if (path.startsWith("/api/cms/")) {
            String key = path.substring("/api/cms/".length()).replaceAll("/.*$", "");
            return json(cmsDispatch(key, q));
        }
        if (path.startsWith("/api.php/")) {
            return phpCompat(path, q);
        }
        switch (path) {
            case "/api/home":     return json(home(q));
            case "/api/category": return json(category(q));
            case "/api/search":   return json(search(q));
            case "/api/detail":   return json(detail(q));
            case "/api/play":     return json(play(q));
            default:
                JsonObject o = new JsonObject();
                o.addProperty("error", "unknown endpoint");
                o.addProperty("path", path);
                o.add("endpoints", index().getAsJsonArray("endpoints"));
                return json(o);
        }
    }

    /* ---------------- 苹果 CMS 兼容层 ---------------- */

    private static JsonObject cmsDispatch(String key, Map<String, String> q) {
        String ck = "cms:" + key + ":" + q;
        return cachedJson(ck, () -> {
            Sources.Site s = Sources.find(key);
            String ac = q.getOrDefault("ac", "list");
            String pg = q.getOrDefault("pg", "1");
            String wd = q.getOrDefault("wd", "");
            String t  = q.getOrDefault("t", "");
            String h  = q.getOrDefault("h", "");
            if (s == null) {
                return emptyCms("未知站点 " + key);
            }
            switch (s.adapter()) {
                case "cms": {
                    String filter = "liangzi".equals(key) ? "liangzi"
                            : "lzm3u8".equals(key) ? "lzm3u8" : "";
                    return "detail".equals(ac)
                            ? Sources.cmsDetail(q.getOrDefault("ids", ""))
                            : Sources.cmsList(t, pg, wd, h, filter);
                }
                case "art":
                case "actor":
                    return Sources.cmsOther(s.adapter(), pg);
                case "html":
                case "spa":
                    return "detail".equals(ac)
                            ? htmlDetail(s, q.getOrDefault("ids", ""))
                            : Sources.htmlList(s, pg, wd);
                default:
                    return emptyCms(s.note());
            }
        });
    }

    private static Result phpCompat(String path, Map<String, String> q) throws Exception {
        if (path.equals("/api.php/provide/art/") || path.equals("/api.php/provide/actor/")) {
            String which = path.contains("actor") ? "actor" : "art";
            return json(Sources.cmsOther(which, q.getOrDefault("pg", "1")));
        }
        if (path.equals("/api.php/providedown/vod/at/xml/")) {
            return new Result("xml", Sources.fetch(Sources.XML_DOWN));
        }
        Matcher m = XML_PATH.matcher(path);
        if (m.matches()) {
            String from = m.group(1);
            String at = m.group(2);
            String xml = Sources.xml(from);
            if ("json".equalsIgnoreCase(at) || "json".equalsIgnoreCase(q.get("at"))) {
                return json(xmlToJson(from));
            }
            return new Result("xml", xml);
        }
        if (path.startsWith("/api.php/provide/vod/")) {
            return json(cmsDispatch("lzi", q));
        }
        return json(emptyCms("未知采集路径 " + path));
    }

    /** XML 三路 → 标准 CMS JSON，用 Jsoup xmlParser 正确处理 CDATA */
    private static JsonObject xmlToJson(String from) {
        String ck = "xml:" + from;
        return cachedJson(ck, () -> {
            String xml = Sources.xml(from);
            Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
            JsonObject out = baseCms();
            JsonArray list = new JsonArray();
            for (Element v : doc.select("video")) {
                JsonObject o = new JsonObject();
                o.addProperty("vod_id", text(v, "id"));
                o.addProperty("vod_name", text(v, "name"));
                o.addProperty("type_id", text(v, "tid"));
                o.addProperty("type_name", text(v, "type"));
                o.addProperty("vod_time", text(v, "last"));
                o.addProperty("vod_remarks", text(v, "note"));
                o.addProperty("vod_play_from", from);
                o.addProperty("vod_pic", text(v, "pic"));
                list.add(o);
            }
            out.add("list", list);
            out.addProperty("total", list.size());
            out.addProperty("msg", "XML " + from + " 转 JSON");
            return out;
        });
    }

    private static String text(Element parent, String tag) {
        Element e = parent.selectFirst(tag);
        return e == null ? "" : e.text().trim();
    }

    /* ---------------- TVBox Spider 五个方法对应端点 ---------------- */

    private static JsonObject home(Map<String, String> q) {
        String key = q.getOrDefault("site", "lzi");
        JsonObject src = cmsDispatch(key, Map.of("ac", "list", "pg", "1"));
        JsonObject out = new JsonObject();
        JsonArray classes = new JsonArray();
        if (src.has("class")) {
            for (var e : src.getAsJsonArray("class")) {
                JsonObject c = e.getAsJsonObject();
                JsonObject o = new JsonObject();
                o.addProperty("type_id", c.has("type_id") ? c.get("type_id").getAsString() : "1");
                o.addProperty("type_name", c.has("type_name") ? c.get("type_name").getAsString() : "");
                classes.add(o);
            }
        }
        if (classes.isEmpty()) {
            classes.add(cls("1", "首页"));
        }
        out.add("class", classes);
        out.add("list", src.getAsJsonArray("list").deepCopy());
        out.add("filters", new JsonObject());
        return out;
    }

    private static JsonObject category(Map<String, String> q) {
        Map<String, String> p = new LinkedHashMap<>(q);
        p.put("ac", "list");
        JsonObject src = cmsDispatch(p.getOrDefault("site", "lzi"), p);
        JsonObject out = new JsonObject();
        out.add("list", src.getAsJsonArray("list").deepCopy());
        for (String k : List.of("page", "pagecount", "limit", "total")) {
            if (src.has(k)) out.add(k, src.get(k).deepCopy());
        }
        return out;
    }

    private static JsonObject search(Map<String, String> q) {
        Map<String, String> p = new LinkedHashMap<>(q);
        p.put("ac", "list");
        JsonObject src = cmsDispatch(p.getOrDefault("site", "lzi"), p);
        JsonObject out = new JsonObject();
        out.add("list", src.getAsJsonArray("list").deepCopy());
        out.addProperty("total", src.has("total") ? src.get("total").getAsInt() : 0);
        return out;
    }

    private static JsonObject detail(Map<String, String> q) {
        String key = q.getOrDefault("site", "lzi");
        String id = q.getOrDefault("id", "");
        Sources.Site s = Sources.find(key);
        if (s != null && "html".equals(s.adapter())) {
            return htmlDetail(s, id);
        }
        JsonObject src = cmsDispatch(key, Map.of("ac", "detail", "ids", id));
        JsonObject out = new JsonObject();
        out.add("list", src.getAsJsonArray("list").deepCopy());
        return out;
    }

    private static JsonObject htmlDetail(Sources.Site s, String ids) {
        String id = ids.split(",")[0].trim();
        String url = switch (s.key()) {
            case "seedhub" -> "https://sidhub.cc/movies/" + id + "/";
            case "wuye" -> "https://xu--5us88dtla.wuye.mom/voddetail/" + id + ".html";
            case "lziroot" -> "https://cj.lziapi.com/index.php/vod/detail/id/" + id + ".html";
            case "libvio" -> "https://libvio.cam/voddetail/" + id + ".html";
            default -> s.url();
        };
        JsonObject v = new JsonObject();
        v.addProperty("vod_id", id);
        v.addProperty("vod_name", s.name() + " #" + id);
        v.addProperty("vod_pic", "");
        v.addProperty("type_name", s.name());
        v.addProperty("vod_play_from", s.key());
        v.addProperty("vod_play_url", "打开原页$" + url);
        JsonObject out = baseCms();
        JsonArray list = new JsonArray();
        list.add(v);
        out.add("list", list);
        out.addProperty("total", 1);
        return out;
    }

    /** 播放：m3u8 直连 parse=0；share/网页链接交给解析接口 parse=1 */
    private static JsonObject play(Map<String, String> q) {
        String key = q.getOrDefault("site", "lzi");
        String id = q.getOrDefault("id", "");
        int n = 0;
        try { n = Integer.parseInt(q.getOrDefault("n", "0")); } catch (NumberFormatException ignored) {}
        JsonObject d = detail(Map.of("site", key, "id", id));
        JsonArray list = d.getAsJsonArray("list");
        String raw = list.isEmpty() ? "" : str(list.get(0).getAsJsonObject(), "vod_play_url");
        String playUrl = "";
        if (!raw.isEmpty()) {
            String[] groups = raw.split("\\$\\$\\$");
            String[] eps = groups[0].split("#");
            int i = Math.max(0, Math.min(n, eps.length - 1));
            String[] kv = eps[i].split("\\$", 2);
            playUrl = kv.length > 1 ? kv[1] : kv[0];
        }
        JsonObject out = new JsonObject();
        boolean direct = playUrl.contains(".m3u8");
        out.addProperty("parse", direct ? 0 : 1);
        out.addProperty("url", direct ? playUrl : Sources.PLAYER + playUrl);
        out.addProperty("flag", q.getOrDefault("flag", "lzm3u8"));
        JsonObject header = new JsonObject();
        header.addProperty("User-Agent", Sources.UA);
        out.add("header", header);
        out.addProperty("source_site", key);
        out.addProperty("raw_play_url", playUrl);
        return out;
    }

    /* ---------------- 订阅配置 ---------------- */

    private static JsonObject buildConfig() {
        String base = "http://127.0.0.1:" + PORT + "/api/cms/";
        JsonObject root = new JsonObject();
        root.addProperty("spider", "");
        root.addProperty("wallpaper", "");
        root.addProperty("logo", "");
        JsonArray sites = new JsonArray();
        String[][] defs = {
            {"lzi",      "🎬量子┃JSON采集",  "1"},
            {"liangzi",  "🎬量子┃liangzi",   "1"},
            {"lzm3u8",   "🎬量子┃lzm3u8",    "1"},
            {"lziroot",  "🌐量子资源网┃HTML","0"},
            {"seedhub",  "🌐SeedHub┃影视",   "1"},
            {"wuye",     "🌐午夜┃影视",      "1"},
            {"quanpan",  "🌐全盘搜┃SPA",     "0"},
            {"libvio",   "🌐Libvio",         "0"}
        };
        for (String[] d : defs) {
            JsonObject s = new JsonObject();
            s.addProperty("key", d[0]);
            s.addProperty("name", d[1]);
            s.addProperty("type", 1);
            s.addProperty("api", base + d[0] + "/");
            s.addProperty("searchable", Integer.parseInt(d[2]));
            s.addProperty("quickSearch", 0);
            s.addProperty("filterable", 1);
            s.addProperty("changeable", 0);
            JsonObject ext = new JsonObject();
            ext.addProperty("site", d[0]);
            s.add("ext", ext);
            sites.add(s);
        }
        root.add("sites", sites);

        JsonArray parses = new JsonArray();
        JsonObject p1 = new JsonObject();
        p1.addProperty("name", "lzm3u8解析");
        p1.addProperty("type", 1);
        p1.addProperty("url", Sources.PLAYER);
        parses.add(p1);
        JsonObject p2 = new JsonObject();
        p2.addProperty("name", "本地聚合");
        p2.addProperty("type", 0);
        p2.addProperty("url", "http://127.0.0.1:" + PORT + "/api/play?site=");
        parses.add(p2);
        root.add("parses", parses);

        JsonArray flags = new JsonArray();
        flags.add("lzm3u8");
        flags.add("liangzi");
        flags.add("量子");
        root.add("flags", flags);
        root.add("lives", new JsonArray());
        root.add("ads", new JsonArray());

        JsonObject meta = new JsonObject();
        meta.addProperty("api_base", "http://127.0.0.1:" + PORT);
        meta.addProperty("generated_by", "com.github.catvod.api.JsonApiServer");
        meta.addProperty("probe_report", "output/probe-report.json");
        meta.addProperty("cms_source", Sources.CMS);
        root.add("_meta", meta);
        return root;
    }

    private static JsonObject index() {
        JsonObject o = new JsonObject();
        o.addProperty("service", "TVBox JSON 聚合接口");
        o.addProperty("base", "http://127.0.0.1:" + PORT);
        o.addProperty("subscription", "/api/config");
        JsonArray eps = new JsonArray();
        for (String s : List.of(
                "/api/sites", "/api/probe", "/api/samples", "/api/config",
                "/api/home?site=lzi", "/api/category?site=lzi&t=1&pg=1",
                "/api/search?site=lzi&wd=凡人", "/api/detail?site=lzi&id=158436",
                "/api/play?site=lzi&id=158436&n=0",
                "/api/cms/lzi/?ac=list&pg=1", "/api/cms/seedhub/?ac=list",
                "/api/xml?from=lzm3u8",
                "/api.php/provide/vod/?ac=list&pg=1",
                "/api.php/provide/vod/from/lzm3u8/at/xml/",
                "/api.php/provide/art/?ac=list", "/api.php/provide/actor/?ac=list")) {
            eps.add(s);
        }
        o.add("endpoints", eps);
        return o;
    }

    /* ---------------- 基础设施 ---------------- */

    private static JsonObject cachedJson(String key, Supplier<JsonObject> loader) {
        Cached c = CACHE.get(key);
        if (c != null && System.currentTimeMillis() - c.ts() < TTL) {
            try {
                return com.google.gson.JsonParser.parseString(c.body()).getAsJsonObject();
            } catch (Exception ignored) {}
        }
        JsonObject o;
        try {
            o = loader.get();
        } catch (Exception e) {
            return emptyCms(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String body = pretty(o);
        CACHE.put(key, new Cached(System.currentTimeMillis(), body, "json"));
        return o;
    }

    @FunctionalInterface
    private interface Supplier<T> { T get() throws Exception; }

    private static JsonObject emptyCms(String msg) {
        JsonObject o = baseCms();
        o.addProperty("msg", msg);
        o.add("list", new JsonArray());
        return o;
    }

    private static JsonObject baseCms() {
        JsonObject o = new JsonObject();
        o.addProperty("code", 1);
        o.addProperty("msg", "ok");
        o.addProperty("page", 1);
        o.addProperty("pagecount", 1);
        o.addProperty("limit", "20");
        o.addProperty("total", 0);
        return o;
    }

    private static JsonObject cls(String id, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type_id", id);
        o.addProperty("type_name", name);
        return o;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String pretty(JsonObject o) {
        return GSON.create().toJson(o);
    }

    private static Result json(JsonObject o) {
        return new Result("json", pretty(o));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",
                "xml".equals(type) ? "application/xml; charset=utf-8"
                                   : "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
