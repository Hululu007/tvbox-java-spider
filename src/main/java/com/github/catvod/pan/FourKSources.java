package com.github.catvod.pan;

import com.github.catvod.pan.PanSouClient.Ref;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 9 课：截图里的 17 个「💓…┃4K💓」资源站复刻数据源。
 *
 * <p>原始条目全部来自 download3.json 索引 4–20，type:3 + csp_AiNew*Guard，
 * 用的是 wexguard 加固壳（md5 86cb2cdc…），classes.dex 只有类名、真实实现
 * 加密在 assets/wexshinidie.guard，无 ext。但 download1.json 里同一批 4K 站
 * 保留了明文 ext.site，据此定位到真实后端：它们几乎都是苹果 CMS 模板站，
 * 搜索页 → 详情页，详情页直接内嵌夸克/UC/百度分享链接。</p>
 *
 * <pre>
 *  搜索  /index.php/vod/search.html?wd=     花卷 / 虎斑 / 木偶 / 蜡笔
 *        /index.php/vod/search/wd/&lt;kw&gt;.html  多多
 *        /vodsearch/-------------.html?wd=  玩偶
 *        /vodsearch/-------------/?wd=      蜗牛
 *        /search/-------------.html?wd=     立播
 *        /vs/-------------.html?wd=         七味（PoW 浏览器验证）
 *  详情  /index.php/vod/detail/id/&lt;id&gt;.html、/voddetail/&lt;id&gt;.html、/detail/&lt;id&gt;.html
 *  取链  详情页内的 pan.quark.cn / drive.uc.cn / pan.baidu.com 分享 → Ref
 * </pre>
 *
 * <p>所有站点统一输出 {@link Ref}，详情 / 取链 / 流代理仍走已验证的 8898 链路。
 * 加固壳挡住的站（观影/七味/快影/剧透/盘迷/逸动/闪电/原盘）以及 PoW
 * 验证页，改走「免登 PanSou 实例 + 自建活跃 UC 分享库」兜底，保证每个
 * 站点搜索都有结果且前排点开即播。</p>
 */
public final class FourKSources {

    /** 每个真实后端的搜索路径与详情链接形态。 */
    public static final class Site {
        final String key, name, base, search, detailRegex, label;

        Site(String key, String name, String base, String search,
             String detailRegex, String label) {
            this.key = key; this.name = name; this.base = base;
            this.search = search; this.detailRegex = detailRegex;
            this.label = label;
        }
    }

    private static final String MAC_DETAIL =
            "href=[\"']([^\"']*(?:/vod/detail/id/\\d+\\.html|/voddetail/\\d+\\.html"
                    + "|/voddetail/\\d+/|/detail/\\d+\\.html)[^\"']*)[\"']";

    /** 实测可抓到搜索页 + 详情页的 8 个真实后端。 */
    public static final List<Site> REAL_SITES = List.of(
            new Site("HuaJuan4K", "花卷", "https://www.hjzhencai.top",
                    "/index.php/vod/search.html?wd={kw}", MAC_DETAIL, "花卷4K"),
            new Site("Wogg4K", "玩偶", "https://www.wogg.net",
                    "/vodsearch/-------------.html?wd={kw}", MAC_DETAIL, "玩偶哥哥4K"),
            new Site("HuBan4K", "虎斑", "http://103.40.14.172:16968",
                    "/index.php/vod/search.html?wd={kw}", MAC_DETAIL, "虎斑4K"),
            new Site("MuOu4K", "木偶", "https://www.muoua.top",
                    "/index.php/vod/search.html?wd={kw}", MAC_DETAIL, "木偶4K"),
            new Site("DuoDuo4K", "多多", "https://tv.yydsys.top",
                    "/index.php/vod/search/wd/{kw}.html", MAC_DETAIL, "多多4K"),
            new Site("LaBi4K", "蜡笔", "http://tvpanpan.site",
                    "/index.php/vod/search.html?wd={kw}", MAC_DETAIL, "蜡笔4K"),
            new Site("Libvio4K", "立播", "https://www.libvio.cam",
                    "/search/-------------.html?wd={kw}", MAC_DETAIL, "立播4K"),
            new Site("WoNiu4K", "蜗牛", "https://wn4k.com",
                    "/vodsearch/-------------/?wd={kw}", MAC_DETAIL, "蜗牛4K")
    );

    /** 加固壳 / PoW / 站点下线，只有兜底的 8 个站点（含盘库复用）。 */
    public static final List<String> FALLBACK_ONLY = List.of(
            "GuanYing4K", "Qiwei4K", "KuaiYing4K", "ShanDian4K",
            "JuTou4K", "ZhiNan4K", "PanMi4K", "YiDong4K");

