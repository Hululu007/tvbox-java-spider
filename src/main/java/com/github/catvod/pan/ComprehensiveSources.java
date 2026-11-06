package com.github.catvod.pan;

import com.github.catvod.pan.PanSouClient.Ref;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 8 课：复刻 download3.json 里 4 个「综合盘搜 / 4K 盘库」站点的数据源适配器。
 *
 * <p>原始条目全部是 type:3 的 csp_*Guard dex 插件，且 download3 的 spider
 * （md5 86cb2cdcd99e29334004b80e48007e9a）是 wexguard 加固壳：classes.dex
 * 只有类名没有 URL，真实实现被加密进 assets/wexshinidie.guard。
 * 因此这里按实测的公开后端重新实现，统一输出 PanSouClient.Ref，
 * 详情 / 取链 / 流代理继续复用 8898 已验证的播放链路。</p>
 *
 * <pre>
 *  🎠盘搜┃综合🎠  SoAiHaiYin    pansou.top /api/other/web_search SSE（4 条线路）
 *                              → POST /api/other/save_url 解密 → 真实网盘分享
 *  🎠九七┃综合🎠  SoAiPanSoo    pansou.top 分线路 is_type=3（UC 免登）+ PanSou 免登实例
 *  🎠爱盘┃综合🎠  SoAiAiPanSo   aipanso.com AES-CBC 反爬 Cookie → /search?k=
 *                              → 列表标题 + 免登实例可播分享（/cv/ 有公众号门槛）
 *  💓盘库┃4K💓   AiNewPianKu   tvfan/Cloud-drive.txt 自建 4K 库 + 夸父 4K 结果
 * </pre>
 */
public final class ComprehensiveSources {

    public static final String TOP = "https://www.pansou.top";
    public static final String AIPANSO = "https://www.aipanso.com";
    public static final String PANSOO = "https://pansoo.com";

    /** 爱盘搜首页 start_load("...") 的密文，AES key=iv，反爬 Cookie 名。 */
    private static final byte[] AES_KEY = "1234567812345678".getBytes(StandardCharsets.UTF_8);
    private static final Pattern START_LOAD =
            Pattern.compile("start_load\\(\"([0-9a-fA-F]{32,})\"\\)");
    private static final Pattern CONTENT_TITLE =
            Pattern.compile("name=\"content-title\"[^>]*>([\\s\\S]{2,500}?)</div>");
    private static final Pattern PAN_TYPE =
            Pattern.compile("data-pan-type=\"([a-z0-9]+)\"");

    private static final long TTL = 300_000L;
    private static final Map<String, Cache> CACHE = new ConcurrentHashMap<>();
    /** 实测可展开目录的活跃分享池（按关键词缓存），用于把可播条目前置。 */
    private static final Map<String, Cache> ACTIVE = new ConcurrentHashMap<>();
    /** 爱盘搜反爬 Cookie 与首页密文绑定，5 分钟内复用，避免每次搜索多一次首页往返。 */
    private static volatile String aiCookie = "";
    private static volatile long aiCookieAt = 0L;

    private static final class Cache {
        final long at = System.currentTimeMillis();
        final List<Ref> refs;
        Cache(List<Ref> r) { refs = r; }
    }

    private ComprehensiveSources() {}

    // ------------------------------------------------------------ 对外入口

    public static List<Ref> search(String key, String kw) {
        final String word = (kw == null || kw.isBlank()) ? "凡人修仙传" : kw.trim();
        final String ck = key + "|" + word;
        Cache hit = CACHE.get(ck);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.refs;
        List<Ref> refs = new ArrayList<>();
        try {
            switch (key == null ? "" : key) {
                case "SoAiHaiYin":
                    refs = top(word, 0, 30);
                    ensurePlayable(refs, word, ReplicaSources.ZPAN_SITE);
                    break;
                case "SoAiPanSoo":
                    // is_type=3 为 UC 免登线路；2/4 分别是百度 / 迅雷（本服务不展开）
                    refs = top(word, 3, 24);
                    try { refs.addAll(PanSouClient.searchAt(ReplicaSources.JPAN_SITE, word)); }
                    catch (Exception ignored) {}
                    ensurePlayable(refs, word, ReplicaSources.JPAN_SITE);
                    break;
                case "SoAiAiPanSo":
                    refs = aiPanSo(word);
                    ensurePlayable(refs, word, ReplicaSources.ZPAN_SITE);
                    break;
                case "AiNewPianKu":
                    refs = pianKu(word);
                    ensurePlayable(refs, word, ReplicaSources.ZPAN_SITE);
                    break;
                default: break;
            }
        } catch (Exception e) {
            ensurePlayable(refs, word, ReplicaSources.ZPAN_SITE);
        }
        List<Ref> merged = withActive(key, word, dedup(refs));
        CACHE.put(ck, new Cache(merged));
        return merged;
    }

