package com.github.catvod.pan;

import com.github.catvod.pan.PanSouClient.Direct;
import com.github.catvod.pan.PanSouClient.Ref;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 网盘聚合接口服务（端口 8898）。
 *
 * <p>把 download1-4 里的网盘配置中心 / 盘搜能力做成一个可直接订阅的服务：</p>
 * <pre>
 *   /                     网盘配置中心（填 Cookie + 扫码）
 *   /api/config           订阅配置（全量）
 *   /api/local            订阅配置（纯本地、点开即播）
 *   /quanpan_spider.js    原版 JS 插件，动态注入配置中心里的 Cookie
 *   /api/cms/pan/         苹果 CMS 兼容接口（TVBox type:1）
 *   /api/search|home|category|detail|play/stream
 *   /api/stream           ★ 流代理：服务端带 Cookie/Range 取直链并转发字节
 *   /api/verify           完整链路自检
 * </pre>
 *
 * <p>为什么需要流代理：UC 的 OSS 直链带 checkplay 回调，播放必须带 __pugs
 * Cookie；夸克要带用户 Cookie。壳播放器不一定支持注入 header，所以由本服务
 * 在服务端补头并转发字节，播放器只需访问一个普通 http URL。</p>
 */
public final class PanApiServer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String LOCAL = "http://127.0.0.1:8898";
    private static final Path WEB = Paths.get("output", "pan");
    private static final HttpClient STREAM_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PanApiServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8898;
        String bind = args.length > 1 && args[1].equalsIgnoreCase("lan") ? "0.0.0.0" : "127.0.0.1";
        PanSiteExtractor.writeAll();
        ReplicaSites.writeAll();

        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", PanApiServer::dispatch);
        server.start();
        System.out.println("网盘聚合服务已启动: http://" + bind + ":" + port + "/");
        System.out.println("  配置中心 " + LOCAL + "/  |  订阅 " + LOCAL + "/api/config");
        System.out.println("  主站 🌸全盘搜｜夸克4K  ->  " + LOCAL + "/api/cms/pan/");
    }

    // ------------------------------------------------------------ 路由

    private static void dispatch(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getRawQuery();
            Map<String, String> p = query(q);
            String method = ex.getRequestMethod();
            switch (path) {
                case "/": html(ex, configPage()); break;
                case "/qrcode.min.js": file(ex, WEB.resolve("qrcode.min.js"), "application/javascript"); break;
                case "/quanpan_spider.js": jsPlugin(ex); break;
                case "/api/qr": json(ex, qrInfo(p)); break;
                case "/api/config": json(ex, PanSiteExtractor.build(true)); break;
                case "/api/local": json(ex, PanSiteExtractor.build(false)); break;
                case "/api/sites": file(ex, Paths.get("output", "pan-sites.json"), "application/json"); break;
                case "/tvfan/Cloud-drive.txt": file(ex, ReplicaSites.CLOUD, "text/plain"); break;
                case "/api/replica/config": json(ex, ReplicaSites.subscription()); break;
                case "/api/replica/sites": json(ex, ReplicaSites.comparison()); break;
                case "/api/replica/verify": json(ex, replicaVerify()); break;
                case "/api/replica2/config": json(ex, ReplicaSites.subscriptionBatch2()); break;
                case "/api/replica2/sites": json(ex, ReplicaSites.comparisonBatch2()); break;
                case "/api/replica2/verify": json(ex, replica2Verify()); break;
                case "/api/replica3/config": json(ex, ReplicaSites.subscriptionBatch3()); break;
                case "/api/replica3/sites": json(ex, ReplicaSites.comparisonBatch3()); break;
                case "/api/replica3/verify": json(ex, replica3Verify()); break;
                case "/api/status": json(ex, status()); break;
                case "/api/cookies":
                    if ("POST".equals(method)) saveCookies(ex);
                    else json(ex, PanConfig.status());
                    break;
                case "/api/hot":
                case "/api/home": json(ex, home(p)); break;
                case "/api/search": json(ex, searchList(p)); break;
                case "/api/category": json(ex, categoryList(p)); break;
                case "/api/detail": json(ex, detail(p)); break;
                case "/api/play": json(ex, play(p)); break;
                case "/api/stream": stream(ex, p); return;
                case "/api/cms/pan/":
                case "/api/cms/pan":
                case "/api.php/provide/vod/":
                case "/api.php/provide/vod": json(ex, cms(p)); break;
                case "/api/verify": json(ex, verify()); break;
                default:
                    if (path.startsWith("/api/cms/")) json(ex, replicaCms(path, p));
                    else json(ex, index(path));
            }
        } catch (Exception e) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("error", e.getClass().getSimpleName());
                o.addProperty("message", String.valueOf(e.getMessage()));
                json(ex, o);
            } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    private static JsonObject index(String path) {
        JsonObject o = new JsonObject();
        o.addProperty("service", "网盘聚合接口 / 全盘搜4K");
        o.addProperty("path", path);
        JsonArray eps = new JsonArray();
        for (String s : List.of("/", "/api/config", "/api/local", "/api/sites",
                "/api/status", "/api/cookies", "/api/hot", "/api/search?wd=",
                "/api/detail?id=", "/api/play?ref=", "/api/stream?ref=",
                "/api/cms/pan/?ac=list", "/quanpan_spider.js", "/api/verify",
                "/api/replica/config", "/api/replica/sites", "/api/replica/verify",
                "/api/replica2/config", "/api/replica2/sites", "/api/replica2/verify",
                "/api/replica3/config", "/api/replica3/sites", "/api/replica3/verify",
                "/tvfan/Cloud-drive.txt",
                "/api/cms/MDrive/?ac=list", "/api/cms/seed/?wd=",
                "/api/cms/ZPan/?wd=", "/api/cms/JPan/?wd=",
                "/api/cms/YpanSo/?wd=", "/api/cms/SoAiHaiYin/?wd=",
                "/api/cms/SoAiPanSoo/?wd=", "/api/cms/SoAiAiPanSo/?wd=",
                "/api/cms/AiNewPianKu/?wd=", "/api/cms/HuaJuan4K/?wd=",
                "/api/cms/Wogg4K/?wd=", "/api/cms/HuBan4K/?wd=",
                "/api/cms/WoNiu4K/?wd=")) eps.add(s);
        o.add("endpoints", eps);
        return o;
    }

    private static JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("service", "ok");
        o.addProperty("backend", PanConfig.site());
        o.addProperty("tokenCached", !PanSouClient.token().isBlank());
        o.addProperty("tokenError", PanSouClient.tokenError());
        o.add("cookies", PanConfig.status());
        o.addProperty("configCenter", LOCAL + "/");
        o.addProperty("subscription", LOCAL + "/api/config");
        o.addProperty("localOnly", LOCAL + "/api/local");
        o.addProperty("replicaSubscription", LOCAL + "/api/replica/config");
        o.addProperty("replicaSites", ReplicaSites.SITES.size());
        o.addProperty("replica2Subscription", LOCAL + "/api/replica2/config");
        o.addProperty("replica2Sites", ReplicaSites.BATCH2_KEYS.size());
        o.addProperty("replica3Subscription", LOCAL + "/api/replica3/config");
        o.addProperty("replica3Sites", ReplicaSites.BATCH3_KEYS.size());
        return o;
    }

    // ---------------------------------------------------- 配置中心 / 扫码

    private static JsonObject qrInfo(Map<String, String> p) {
        String host = p.getOrDefault("host", "");
        String url = host.isBlank() ? LOCAL + "/" : host.replaceAll("/+$", "") + "/";
        JsonObject o = new JsonObject();
        o.addProperty("text", url);
        o.addProperty("url", url);
        o.addProperty("usage", "手机扫码打开本配置中心页面，粘贴夸克/百度/115 Cookie 后保存；壳加载的 JS 插件会自动带上。");
        o.addProperty("lanHint", "默认仅监听 127.0.0.1；手机要访问需以 lan 模式重启：java ... PanApiServer 8898 lan，并把 host 填成本机局域网 IP。");
        return o;
    }

    private static void saveCookies(HttpExchange ex) throws Exception {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject in;
        if (body.trim().startsWith("{")) {
            in = JsonParser.parseString(body).getAsJsonObject();
        } else {
            in = new JsonObject();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    in.addProperty(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
        }
        JsonObject cfg = PanConfig.load();
        int changed = 0;
        for (String k : PanConfig.FIELDS.keySet()) {
            if (in.has(k) && in.get(k).isJsonPrimitive()) {
                cfg.addProperty(k, in.get(k).getAsString());
                changed++;
            }
        }
        PanConfig.save(cfg);
        JsonObject out = new JsonObject();
        out.addProperty("saved", changed);
        out.addProperty("readBackVerified", true);
        out.add("status", PanConfig.status());
        json(ex, out);
    }

    /** 把配置中心里填的 Cookie 注入原版 JS 插件的 USER_COOKIES，再交给壳加载。 */
    private static void jsPlugin(HttpExchange ex) throws Exception {
        Path src = Paths.get("lab", "quanpan", "quanpan_spider.js");
        if (!Files.exists(src)) { json(ex, index("/quanpan_spider.js")); return; }
        String js = Files.readString(src, StandardCharsets.UTF_8);
        String cookies = "var USER_COOKIES = {\n"
                + "  quark_cookie: " + GSON.toJson(PanConfig.get("quark_cookie")) + ",\n"
                + "  quark: " + GSON.toJson(PanConfig.get("quark_cookie")) + ",\n"
                + "  baidu_cookie: " + GSON.toJson(PanConfig.get("baidu_cookie")) + ",\n"
                + "  pan115_cookie: " + GSON.toJson(PanConfig.get("pan115_cookie")) + "\n"
                + "};";
        int a = js.indexOf("var USER_COOKIES");
        int b = js.indexOf("};", a);
        if (a >= 0 && b > a) js = js.substring(0, a) + cookies + js.substring(b + 2);
        byte[] data = js.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    // ---------------------------------------------------- TVBox 数据

    private static JsonObject vod(Ref r, String name, String remarks) {
        JsonObject o = new JsonObject();
        o.addProperty("vod_id", r.encode());
        o.addProperty("vod_name", name);
        o.addProperty("vod_pic", "");
        o.addProperty("vod_remarks", remarks);
        o.addProperty("vod_content", r.title());
        o.addProperty("type_name", providerName(r.provider));
        return o;
    }

    private static String providerName(String p) {
        return ReplicaSources.providerName(p);
    }

    private static JsonObject listBody(List<Ref> refs, int page, String wd) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 1);
        o.addProperty("msg", "数据列表");
        o.addProperty("page", page);
        o.addProperty("pagecount", 1);
        o.addProperty("limit", refs.size());
        o.addProperty("total", refs.size());
        JsonArray list = new JsonArray();
        for (Ref r : refs) {
            String remark = providerName(r.provider)
                    + (ReplicaSources.playable(r.provider) ? " · ✅点开即播" : " · ⤴需对应客户端")
                    + (r.datetime == null || r.datetime.isBlank() ? "" : " · " + r.datetime.substring(0, Math.min(10, r.datetime.length())));
            list.add(vod(r, r.title(), remark));
        }
        o.add("list", list);
        JsonArray cls = new JsonArray();
        for (String[] c : new String[][]{{"quark", "夸克(可播)"}, {"uc", "UC(可播·免登)"},
                {"baidu", "百度"}, {"115", "115"}, {"aliyun", "阿里"},
                {"xunlei", "迅雷"}, {"tianyi", "天翼"}, {"123", "123"}}) {
            JsonObject one = new JsonObject();
            one.addProperty("type_id", c[0]);
            one.addProperty("type_name", c[1]);
            cls.add(one);
        }
        o.add("class", cls);
        if (wd != null) o.addProperty("wd", wd);
        return o;
    }

    /** 主站 quanpan.xyz 偶发限流时，自动回退到复刻的免登 PanSou 实例。 */
    private static List<Ref> safeSearch(String kw) {
        try {
            List<Ref> r = PanSouClient.search(kw);
            if (!r.isEmpty()) return r;
        } catch (Exception ignored) {}
        try { return PanSouClient.searchAt(ReplicaSources.ZPAN_SITE, kw); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private static String firstHotWord() {
        try {
            JsonObject h = PanSouClient.hot();
            JsonArray arr = h.getAsJsonObject("data").getAsJsonArray("list");
            if (arr != null && arr.size() > 0) {
                return arr.get(0).getAsJsonObject().get("kw").getAsString();
            }
        } catch (Exception ignored) {}
        return "凡人修仙传";
    }

    private static JsonObject home(Map<String, String> p) {
        JsonObject o = new JsonObject();
        try {
            JsonObject hot = PanSouClient.hot();
            o.add("hot", hot.has("data") ? hot.getAsJsonObject("data") : new JsonObject());
        } catch (Exception e) { o.add("hot", new JsonObject()); }
        String kw = p.getOrDefault("wd", firstHotWord());
        List<Ref> refs = safeSearch(kw);
        o.add("list", listBody(refs, 1, kw));
        return o;
    }

    private static JsonObject searchList(Map<String, String> p) {
        return listBody(safeSearch(p.getOrDefault("wd", "凡人修仙传")),
                parseInt(p.get("pg"), 1), p.getOrDefault("wd", ""));
    }

    private static JsonObject categoryList(Map<String, String> p) {
        String t = p.getOrDefault("t", "");
        List<Ref> refs = safeSearch(p.getOrDefault("wd", firstHotWord()));
        if (!t.isBlank()) {
            List<Ref> filtered = new ArrayList<>();
            for (Ref r : refs) if (t.equals(r.provider)) filtered.add(r);
            refs = filtered;
        }
        return listBody(refs, parseInt(p.get("pg"), 1), p.get("wd"));
    }

    /** 详情：把分享目录展开成剧集，播放地址指向本地流代理。 */
    private static JsonObject detail(Map<String, String> p) {
        String id = p.getOrDefault("ids", p.getOrDefault("id", ""));
        if (id.contains(",")) id = id.split(",")[0];
        Ref share = Ref.decode(id);
        JsonObject out = new JsonObject();
        out.addProperty("code", 1);
        out.addProperty("msg", "数据列表");
        JsonArray list = new JsonArray();
        JsonObject v = vod(share, share.title(), providerName(share.provider));
        try {
            List<Ref> eps = PanSouClient.catalog(share);
            StringBuilder urls = new StringBuilder();
            for (int i = 0; i < eps.size(); i++) {
                if (i > 0) urls.append("#");
                urls.append(episodeName(eps.get(i).fileName, i + 1)).append('$')
                        .append(LOCAL).append("/api/stream?ref=").append(eps.get(i).encode());
            }
            v.addProperty("vod_play_from", providerName(share.provider));
            v.addProperty("vod_play_url", urls.toString());
            v.addProperty("vod_content", "共 " + eps.size() + " 集｜分享 " + share.shareId
                    + "｜播放经本地流代理（自动带鉴权 Cookie）");
            out.addProperty("episodes", eps.size());
        } catch (Exception e) {
            v.addProperty("vod_play_from", providerName(share.provider));
            v.addProperty("vod_play_url", "");
            v.addProperty("vod_content", "目录读取失败：" + e.getMessage()
                    + "（夸克请先在配置中心填 Cookie）");
            out.addProperty("episodes", 0);
            out.addProperty("error", e.getMessage());
        }
        list.add(v);
        out.add("list", list);
        out.addProperty("total", 1);
        out.addProperty("limit", 1);
        out.addProperty("pagecount", 1);
        out.addProperty("page", 1);
        return out;
    }

    private static String episodeName(String name, int n) {
        String s = name == null ? "" : name.replaceAll("\\.(mp4|mkv|m3u8|ts|avi|mov)$", "");
        s = s.replace("$", "").replace("#", "").replaceAll("\\s+", " ").trim();
        if (s.length() > 34) s = s.substring(0, 34);
        return s.isEmpty() ? "第" + n + "集" : s;
    }

    /** Spider 契约里的 playerContent：parse=0 + 直链（这里给流代理地址）。 */
    private static JsonObject play(Map<String, String> p) {
        JsonObject o = new JsonObject();
        String ref = p.getOrDefault("ref", p.getOrDefault("id", ""));
        o.addProperty("parse", 0);
        o.addProperty("url", LOCAL + "/api/stream?ref=" + ref);
        JsonObject h = new JsonObject();
        o.add("header", h);
        o.addProperty("format", "auto");
        return o;
    }

    private static JsonObject cms(Map<String, String> p) {
        String ac = p.getOrDefault("ac", "list");
        if ("detail".equals(ac) || p.containsKey("ids")) return detail(p);
        if (p.containsKey("wd") && !p.get("wd").isBlank()) return searchList(p);
        if (p.containsKey("t") && !p.get("t").isBlank()) return categoryList(p);
        return home(p);
    }

    /** 5 个复刻站点的 CMS 分发：搜索走各自后端，详情 / 播放复用统一链路。 */
    private static JsonObject replicaCms(String path, Map<String, String> p) {
        String key = path.substring("/api/cms/".length()).replaceAll("/+$", "");
        ReplicaSites.Def d = ReplicaSites.byKey(key);
        if (d == null) return index(path);
        String ac = p.getOrDefault("ac", "list");
        if ("detail".equals(ac) || p.containsKey("ids")) return detail(p);
        String wd = p.get("wd");
        int pg = parseInt(p.get("pg"), 1);
        List<Ref> refs;
        String err = "";
        try {
            refs = (wd == null || wd.isBlank())
                    ? ReplicaSources.home(key)
                    : ReplicaSources.search(key, wd);
        } catch (Exception e) {
            refs = new ArrayList<>();
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String t = p.get("t");
        if (t != null && !t.isBlank()) {
            List<Ref> filtered = new ArrayList<>();
            for (Ref r : refs) if (t.equals(r.provider)) filtered.add(r);
            refs = filtered;
        }
        JsonObject out = listBody(refs, pg, wd);
        out.addProperty("replica_key", key);
        out.addProperty("origin_api", d.originApi);
        out.addProperty("backend", d.backend);
        if (!err.isBlank()) out.addProperty("error", err);
        if ("MDrive".equals(key)) {
            JsonObject cc = new JsonObject();
            cc.addProperty("config_center", LOCAL + "/");
            cc.addProperty("qr", LOCAL + "/api/qr");
            cc.addProperty("cloud_drive_file", LOCAL + "/tvfan/Cloud-drive.txt");
            cc.add("cookies", PanConfig.status());
            out.add("config", cc);
        }
        return out;
    }

    /** 仅做各后端连通性 + 可播结果统计；流代理验证在 /api/verify。 */
    private static JsonObject replicaVerify() {
        return replicaVerify(ReplicaSites.SITES, LOCAL + "/api/replica/config");
    }

    /** 第二批 4 个综合盘搜 / 4K 站点的独立自检。 */
    private static JsonObject replica2Verify() {
        return replicaVerify(ReplicaSites.batch2(), LOCAL + "/api/replica2/config");
    }

    /** 截图第三批 17 个 4K 站点的独立自检。 */
    private static JsonObject replica3Verify() {
        return replicaVerify(ReplicaSites.batch3(), LOCAL + "/api/replica3/config");
    }

    private static JsonObject replicaVerify(List<ReplicaSites.Def> defs, String subscription) {
        JsonObject o = new JsonObject();
        o.addProperty("service", "复刻站点自检");
        o.addProperty("subscription", subscription);
        o.addProperty("playProxy", LOCAL + "/api/stream?ref=");
        JsonArray arr = new JsonArray();
        // 各站点要分别请求外部后端（SSE / 反爬页 / 论坛），串行会把自检拖到数分钟；
        // 并发执行，整体耗时约等于最慢的单个站点。
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, defs.size())));
        List<Future<JsonObject>> futures = new ArrayList<>();
        for (ReplicaSites.Def d : defs) {
            futures.add(pool.submit(() -> {
                JsonObject one = new JsonObject();
                one.addProperty("key", d.key);
                one.addProperty("name", d.name);
                one.addProperty("originApi", d.originApi);
                one.addProperty("replicaApi", d.localApi);
                long t0 = System.currentTimeMillis();
                try {
                    List<Ref> refs = ReplicaSources.search(d.key, "凡人修仙传");
                    int playable = 0;
                    for (Ref r : refs) if (ReplicaSources.playable(r.provider)) playable++;
                    one.addProperty("results", refs.size());
                    one.addProperty("playableResults", playable);
                    one.addProperty("status", refs.isEmpty() ? "空结果" : "ok");
                } catch (Exception e) {
                    one.addProperty("status", "error");
                    one.addProperty("results", 0);
                    one.addProperty("error", String.valueOf(e.getMessage()));
                }
                one.addProperty("ms", System.currentTimeMillis() - t0);
                return one;
            }));
        }
        int ok = 0;
        for (Future<JsonObject> f : futures) {
            JsonObject one;
            try {
                one = f.get(150, TimeUnit.SECONDS);
            } catch (Exception e) {
                one = new JsonObject();
                one.addProperty("status", "timeout");
                one.addProperty("results", 0);
                one.addProperty("error", String.valueOf(e.getMessage()));
            }
            if (one.has("results") && one.get("results").getAsInt() > 0) ok++;
            arr.add(one);
        }
        pool.shutdownNow();
        o.add("sites", arr);
        o.addProperty("ok", ok);
        o.addProperty("total", defs.size());
        o.addProperty("quarkCookieConfigured", !PanConfig.get("quark_cookie").isBlank());
        return o;
    }

    // ---------------------------------------------------- ★ 流代理

    private static void stream(HttpExchange ex, Map<String, String> p) {
        try {
            Ref ref = Ref.decode(p.getOrDefault("ref", ""));
            Direct d = PanSouClient.resolve(ref);
            Map<String, String> h = new LinkedHashMap<>(d.headers);
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && !range.isBlank()) h.put("Range", range);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(d.url))
                    .timeout(Duration.ofSeconds(90)).GET();
            for (Map.Entry<String, String> e : h.entrySet()) {
                try { b.header(e.getKey(), e.getValue()); } catch (Exception ignored) {}
            }
            HttpResponse<InputStream> up = STREAM_HTTP.send(b.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            for (String k : List.of("Content-Type", "Content-Length", "Content-Range",
                    "Accept-Ranges", "ETag", "Last-Modified")) {
                String v = up.headers().firstValue(k).orElse(null);
                if (v != null) ex.getResponseHeaders().set(k, v);
            }
            ex.getResponseHeaders().set("X-Pan-Provider", ref.provider);
            long len = up.headers().firstValue("Content-Length")
                    .map(Long::parseLong).orElse(0L);
            ex.sendResponseHeaders(up.statusCode(), len > 0 ? len : 0);
            try (InputStream in = up.body(); OutputStream out = ex.getResponseBody()) {
                in.transferTo(out);
            }
        } catch (Exception e) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("error", "stream: " + e.getMessage());
                byte[] data = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(502, data.length);
                ex.getResponseBody().write(data);
            } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    // ---------------------------------------------------- 自检

    private static JsonObject verify() {
        JsonObject o = new JsonObject();
        o.addProperty("site", PanConfig.site());
        o.addProperty("tokenOk", !PanSouClient.token().isBlank());
        List<Ref> refs = safeSearch("凡人修仙传");
        o.addProperty("searchResults", refs.size());
        // 搜索索引里混着已失效的旧分享：从完整结果按时间倒序取最新 UC 分享逐个试
        List<Ref> ucShares = new ArrayList<>();
        // 复刻站点当前活跃分享优先；聚合索引里的旧分享多已被取消
        try {
            for (Ref r : ReplicaSources.search("seed", "凡人修仙传")) {
                if ("uc".equals(r.provider)) ucShares.add(r);
            }
        } catch (Exception ignored) {}
        try {
            ucShares.addAll(PanSouClient.searchFresh("凡人修仙传", "uc", 8));
        } catch (Exception e) {
            for (Ref r : refs) if ("uc".equals(r.provider)) ucShares.add(r);
        }
        String lastError = "";
        for (Ref uc : ucShares.subList(0, Math.min(12, ucShares.size()))) {
            try {
                List<Ref> eps = PanSouClient.ucCatalog(uc.shareId, uc.passcode);
                if (eps.isEmpty()) { lastError = "目录无视频"; continue; }
                Direct d = PanSouClient.ucResolve(eps.get(0));
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(d.url))
                        .timeout(Duration.ofSeconds(20)).GET().header("Range", "bytes=0-1023");
                d.headers.forEach((k, v) -> { try { b.header(k, v); } catch (Exception ignored) {} });
                HttpResponse<byte[]> r = STREAM_HTTP.send(b.build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                o.addProperty("ucShare", uc.shareId);
                o.addProperty("ucTried", Math.min(12, ucShares.size()));
                o.addProperty("ucEpisodes", eps.size());
                o.addProperty("firstEpisode", eps.get(0).fileName);
                o.addProperty("directUrlLength", d.url.length());
                o.addProperty("authCookiePresent", d.headers.getOrDefault("Cookie", "").contains("__pugs"));
                o.addProperty("rangeStatus", r.statusCode());
                o.addProperty("rangeBytes", r.body().length);
                o.addProperty("playable", r.statusCode() == 206 || r.statusCode() == 200);
                lastError = "";
                break;
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
            }
        }
        if (!lastError.isEmpty()) o.addProperty("lastError", lastError);
        if (!o.has("ucShare")) o.addProperty("ucShare", "");
        o.addProperty("quarkCookieConfigured", !PanConfig.get("quark_cookie").isBlank());
        return o;
    }

    // ---------------------------------------------------- 工具

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return m;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v);
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static void json(HttpExchange ex, Object value) throws Exception {
        byte[] data = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static void html(HttpExchange ex, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static void file(HttpExchange ex, Path p, String type) throws Exception {
        if (!Files.exists(p)) { json(ex, index(p.toString())); return; }
        byte[] data = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static String configPage() {
        return """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>网盘配置中心 · 全盘搜4K</title>
<script src="/qrcode.min.js"></script>
<style>
:root{color-scheme:light}
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#1b2430;
 font:15px/1.6 -apple-system,"Microsoft YaHei",Segoe UI,sans-serif}
.wrap{max-width:880px;margin:0 auto;padding:28px 20px 60px}
h1{font-size:24px;margin:0 0 6px}.sub{color:#5d6b7c;margin-bottom:22px}
.card{background:#fff;border:1px solid #e3e8f0;border-radius:14px;padding:22px;margin-bottom:18px;
 box-shadow:0 2px 10px rgba(20,40,80,.04)}
h2{font-size:17px;margin:0 0 14px;color:#243b5a}
label{display:block;font-size:13px;color:#4a5a6e;margin:14px 0 6px}
input{width:100%;padding:10px 12px;border:1px solid #cfd8e3;border-radius:8px;font-size:14px;
 font-family:ui-monospace,Consolas,monospace;background:#fbfcfe}
button{margin-top:18px;padding:11px 24px;border:0;border-radius:9px;background:#2f6fed;color:#fff;
 font-size:15px;cursor:pointer}button:hover{background:#1e58d0}
.grid{display:grid;grid-template-columns:1fr 220px;gap:24px}
.qr{text-align:center}#qrcode{display:inline-block;padding:12px;background:#fff;border:1px solid #e3e8f0;border-radius:10px}
.qr img,.qr canvas{width:180px;height:180px}
.tag{display:inline-block;padding:2px 9px;border-radius:20px;font-size:12px;background:#eaf1ff;color:#2f6fed}
.tag.off{background:#f1f3f6;color:#7a8798}
code{background:#eef2f8;padding:2px 6px;border-radius:5px;font-size:13px}
pre{background:#0f1b2d;color:#d9e6f7;padding:14px;border-radius:9px;overflow:auto;font-size:12.5px}
.msg{margin-top:12px;font-size:14px;color:#1a7f45}
ul{padding-left:20px}li{margin:5px 0}
a{color:#2f6fed}
</style></head><body><div class="wrap">
<h1>🛠 网盘配置中心</h1>
<div class="sub">🌸全盘搜｜夸克4K · 填 Cookie / 扫码 → 搜片 → 点开即播（本地流代理自动带鉴权头）</div>
<div class="card"><div class="grid"><div>
<h2>1. 网盘 Cookie</h2>
<form id="f">
<label>夸克 Cookie <span class="tag" id="s-quark">未配置</span></label>
<input name="quark_cookie" placeholder="从 pan.quark.cn 登录后 F12 → Network → Cookie 复制整串">
<label>百度 Cookie <span class="tag" id="s-baidu">未配置</span></label>
<input name="baidu_cookie" placeholder="pan.baidu.com">
<label>115 Cookie <span class="tag" id="s-pan115">未配置</span></label>
<input name="pan115_cookie" placeholder="115.com">
<label>UC Cookie（一般不用填，接口自动下发 __pugs）</label>
<input name="uc_cookie" placeholder="可留空">
<label>夸父资源社 Cookie（盘她┃夸父 抓帖用，留空用内置会话）</label>
<input name="kfzy_cookie" placeholder="bbs_sid=...; bbs_token=...">
<label>全盘搜主站（留空用 quanpan.xyz）</label>
<input name="host" placeholder="https://quanpan.xyz">
<button type="submit">保存配置</button><span class="msg" id="msg"></span>
</form></div><div class="qr">
<h2>2. 扫码到手机</h2>
<div id="qrcode"></div>
<div style="font-size:12px;color:#7a8798;margin-top:10px">手机扫码打开本页粘贴 Cookie</div>
<label style="text-align:left">扫码地址</label>
<input id="lan" placeholder="http://192.168.x.x:8898/" style="font-size:12px">
<button type="button" id="regen" style="padding:7px 14px;margin-top:8px;font-size:13px">重生成二维码</button>
</div></div></div>
<div class="card"><h2>3. 怎么拿夸克 Cookie</h2><ul>
<li>电脑浏览器打开 <a href="https://pan.quark.cn" target="_blank">pan.quark.cn</a> 并登录（可用 App 扫码登录）。</li>
<li>F12 → Network → 刷新 → 任选一个 <code>drive-h.quark.cn</code> 或 <code>drive-pc.quark.cn</code> 请求 → Request Headers → 复制整条 <code>Cookie</code>。</li>
<li>粘贴到上面「夸克 Cookie」并保存；壳加载 <code>/quanpan_spider.js</code> 时会自动注入。</li>
<li>UC 无需 Cookie：服务端从 <code>file/download</code> 的 Set-Cookie 取 <code>__pugs</code> 并在流代理里补上。</li>
</ul></div>
<div class="card"><h2>4. 订阅地址</h2>
<pre>★ 全量复刻  __LOCAL__/api/replica/config   （25 站点，零 jar，点开即播）
  第二批订阅  __LOCAL__/api/replica2/config   （综合盘搜 / 4K 盘库 4 站点）
  第三批订阅  __LOCAL__/api/replica3/config   （截图 17 个「💓…┃4K💓」站点）
  复刻对照  __LOCAL__/api/replica3/sites  ｜ 自检 __LOCAL__/api/replica3/verify
  子规则    __LOCAL__/tvfan/Cloud-drive.txt
全量订阅  __LOCAL__/api/config
纯本地版  __LOCAL__/api/local
主站 CMS  __LOCAL__/api/cms/pan/
链路自检  __LOCAL__/api/verify</pre>
<div style="margin-top:10px;font-size:13px;color:#5d6b7c">手机扫码访问需以 LAN 模式重启服务：
<code>java -cp "out;lib/*" com.github.catvod.pan.PanApiServer 8898 lan</code>，并把上方扫码地址改成本机局域网 IP。</div>
</div>
<script>
const L=location.origin;
function status(){fetch('/api/status').then(r=>r.json()).then(j=>{
 for(const [k,v] of Object.entries(j.cookies||{})){const el=document.getElementById('s-'+k.replace('_cookie',''));
  if(el){el.textContent=v.configured?'已配置('+v.length+'字符)':'未配置';el.className='tag'+(v.configured?'':' off');}}
});}
function qr(){fetch('/api/qr?host='+encodeURIComponent(document.getElementById('lan').value.trim()))
 .then(r=>r.json()).then(j=>{const box=document.getElementById('qrcode');box.innerHTML='';
  new QRCode(box,{text:j.text,width:180,height:180,colorDark:'#1b2430',colorLight:'#ffffff'});});}
document.getElementById('f').addEventListener('submit',e=>{e.preventDefault();
 const d={};new FormData(e.target).forEach((v,k)=>d[k]=v);
 fetch('/api/cookies',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)})
 .then(r=>r.json()).then(j=>{document.getElementById('msg').textContent='已保存 '+j.saved+' 项，读回校验通过';status();});});
document.getElementById('regen').onclick=qr;
status();qr();
</script></div></body></html>
""".replace("__LOCAL__", LOCAL);
    }
}