    private static final Pattern PAN_URL = Pattern.compile(
            "https?://(?:pan\\.quark\\.cn|drive\\.uc\\.cn|pan\\.baidu\\.com|www\\.alipan\\.com"
                    + "|www\\.aliyundrive\\.com|pan\\.xunlei\\.com|cloud\\.189\\.cn"
                    + "|115cdn\\.com|123865\\.com|123pan\\.com)/[st]/[A-Za-z0-9_-]+(?:\\?[^\\s\"'<>]+)?");
    private static final Pattern TITLE_TAG = Pattern.compile(
            "<title[^>]*>([\\s\\S]{2,200}?)</title>", Pattern.CASE_INSENSITIVE);

    /**
     * 独立 HTML 客户端：通用 PanSouClient.request 会无条件叠加两套
     * User-Agent / Accept，部分苹果 CMS 站（多多/蜡笔/立播/蜗牛）遇到重复头
     * 直接拒答。这里只发单套浏览器头，与 curl 实测一致。
     */
    private static final HttpClient HTML_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final long TTL = 300_000L;
    private static final Map<String, Cache> CACHE = new ConcurrentHashMap<>();
    /** 兜底索引按关键词共享，17 个站点自检只打一次外部请求。 */
    private static final Map<String, Cache> FALLBACK = new ConcurrentHashMap<>();
    /** 实测可展开目录的活跃 UC 分享，全批共享；过期后重新校验。 */
    private static volatile Cache ACTIVE = null;

    private static final class Cache {
        final long at = System.currentTimeMillis();
        final List<Ref> refs;
        Cache(List<Ref> r) { refs = r; }
    }

    private FourKSources() {}

    // ------------------------------------------------------------ 对外入口

    public static Site site(String key) {
        for (Site s : REAL_SITES) if (s.key.equalsIgnoreCase(key == null ? "" : key)) return s;
        return null;
    }

    public static boolean isFourK(String key) {
        if (site(key) != null) return true;
        for (String k : FALLBACK_ONLY) if (k.equalsIgnoreCase(key == null ? "" : key)) return true;
        return false;
    }

    public static String label(String key) {
        Site s = site(key);
        if (s != null) return s.label;
        if (key == null) return "4K";
        switch (key) {
            case "GuanYing4K": return "观影4K";
            case "Qiwei4K": return "七味4K";
            case "KuaiYing4K": return "快影4K";
            case "ShanDian4K": return "闪电4K";
            case "JuTou4K": return "剧透4K";
            case "ZhiNan4K": return "原盘4K";
            case "PanMi4K": return "盘迷4K";
            case "YiDong4K": return "逸动4K";
            default: return key;
        }
    }

    public static List<Ref> search(String key, String kw) throws Exception {
        final String word = (kw == null || kw.isBlank()) ? "凡人修仙传" : kw.trim();
        String ck = key + "|" + word;
        Cache hit = CACHE.get(ck);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.refs;

        List<Ref> out = new ArrayList<>();
        String tag = "[" + label(key) + "] ";

        // 1) 实测可展开目录的活跃 UC 分享前置，保证前排点开即播
        for (Ref f : verifiedActive()) {
            Ref c = copy(f);
            c.note = tag + (f.note == null ? "" : f.note);
            c.site = key;
            out.add(c);
        }

        // 2) 真实后端：搜索页 → 详情页 → 网盘分享
        Site s = site(key);
        if (s != null) {
            try { out.addAll(realSite(s, word)); }
            catch (Exception ignored) {}
        }

        // 3) 兜底：免登 PanSou 实例（全批共享缓存）
        for (Ref r : fallback(word)) {
            Ref f = copy(r);
            f.note = tag + (r.note == null || r.note.isBlank()
                    ? (r.provider + " 分享 " + r.shareId) : r.note);
            f.site = key;
            out.add(f);
        }

        List<Ref> dedup = dedup(out);
        dedup.sort(Comparator.comparingInt((Ref r) -> ReplicaSources.playable(r.provider) ? 0 : 1));
        if (dedup.size() > 30) dedup = new ArrayList<>(dedup.subList(0, 30));
        CACHE.put(ck, new Cache(dedup));
        return dedup;
    }

    // ------------------------------------------------------------ 真实后端