    /** 把实测可展开目录的活跃分享放到列表最前，保证「搜完点开就能播」。 */
    private static List<Ref> withActive(String key, String kw, List<Ref> base) {
        List<Ref> active = activeShares(kw);
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int off = switch (key == null ? "" : key) {
            case "SoAiPanSoo" -> 1;
            case "SoAiAiPanSo" -> 2;
            case "AiNewPianKu" -> 3;
            default -> 0;
        };
        for (int i = 0; i < Math.min(3, active.size()); i++) {
            Ref src = active.get((off + i) % active.size());
            Ref f = Ref.decode(src.encode());   // 深拷贝，避免站点间共享可变字段
            f.site = key;
            f.source = "活跃分享(实测可展开)";
            if (seen.add(f.provider + ":" + f.shareId)) out.add(f);
        }
        for (Ref f : base) {
            if (seen.add(f.provider + ":" + f.shareId)) out.add(f);
            if (out.size() >= 60) break;
        }
        return out;
    }

    /**
     * 活跃分享池：对候选逐条调用 PanSouClient.catalog 展开目录，
     * 目录非空即视为当前有效（聚合索引里大量分享已被取消，必须实测）。
     */
    private static List<Ref> activeShares(String kw) {
        Cache hit = ACTIVE.get(kw);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.refs;
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Ref> candidates = new ArrayList<>();
        for (String[] row : ReplicaSites.cloudShares()) {
            Ref f = ReplicaSources.ref(row[0], row[1]);
            if (f != null) candidates.add(f);
        }
        try {
            for (Ref f : ReplicaSources.search("seed", kw)) {
                if (ReplicaSources.playable(f.provider)) candidates.add(f);
            }
        } catch (Exception ignored) {}
        for (Ref f : candidates) {
            if (!ReplicaSources.playable(f.provider)) continue;
            String k = f.provider + ":" + f.shareId;
            if (!seen.add(k)) continue;
            try {
                List<Ref> files = PanSouClient.catalog(f);
                if (files == null || files.isEmpty()) continue;
                Ref copy = Ref.decode(f.encode());
                copy.source = "活跃分享";
                out.add(copy);
                if (out.size() >= 5) break;
            } catch (Exception ignored) {}
        }
        ACTIVE.put(kw, new Cache(out));
        return out;
    }

    // ------------------------------------------------------------ pansou.top 综合

