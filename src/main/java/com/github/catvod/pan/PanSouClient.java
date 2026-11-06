package com.github.catvod.pan;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「🌸全盘搜｜夸克4K」的 Java 复刻 + 修正版。
 *
 * <p>上游：quanpan.xyz（PanSou 聚合搜索，需 /api/auth/token 下发 x-qp-k）。
 * 目录与直链：UC / 夸克 / 百度 / 115 官方 clouddrive 接口。</p>
 *
 * <p><b>对原 JS 插件的一处修正</b>：UC 的 file/download 会在 Set-Cookie 下发
 * __pugs，OSS 直链带 checkplay 回调鉴权，缺这个 Cookie 会 403
 * RequestDeniedByCallback。原插件只同步了夸克的 __puus，没同步 UC 的 __pugs。
 * 本类在 resolve 时收集全部 Set-Cookie 并放进播放 header。</p>
 */
public final class PanSouClient {

    private static final Gson GSON = new Gson();
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    public static final String UC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final String UC_API = "https://pc-api.uc.cn/1/clouddrive/";
    private static final String QUARK_H = "https://drive-h.quark.cn/1/clouddrive/";
    private static final String QUARK_PC = "https://drive-pc.quark.cn/1/clouddrive/";
    private static final List<String> PROVIDERS = List.of("quark", "uc", "baidu", "115");
    private static final Pattern SHARE = Pattern.compile("/s/([^/?&#]+)");
    private static final int CAP = 60;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile String token = "";
    private static volatile long tokenExpire = 0L;
    private static volatile String lastTokenError = "";

    private PanSouClient() {}

    // ---------------------------------------------------------------- HTTP

    public static final class Resp {
        public final int status;
        public final String body;
        public final Map<String, List<String>> headers;
        Resp(int s, String b, Map<String, List<String>> h) { status = s; body = b; headers = h; }
        public JsonObject json() {
            try { return JsonParser.parseString(body).getAsJsonObject(); }
            catch (Exception e) { return new JsonObject(); }
        }
        /** 收集 Set-Cookie 里的 name=value，拼成可回传的 Cookie 头（大小写不敏感）。 */
        public String cookies(String acc) {
            StringBuilder sb = new StringBuilder(acc == null ? "" : acc);
            for (Map.Entry<String, List<String>> en : headers.entrySet()) {
                if (!"set-cookie".equalsIgnoreCase(en.getKey())) continue;
                for (String line : en.getValue()) {
                    String kv = line.split(";", 2)[0].trim();
                    String name = kv.split("=", 2)[0];
                    if (!kv.isEmpty() && (name.startsWith("__") || name.startsWith("bbs")
                            || name.equalsIgnoreCase("BDCLND"))) {
                        if (sb.indexOf(name + "=") >= 0) continue;
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(kv);
                    }
                }
            }
            return sb.toString();
        }
    }