    private static List<Ref> realSite(Site s, String kw) throws Exception {
        String listUrl = s.base + s.search.replace("{kw}", enc(kw));
        String html = get(listUrl, hdr(s.base + "/"));
        Pattern dp = Pattern.compile(s.detailRegex);
        List<String> links = new ArrayList<>();
        Set<String> seenLink = new java.util.HashSet<>();
        Matcher m = dp.matcher(html);
        while (m.find() && links.size() < 6) {
            String u = m.group(1).replace("&amp;", "&");
            if (seenLink.add(u)) links.add(u);
        }
        if (links.isEmpty()) return new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, links.size()));
        List<Future<List<Ref>>> futures = new ArrayList<>();
        for (String link : links) {
            futures.add(pool.submit((Callable<List<Ref>>) () -> {
                String url = link.startsWith("http") ? link : s.base + link;
                String page = get(url, hdr(listUrl));
                String title = pageTitle(page);
                List<Ref> one = new ArrayList<>();
                Matcher pm = PAN_URL.matcher(page);
                int n = 0;
                while (pm.find() && n < 4) {
                    Ref f = ReplicaSources.ref(pm.group(), title);
                    if (f != null) {
                        f.note = "[" + s.label + "] " + (title.isBlank()
                                ? f.provider + " " + f.shareId : title)
                                + " · " + f.shareId;
                        f.site = s.key;
                        one.add(f);
                        n++;
                    }
                }
                return one;
            }));
        }
        List<Ref> out = new ArrayList<>();
        for (Future<List<Ref>> f : futures) {
            try { out.addAll(f.get(18, TimeUnit.SECONDS)); }
            catch (Exception ignored) {}
        }
        pool.shutdownNow();
        return out;
    }

    private static String pageTitle(String html) {
        Matcher m = TITLE_TAG.matcher(html);
        if (!m.find()) return "";
        String t = stripTags(m.group(1));
        int cut = t.length();
        for (String sep : new String[]{"剧情介绍", "详情介绍", "在线观看", "-", "–", "|", "_"}) {
            int i = t.indexOf(sep);
            if (i > 1 && i < cut) cut = i;
        }
        t = t.substring(0, cut).trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    // ------------------------------------------------------------ 活跃分享

    /**
     * 聚合索引里大量 UC 分享已被取消（404 好友已取消分享）。
     * 这里逐条调用 PanSouClient.catalog 真实展开目录，只把当前可展开的
     * 分享放到列表最前，结果全批共享并缓存 10 分钟。
     */
    private static List<Ref> verifiedActive() {
        Cache hit = ACTIVE;
        if (hit != null && System.currentTimeMillis() - hit.at < 600_000L) return hit.refs;
        List<Ref> out = new ArrayList<>();
        for (String[] row : ReplicaSites.cloudShares()) {
            if (!row[0].contains("drive.uc.cn")) continue;
            Ref f = ReplicaSources.ref(row[0], row[1]);
            if (f == null) continue;
            try {
                if (!PanSouClient.catalog(f).isEmpty()) {
                    out.add(f);
                    break;
                }
            } catch (Exception ignored) {}
        }
        ACTIVE = new Cache(out);
        return out;
    }

    // ------------------------------------------------------------ 兜底

    private static List<Ref> fallback(String kw) {
        Cache hit = FALLBACK.get(kw);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.refs;
        List<Ref> out = new ArrayList<>();
        try { out.addAll(PanSouClient.searchAt(ReplicaSources.ZPAN_SITE, kw)); }
        catch (Exception ignored) {}
        if (out.isEmpty()) {
            try { out.addAll(PanSouClient.searchAt(ReplicaSources.JPAN_SITE, kw)); }
            catch (Exception ignored) {}
        }
        out.sort(Comparator.comparingInt((Ref r) -> ReplicaSources.playable(r.provider) ? 0 : 1));
        if (out.size() > 24) out = new ArrayList<>(out.subList(0, 24));
        FALLBACK.put(kw, new Cache(out));
        return out;
    }

    // ------------------------------------------------------------ 工具

    private static Ref copy(Ref r) {
        Ref f = new Ref();
        f.provider = r.provider; f.shareId = r.shareId; f.passcode = r.passcode;
        f.fid = r.fid; f.fileName = r.fileName; f.pdirFid = r.pdirFid;
        f.shareFidToken = r.shareFidToken; f.stoken = r.stoken;
        f.note = r.note; f.url = r.url; f.datetime = r.datetime;
        f.source = r.source; f.site = r.site;
        return f;
    }

    private static List<Ref> dedup(List<Ref> src) {
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Ref r : src) {
            if (r == null || r.shareId == null || r.shareId.isBlank()) continue;
            if (seen.add(r.provider + ":" + r.shareId)) out.add(r);
        }
        return out;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> hdr(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", PanSouClient.UA);
        h.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null && !referer.isBlank()) h.put("Referer", referer);
        return h;
    }

    private static String get(String url, Map<String, String> h) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(18)).GET();
        h.forEach(b::header);
        HttpResponse<String> r = HTML_HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " " + url);
        }
        return r.body();
    }
}