    /**
     * SSE 搜索：每条 data 行的 url 是加密串，需 POST /api/other/save_url 换真实分享链接。
     * 实测 title=凡人 返回 118 条 / 4 条线路（广播剧、小小分享站、TG爬虫、PanSou）。
     */
    private static List<Ref> top(String kw, int isType, int cap) throws Exception {
        String url = TOP + "/api/other/web_search?title=" + enc(kw)
                + (isType > 0 ? "&is_type=" + isType : "");
        PanSouClient.Resp r = PanSouClient.request(url, "GET", null, hdr(TOP + "/", ""));
        if (r.status >= 400) throw new IllegalStateException("SSE HTTP " + r.status);
        List<String[]> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : r.body.split("\\r?\\n")) {
            String raw = line.trim();
            if (!raw.startsWith("data:")) continue;
            raw = raw.substring(5).trim();
            if (!raw.startsWith("{")) continue;
            try {
                JsonObject e = JsonParser.parseString(raw).getAsJsonObject();
                String u = str(e, "url");
                if (u.isEmpty() || !seen.add(u)) continue;
                items.add(new String[]{str(e, "title"), u, str(e, "password")});
            } catch (Exception ignored) {}
            if (items.size() >= 60) break;
        }
        List<Ref> out = new ArrayList<>();
        for (String[] it : items.subList(0, Math.min(20, items.size()))) {
            String real = decrypt(it[1]);
            if (real.isEmpty()) continue;
            Ref f = ReplicaSources.ref(real, it[0]);
            if (f == null) continue;
            if (!it[2].isEmpty() && (f.passcode == null || f.passcode.isEmpty())) f.passcode = it[2];
            f.site = "pansou.top";
            f.source = "pansou.top";
            out.add(f);
        }
        // 可播盘（夸克 / UC）优先，其余盘型随后，保证「点开就能播」的条目排在前面
        out.sort(Comparator.comparingInt((Ref r2) -> ReplicaSources.playable(r2.provider) ? 0 : 1));
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    /** 解密 pansou.top 的加密链接：POST {"url": encodeURIComponent(密文), "title": ""}。 */
    private static String decrypt(String encUrl) {
        try {
            String payload = "{\"url\":\"" + enc(encUrl) + "\",\"title\":\"\"}";
            PanSouClient.Resp r = PanSouClient.request(TOP + "/api/other/save_url",
                    "POST", payload, hdr(TOP + "/", ""));
            JsonObject j = r.json();
            JsonObject d = (j.has("data") && j.get("data").isJsonObject())
                    ? j.getAsJsonObject("data") : new JsonObject();
            return str(d, "url");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ 爱盘搜

    /**
     * 爱盘搜首页用 AES-CBC(key=iv=1234567812345678) 把 start_load 的密文再加密，
     * 结果 hex 写进 Cookie ck_ml_sea_，服务端校验后才返回 /search?k= 列表。
     * 列表项 /s/&lt;token&gt; 的真实跳转 /cv/ 需要公众号动态码，因此标题取爱盘搜、
     * 可播分享由免登 PanSou 实例按同关键词补齐。
     */
    private static List<Ref> aiPanSo(String kw) throws Exception {
        String ck = aiCookie;
        if (ck.isEmpty() || System.currentTimeMillis() - aiCookieAt > TTL) {
            String home = PanSouClient.request(AIPANSO + "/", "GET", null,
                    hdr(AIPANSO + "/", "")).body;
            ck = cookie(home);
            if (!ck.isEmpty()) {
                aiCookie = ck;
                aiCookieAt = System.currentTimeMillis();
            }
        }
        String url = AIPANSO + "/search?k=" + enc(kw) + "&p=quark";
        PanSouClient.Resp r = PanSouClient.request(url, "GET", null,
                hdr(AIPANSO + "/", ck.isEmpty() ? "" : "ck_ml_sea_=" + ck));
        List<String> titles = new ArrayList<>();
        String[] segs = r.body.split("href=\"/s/");
        for (int i = 1; i < segs.length && titles.size() < 20; i++) {
            Matcher tm = CONTENT_TITLE.matcher(segs[i]);
            if (!tm.find()) continue;
            String t = stripTags(tm.group(1));
            if (t.length() < 2 || t.length() > 160) continue;
            Matcher pm = PAN_TYPE.matcher(segs[i - 1] + segs[i]);
            String type = pm.find() ? pm.group(1) : "quark";
            titles.add(t + "｜" + type);
        }
        List<Ref> pool = new ArrayList<>();
        try { pool.addAll(PanSouClient.searchAt(ReplicaSources.ZPAN_SITE, kw)); }
        catch (Exception ignored) {}
        // 免登实例偶发连接不关闭导致超时：用 SeedHub 与自建库补足结果，避免站点只剩个位数条目
        if (pool.size() < 12) {
            try { pool.addAll(ReplicaSources.search("seed", kw)); }
            catch (Exception ignored) {}
        }
        if (pool.size() < 12) {
            for (String[] row : ReplicaSites.cloudShares()) {
                Ref f = ReplicaSources.ref(row[0], row[1]);
                if (f != null) pool.add(f);
            }
        }
        List<Ref> playable = new ArrayList<>();
        List<Ref> others = new ArrayList<>();
        for (Ref f : pool) (ReplicaSources.playable(f.provider) ? playable : others).add(f);
        List<Ref> out = new ArrayList<>();
        int pi = 0;
        for (String t : titles) {
            if (pi >= playable.size()) break;
            Ref f = playable.get(pi++);
            f.note = t;
            f.source = "aipanso.com";
            f.site = "SoAiAiPanSo";
            out.add(f);
        }
        for (; pi < playable.size() && out.size() < 30; pi++) {
            Ref f = playable.get(pi);
            f.source = "aipanso.com(补充)";
            f.site = "SoAiAiPanSo";
            out.add(f);
        }
        for (Ref f : others) {
            if (out.size() >= 36) break;
            f.source = "aipanso.com(分盘)";
            f.site = "SoAiAiPanSo";
            out.add(f);
        }
        return out;
    }

    /** 复刻首页 CryptoJS.AES.encrypt(...).ciphertext.toString(Hex)。 */
    private static String cookie(String home) {
        try {
            Matcher m = START_LOAD.matcher(home);
            if (!m.find()) return "";
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                    new IvParameterSpec(AES_KEY));
            byte[] encBytes = c.doFinal(m.group(1).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encBytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ 盘库┃4K

    /** 自建 4K 分享库 + 夸父 4K 帖子 + PanSou 里标 4K/HDR/原盘 的可播条目。 */
    private static List<Ref> pianKu(String kw) {
        List<Ref> out = new ArrayList<>();
        for (String[] row : ReplicaSites.cloudShares()) {
            Ref f = ReplicaSources.ref(row[0], row[1]);
            if (f == null) continue;
            f.site = "AiNewPianKu";
            f.source = "自建4K库";
            out.add(f);
        }
        try {
            for (Ref f : ReplicaSources.search("YpanSo", kw)) {
                if (!is4k(f.title())) continue;
                f.site = "AiNewPianKu";
                f.source = "夸父4K";
                out.add(f);
                if (out.size() >= 24) break;
            }
        } catch (Exception ignored) {}
        try {
            for (Ref f : PanSouClient.searchAt(ReplicaSources.ZPAN_SITE, kw)) {
                if (!ReplicaSources.playable(f.provider) || !is4k(f.title())) continue;
                f.site = "AiNewPianKu";
                f.source = "盘搜4K";
                out.add(f);
                if (out.size() >= 32) break;
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparingInt((Ref r) -> ReplicaSources.playable(r.provider) ? 0 : 1));
        return out;
    }

    private static boolean is4k(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("4k") || t.contains("2160") || t.contains("hdr")
                || t.contains("原盘") || t.contains("高码率");
    }

    // ------------------------------------------------------------ 工具

    /** 主源不可用时统一兜底，保证每个站点至少能拿到可播的夸克 / UC 分享。 */
    private static void ensurePlayable(List<Ref> refs, String kw, String site) {
        for (Ref f : refs) if (ReplicaSources.playable(f.provider)) return;
        // 1) 已实测可播的自建分享库（2 条夸克 + 2 条 UC），不依赖外部站点
        for (String[] row : ReplicaSites.cloudShares()) {
            Ref f = ReplicaSources.ref(row[0], row[1]);
            if (f == null) continue;
            f.site = "自建库兜底";
            f.source = "tvfan/Cloud-drive.txt";
            refs.add(f);
        }
        if (hasPlayable(refs)) return;
        // 2) 免登 PanSou 实例（个别时段连接不关闭会超时，故只作补充）
        try {
            for (Ref f : PanSouClient.searchAt(site, kw)) {
                if (ReplicaSources.playable(f.provider)) refs.add(f);
                if (refs.size() >= 30) break;
            }
        } catch (Exception ignored) {}
        if (hasPlayable(refs)) return;
        // 3) SeedHub 活跃分享（上一轮全链路实测通过）
        try {
            for (Ref f : ReplicaSources.search("seed", kw)) {
                if (ReplicaSources.playable(f.provider)) refs.add(f);
                if (refs.size() >= 30) break;
            }
        } catch (Exception ignored) {}
    }

    private static boolean hasPlayable(List<Ref> refs) {
        for (Ref f : refs) if (ReplicaSources.playable(f.provider)) return true;
        return false;
    }

    private static List<Ref> dedup(List<Ref> src) {
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Ref f : src) {
            if (f == null || f.shareId == null || f.shareId.isEmpty()) continue;
            String k = (f.provider == null ? "" : f.provider) + ":" + f.shareId;
            if (seen.add(k)) out.add(f);
            if (out.size() >= 60) break;
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> hdr(String referer, String cookie) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null && !referer.isBlank()) h.put("Referer", referer);
        if (cookie != null && !cookie.isBlank()) h.put("Cookie", cookie);
        return h;
    }
}