    public static Resp request(String url, String method, String body,
                               Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", UA)
                .header("Accept", "application/json, */*");
        headers.forEach(b::header);
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "{}" : body, StandardCharsets.UTF_8));
            if (!headers.containsKey("Content-Type")) {
                b.header("Content-Type", "application/json");
            }
        } else {
            b.GET();
        }
        HttpResponse<String> r = HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body(), r.headers().map());
    }

    private static Resp post(String url, Object body, Map<String, String> h) throws Exception {
        return request(url, "POST", GSON.toJson(body), h);
    }

    private static Resp get(String url, Map<String, String> h) throws Exception {
        return request(url, "GET", null, h);
    }

    private static Map<String, String> map(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }

    /** POST body 用：值可以是 List（会被 Gson 序列化成 JSON 数组）。 */
    private static Map<String, Object> omap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String q(Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(String.valueOf(kv[i]), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(String.valueOf(kv[i + 1]), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ------------------------------------------------------ 搜索后端 PanSou

    public static synchronized String token() {
        if (!token.isBlank() && System.currentTimeMillis() < tokenExpire) return token;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String site = PanConfig.site();
                Resp r = get(site + "/api/auth/token",
                        map("User-Agent", UA, "Referer", site + "/"));
                JsonObject j = r.json();
                JsonObject d = j.has("data") && j.get("data").isJsonObject()
                        ? j.getAsJsonObject("data") : null;
                if (r.status < 400 && d != null && d.has("token")) {
                    token = d.get("token").getAsString();
                    long ttl = d.has("ttl_ms") ? d.get("ttl_ms").getAsLong() : 600_000L;
                    tokenExpire = System.currentTimeMillis() + Math.min(ttl * 4 / 5, 900_000L);
                    lastTokenError = "";
                    return token;
                }
                lastTokenError = "HTTP " + r.status + " " + j;
            } catch (Exception e) {
                lastTokenError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ignored) {}
        }
        return token;
    }

    public static String tokenError() { return lastTokenError; }

    public static Resp searchRaw(String kw) throws Exception {
        String site = PanConfig.site();
        Map<String, Object> body = omap("kw", kw, "res", "merge", "src", "all");
        Map<String, String> h = map("User-Agent", UA, "Referer", site + "/",
                "x-qp-k", token(), "Content-Type", "application/json");
        Resp r = post(site + "/api/search", body, h);
        if (r.status == 403) {           // 服务端令牌过期：强制刷新重试一次
            token = ""; tokenExpire = 0L;
            h.put("x-qp-k", token());
            r = post(site + "/api/search", body, h);
        }
        return r;
    }

    public static JsonObject hot() throws Exception {
        String site = PanConfig.site();
        Resp r = get(site + "/api/hot-realtime",
                map("User-Agent", UA, "Referer", site + "/", "x-qp-k", token()));
        return r.json();
    }

    /** 搜索并归一化：四盘轮转合并， unsupported 组丢弃，每条变成一个 Ref。 */
    public static List<Ref> search(String kw) throws Exception {
        Resp r = searchRaw(kw);
        if (r.status >= 400) throw new IllegalStateException("PanSou HTTP " + r.status);
        JsonObject root = r.json();
        if (root.has("code") && root.get("code").getAsInt() != 0) {
            throw new IllegalStateException("PanSou: " + root.get("message").getAsString());
        }
        JsonObject merged = root.getAsJsonObject("data").getAsJsonObject("merged_by_type");
        Map<String, List<Ref>> byType = new LinkedHashMap<>();
        for (String p : PROVIDERS) byType.put(p, new ArrayList<>());

        for (Map.Entry<String, JsonElement> en : merged.entrySet()) {
            String p = en.getKey();
            if (!byType.containsKey(p) || !en.getValue().isJsonArray()) continue;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonElement el : en.getValue().getAsJsonArray()) {
                JsonObject e = el.getAsJsonObject();
                String url = str(e, "url");
                String sid = shareId(url);
                if (sid.isEmpty() || !seen.add(p + ":" + sid)) continue;
                Ref ref = new Ref();
                ref.provider = p;
                ref.shareId = sid;
                ref.passcode = passcode(e);
                ref.note = clean(str(e, "note"));
                ref.url = url;
                ref.datetime = str(e, "datetime");
                ref.source = str(e, "source");
                byType.get(p).add(ref);
            }
        }
        List<Ref> out = new ArrayList<>();
        for (int i = 0; out.size() < CAP; i++) {
            boolean progress = false;
            for (String p : PROVIDERS) {
                List<Ref> list = byType.get(p);
                if (i < list.size()) { out.add(list.get(i)); progress = true; }
                if (out.size() >= CAP) break;
            }
            if (!progress) break;
        }
        return out;
    }

    /** 从完整搜索结果里按时间倒序取某盘的最新分享（避开轮转上限里的旧失效索引）。 */
    public static List<Ref> searchFresh(String kw, String provider, int limit) throws Exception {
        Resp r = searchRaw(kw);
        if (r.status >= 400) throw new IllegalStateException("PanSou HTTP " + r.status);
        JsonObject merged = r.json().getAsJsonObject("data").getAsJsonObject("merged_by_type");
        if (!merged.has(provider) || !merged.get(provider).isJsonArray()) return new ArrayList<>();
        List<Ref> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement el : merged.getAsJsonArray(provider)) {
            JsonObject e = el.getAsJsonObject();
            String sid = shareId(str(e, "url"));
            if (sid.isEmpty() || !seen.add(sid)) continue;
            Ref f = new Ref();
            f.provider = provider; f.shareId = sid; f.passcode = passcode(e);
            f.note = clean(str(e, "note")); f.url = str(e, "url");
            f.datetime = str(e, "datetime"); f.source = str(e, "source");
            out.add(f);
        }
        out.sort((a, b) -> String.valueOf(b.datetime).compareTo(String.valueOf(a.datetime)));
        return out.subList(0, Math.min(limit, out.size()));
    }

    /**
     * 复刻「🎈盘搜┃四盘 / 🦋易搜┃四盘」：指定 PanSou 实例、保留全部盘型。
     *
     * <p>这两个站点是 PanSou 的独立部署（/api/health 返回 auth_enabled:false），
     * 协议与主站 quanpan.xyz 相同，但不需要 /api/auth/token 下发 x-qp-k，
     * 且 merged_by_type 里有 8~10 种盘（quark/uc/baidu/115/xunlei/tianyi/
     * aliyun/123/magnet/mobile），原 search() 只保留 4 盘。这里全部保留，
     * 可直播的 quark/uc 排在前面，其余盘型仍进入列表供跳转。</p>
     */
    public static List<Ref> searchAt(String site, String kw) throws Exception {
        Map<String, Object> body = omap("kw", kw, "res", "merge", "src", "all");
        Map<String, String> h = map("User-Agent", UA, "Referer", site + "/",
                "Content-Type", "application/json");
        if (site.contains("quanpan.xyz")) h.put("x-qp-k", token());
        Resp r = post(site + "/api/search", body, h);
        if (r.status >= 400) throw new IllegalStateException(site + " HTTP " + r.status);
        JsonObject root = r.json();
        JsonObject merged = root.has("data") && root.get("data").isJsonObject()
                && root.getAsJsonObject("data").has("merged_by_type")
                ? root.getAsJsonObject("data").getAsJsonObject("merged_by_type")
                : new JsonObject();
        List<String> keys = new ArrayList<>(merged.keySet());
        keys.sort((a, b) -> {
            int pa = playableRank(a), pb = playableRank(b);
            return pa != pb ? Integer.compare(pa, pb) : a.compareTo(b);
        });
        Map<String, List<Ref>> byType = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String p : keys) {
            List<Ref> bucket = new ArrayList<>();
            if (merged.get(p).isJsonArray()) {
                for (JsonElement el : merged.getAsJsonArray(p)) {
                    JsonObject e = el.getAsJsonObject();
                    String url = str(e, "url"), sid = shareIdAny(url);
                    if (sid.isEmpty() || !seen.add(p + ":" + sid)) continue;
                    Ref f = new Ref();
                    f.provider = p; f.shareId = sid; f.passcode = passcode(e);
                    f.note = clean(str(e, "note")); f.url = url;
                    f.datetime = str(e, "datetime"); f.source = str(e, "source");
                    f.site = site;
                    bucket.add(f);
                }
            }
            byType.put(p, bucket);
        }
        // 四盘轮转：每轮各盘取一条，避免夸克结果把整个列表占满
        List<Ref> out = new ArrayList<>();
        for (int i = 0; out.size() < CAP; i++) {
            boolean progress = false;
            for (List<Ref> bucket : byType.values()) {
                if (i < bucket.size()) { out.add(bucket.get(i)); progress = true; }
                if (out.size() >= CAP) break;
            }
            if (!progress) break;
        }
        return out;
    }

    private static int playableRank(String p) {
        switch (p) {
            case "quark": return 0;
            case "uc": return 1;
            case "baidu": return 2;
            case "115": return 3;
            default: return 9;
        }
    }

    /** 除 /s/ 外还兼容天翼云盘的 /t/ 分享。 */
    private static String shareIdAny(String url) {
        Matcher m = Pattern.compile("/(?:s|t)/([^/?&#]+)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    private static String shareId(String url) {
        Matcher m = SHARE.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    private static String passcode(JsonObject e) {
        for (String k : List.of("password", "passcode", "pwd")) {
            String v = str(e, k);
            if (!v.isBlank()) return v.trim();
        }
        Matcher m = Pattern.compile("[?&](?:pwd|password|passcode)=([^&#]+)")
                .matcher(str(e, "url"));
        return m.find() ? m.group(1) : "";
    }

    private static String clean(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    // ------------------------------------------------------------- Ref

    /** 一条剧集/分享引用，Base64(url-safe) 编码后可直接放进 TVBox 的 id / 播放地址。 */
    public static final class Ref {
        public String provider, shareId, passcode, fid, fileName, pdirFid,
                shareFidToken, stoken, note, url, datetime, source, site;

        public String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
        }
        public static Ref decode(String s) {
            try {
                return GSON.fromJson(new String(Base64.getUrlDecoder().decode(s),
                        StandardCharsets.UTF_8), Ref.class);
            } catch (Exception e) { return new Ref(); }
        }
        public String title() {
            return note == null || note.isBlank() ? (provider + " 分享 " + shareId) : note;
        }
    }

    /** 直链 + 播放必须携带的头。 */
    public static final class Direct {
        public final String url;
        public final Map<String, String> headers;
        public final String format;
        Direct(String u, Map<String, String> h, String f) { url = u; headers = h; format = f; }
    }

    // ------------------------------------------------------------ UC

    private static JsonObject ucApi(String path, Object data) throws Exception {
        Resp r = data == null
                ? get(UC_API + path, map("User-Agent", UC_UA, "Referer", "https://drive.uc.cn"))
                : post(UC_API + path, data, map("User-Agent", UC_UA,
                        "Referer", "https://drive.uc.cn", "Content-Type", "application/json"));
        JsonObject j = r.json();
        if (r.status >= 400 || (j.has("code") && j.get("code").getAsInt() != 0)) {
            throw new IllegalStateException("UC: " + str(j, "message") + " (HTTP " + r.status + ")");
        }
        return j.has("data") && j.get("data").isJsonObject()
                ? j.getAsJsonObject("data") : new JsonObject();
    }

    /** 读 UC 分享目录（BFS，最多 3 层 / 每层 100 项），返回可播视频的 Ref 列表。 */
    public static List<Ref> ucCatalog(String shareId, String passcode) throws Exception {
        JsonObject t = ucApi("share/sharepage/token?pr=UCBrowser&fr=pc",
                map("pwd_id", shareId, "passcode", passcode == null ? "" : passcode));
        String stoken = str(t, "stoken");
        if (stoken.isBlank()) throw new IllegalStateException("UC 分享失效或提取码错误");

        List<Ref> out = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of("0"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int depth = 0; depth < 3 && !queue.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String pdir : queue) {
                String qs = q("pr", "UCBrowser", "fr", "pc", "pwd_id", shareId,
                        "stoken", stoken, "pdir_fid", pdir, "force", 0, "_page", 1,
                        "_size", 100, "_fetch_total", 1,
                        "_sort", "file_type:asc,file_name:asc");
                JsonObject d = ucApi("share/sharepage/detail?" + qs, null);
                if (!d.has("list")) continue;
                for (JsonElement el : d.getAsJsonArray("list")) {
                    JsonObject it = el.getAsJsonObject();
                    String fid = str(it, "fid");
                    boolean dir = it.has("dir") && it.get("dir").getAsBoolean();
                    if (dir) { if (depth < 2) next.add(fid); continue; }
                    String name = str(it, "file_name");
                    if (!isVideo(it, name) || !seen.add(fid)) continue;
                    Ref f = new Ref();
                    f.provider = "uc";
                    f.shareId = shareId;
                    f.passcode = passcode;
                    f.stoken = stoken;
                    f.fid = fid;
                    f.fileName = name;
                    f.pdirFid = pdir;
                    f.shareFidToken = str(it, "share_fid_token");
                    f.note = name;
                    out.add(f);
                }
            }
            queue = next;
        }
        out.sort(Comparator.comparing(r -> r.fileName == null ? "" : r.fileName));
        return out;
    }

    private static boolean isVideo(JsonObject it, String name) {
        if ("video".equals(str(it, "obj_category"))) return true;
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".m3u8")
                || n.endsWith(".ts") || n.endsWith(".avi") || n.endsWith(".mov");
    }

    /** UC 免登取链：关键是收集 file/download 下发的 __pugs，否则 OSS 回调 403。 */
    public static Direct ucResolve(Ref ref) throws Exception {
        String stoken = ref.stoken;
        if (stoken == null || stoken.isBlank()) {
            JsonObject t = ucApi("share/sharepage/token?pr=UCBrowser&fr=pc",
                    map("pwd_id", ref.shareId, "passcode",
                            ref.passcode == null ? "" : ref.passcode));
            stoken = str(t, "stoken");
        }
        Map<String, Object> body = omap(
                "fids", List.of(ref.fid),
                "fids_token", List.of(ref.shareFidToken == null ? "" : ref.shareFidToken),
                "pwd_id", ref.shareId, "stoken", stoken, "speedup_session", "");
        Resp r = post(UC_API + "file/download?pr=UCBrowser&fr=pc", body,
                map("User-Agent", UC_UA, "Referer", "https://drive.uc.cn",
                        "Content-Type", "application/json"));
        JsonObject j = r.json();
        if (r.status >= 400 || (j.has("code") && j.get("code").getAsInt() != 0)) {
            throw new IllegalStateException("UC 取链失败: " + str(j, "message"));
        }
        JsonArray arr = j.has("data") && j.get("data").isJsonArray()
                ? j.getAsJsonArray("data") : new JsonArray();
        String url = arr.size() > 0 && arr.get(0).isJsonObject()
                ? str(arr.get(0).getAsJsonObject(), "download_url") : "";
        if (!url.startsWith("http")) throw new IllegalStateException("UC 直链为空");

        String cookie = r.cookies("");
        // 实测矩阵（2026-11-06）：OSS checkplay 回调会拒绝带 Referer 的请求
        // （forbid[deny]），但不能缺 __pugs Cookie（require login）。
        // 因此播放头只保留 UA / Accept-Encoding / Cookie，绝不加 Referer。
        Map<String, String> h = map(
                "User-Agent", UC_UA,
                "Accept-Encoding", "identity",
                "Cookie", cookie);
        if (cookie.isBlank()) h.remove("Cookie");
        return new Direct(url, h, url.contains(".m3u8") ? "hls" : "file");
    }

    // ------------------------------------------------------------ 夸克

    private static Map<String, String> quarkHeaders(String cookie) {
        return map("User-Agent", UA, "Referer", "https://pan.quark.cn/",
                "Cookie", cookie == null ? "" : cookie);
    }

    private static JsonObject quarkApi(String base, String path, String method,
                                       Object body, String cookie) throws Exception {
        Resp r = "POST".equals(method)
                ? post(base + path, body, quarkHeaders(cookie))
                : get(base + path, quarkHeaders(cookie));
        return r.json();
    }

    /** 夸克目录：无 Cookie 也能读 stoken 和目录，但播放一定要 Cookie。 */
    public static List<Ref> quarkCatalog(String shareId, String passcode, String cookie) throws Exception {
        JsonObject t = quarkApi(QUARK_H,
                "share/sharepage/token?pr=ucpro&fr=pc&uc_param_str=", "POST",
                map("pwd_id", shareId, "passcode", passcode == null ? "" : passcode), cookie);
        String stoken = t.has("data") ? str(t.getAsJsonObject("data"), "stoken") : "";
        if (stoken.isBlank()) throw new IllegalStateException("夸克分享失效: " + str(t, "message"));

        List<Ref> out = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of("0"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int depth = 0; depth < 3 && !queue.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String pdir : queue) {
                String qs = q("pr", "ucpro", "fr", "pc", "uc_param_str", "", "ver", "2",
                        "pwd_id", shareId, "stoken", stoken, "pdir_fid", pdir,
                        "force", "0", "_page", 1, "_size", 200, "_fetch_total", 1,
                        "_sort", "file_type:asc,file_name:asc");
                JsonObject j = quarkApi(QUARK_H, "share/sharepage/detail?" + qs,
                        "GET", null, cookie);
                if (!j.has("data")) continue;
                JsonArray list = j.getAsJsonObject("data").getAsJsonArray("list");
                if (list == null) continue;
                for (JsonElement el : list) {
                    JsonObject it = el.getAsJsonObject();
                    String fid = str(it, "fid"), name = str(it, "file_name");
                    boolean dir = it.has("dir") && it.get("dir").getAsBoolean();
                    if (dir) { if (depth < 2) next.add(fid); continue; }
                    if (!isVideo(it, name) || !seen.add(fid)) continue;
                    Ref f = new Ref();
                    f.provider = "quark"; f.shareId = shareId; f.passcode = passcode;
                    f.stoken = stoken; f.fid = fid; f.fileName = name;
                    f.pdirFid = pdir; f.shareFidToken = str(it, "share_fid_token");
                    f.note = name;
                    out.add(f);
                }
            }
            queue = next;
        }
        out.sort(Comparator.comparing(r -> r.fileName == null ? "" : r.fileName));
        return out;
    }

    /** 夸克取链：转存到 ftv-cache -> 原画 download；失败回退 v2/play。 */
    public static Direct quarkResolve(Ref ref, String cookie) throws Exception {
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("未配置夸克 Cookie：请在网盘配置中心填写 quark_cookie（可扫码打开）");
        }
        // 1. 找/建缓存目录
        String qs = "pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=50"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,updated_at:desc"
                + "&fetch_all_file=1&fetch_risk_file_name=1";
        JsonObject sort = quarkApi(QUARK_PC, "file/sort?" + qs, "GET", null, cookie);
        String toFid = "";
        if (sort.has("data")) {
            JsonArray list = sort.getAsJsonObject("data").getAsJsonArray("list");
            if (list != null) for (JsonElement el : list) {
                JsonObject it = el.getAsJsonObject();
                if (it.has("dir") && it.get("dir").getAsBoolean()
                        && "ftv-cache".equals(str(it, "file_name"))) { toFid = str(it, "fid"); break; }
            }
        }
        if (toFid.isBlank()) {
            JsonObject cr = quarkApi(QUARK_PC, "file?pr=ucpro&fr=pc&uc_param_str=", "POST",
                    map("pdir_fid", "0", "file_name", "ftv-cache", "dir_path", "",
                            "dir_init_lock", false), cookie);
            if (cr.has("data")) toFid = str(cr.getAsJsonObject("data"), "fid");
        }
        if (toFid.isBlank()) throw new IllegalStateException("夸克缓存目录创建失败，请检查 Cookie");

        // 2. 转存
        String saveQs = "pr=ucpro&fr=pc&uc_param_str=";
        Map<String, Object> saveBody = omap("fid_list", List.of(ref.fid),
                "fid_token_list", List.of(ref.shareFidToken == null ? "" : ref.shareFidToken),
                "pdir_fid", ref.pdirFid == null ? "0" : ref.pdirFid,
                "pwd_id", ref.shareId, "scene", "link",
                "stoken", ref.stoken == null ? "" : ref.stoken, "to_pdir_fid", toFid);
        JsonObject saved = quarkApi(QUARK_H, "share/sharepage/save?" + saveQs,
                "POST", saveBody, cookie);
        String myFid = savedFid(saved);
        String taskId = saved.has("data") ? str(saved.getAsJsonObject("data"), "task_id") : "";
        for (int i = 0; i < 6 && myFid.isBlank() && !taskId.isBlank(); i++) {
            Thread.sleep(1000);
            JsonObject task = quarkApi(QUARK_H,
                    "task?pr=ucpro&fr=pc&uc_param_str=&task_id=" + taskId + "&retry_index=" + i,
                    "GET", null, cookie);
            myFid = savedFid(task);
        }
        if (myFid.isBlank()) throw new IllegalStateException("夸克转存失败，请检查 Cookie 或分享状态");

        // 3. 原画鉴权 + 下载
        quarkApi(QUARK_PC, "auth/pc/flush?pr=ucpro&fr=pc&sys=win32&ve=3.1.8",
                "GET", null, cookie);
        JsonObject dl = quarkApi(QUARK_PC, "file/download?pr=ucpro&fr=pc&uc_param_str=",
                "POST", map("fids", List.of(myFid)), cookie);
        String url = firstDownloadUrl(dl);
        if (url.isBlank()) {
            JsonObject pl = quarkApi(QUARK_PC, "file/v2/play?pr=ucpro&fr=pc&uc_param_str=",
                    "POST", map("fid", myFid, "resolutions", "normal,low,high,super,2k,4k"), cookie);
            url = firstDownloadUrl(pl);
        }
        if (url.isBlank()) throw new IllegalStateException("夸克直链为空（Cookie 可能已过期）");
        return new Direct(url, map("User-Agent", UA, "Cookie", cookie,
                "Referer", "https://pan.quark.cn/"), "auto");
    }

    private static String savedFid(JsonObject j) {
        if (j == null || !j.has("data") || !j.get("data").isJsonObject()) return "";
        JsonObject d = j.getAsJsonObject("data");
        List<JsonObject> cands = new ArrayList<>();
        cands.add(d);
        if (d.has("task_resp") && d.get("task_resp").isJsonObject()
                && d.getAsJsonObject("task_resp").has("data")
                && d.getAsJsonObject("task_resp").get("data").isJsonObject()) {
            cands.add(d.getAsJsonObject("task_resp").getAsJsonObject("data"));
        }
        for (JsonObject cand : cands) {
            if (cand.has("save_as") && cand.get("save_as").isJsonObject()) {
                JsonObject sa = cand.getAsJsonObject("save_as");
                for (String k : List.of("save_as_top_fids", "save_as_select_top_fids")) {
                    if (sa.has(k) && sa.get(k).isJsonArray() && sa.getAsJsonArray(k).size() > 0) {
                        return sa.getAsJsonArray(k).get(0).getAsString();
                    }
                }
            }
        }
        return "";
    }

    private static String firstDownloadUrl(JsonObject j) {
        if (!j.has("data")) return "";
        if (j.get("data").isJsonArray()) {
            for (JsonElement el : j.getAsJsonArray("data")) {
                if (el.isJsonObject()) {
                    String u = str(el.getAsJsonObject(), "download_url");
                    if (!u.isBlank()) return u;
                }
            }
        } else if (j.get("data").isJsonObject()) {
            return str(j.getAsJsonObject("data"), "download_url");
        }
        return "";
    }

    // ------------------------------------------------------------ 统一入口

    public static List<Ref> catalog(Ref share) throws Exception {
        switch (share.provider == null ? "" : share.provider) {
            case "uc": return ucCatalog(share.shareId, share.passcode);
            case "quark": return quarkCatalog(share.shareId, share.passcode,
                    PanConfig.get("quark_cookie"));
            default: throw new IllegalStateException(
                    share.provider + " 目录读取需要对应网盘客户端，本服务已实现 UC/夸克");
        }
    }

    public static Direct resolve(Ref ref) throws Exception {
        switch (ref.provider == null ? "" : ref.provider) {
            case "uc": return ucResolve(ref);
            case "quark": return quarkResolve(ref, PanConfig.get("quark_cookie"));
            default: throw new IllegalStateException(
                    ref.provider + " 取链未实现，请在配置中心填 Cookie 或改用解析接口");
        }
    }
}
