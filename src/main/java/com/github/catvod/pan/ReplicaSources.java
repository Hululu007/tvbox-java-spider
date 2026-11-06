package com.github.catvod.pan;

import com.github.catvod.pan.PanSouClient.Ref;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5 个复刻站点的数据源适配器。统一输出 PanSouClient.Ref，
 * 因此详情 / 取链 / 流代理完全复用已验证的 8898 播放链路。
 *
 * <p>实测契约（2026-11-06）：</p>
 * <pre>
 *  ZPan/JPan  POST {site}/api/search {kw,res:merge,src:all}  → data.merged_by_type
 *             /api/health → {"auth_enabled":false,...}  → 无需 x-qp-k
 *  SeedHub    GET /s/&lt;kw&gt;/ 取 /movies/&lt;id&gt;/ → 详情页 /link_start/?redirect_to=pan_id_*
 *             → 中间页内嵌真实 pan.quark.cn/s/... 分享链接
 *  夸父       GET /search.htm?keyword=&lt;kw&gt;&range=1（带 bbs_sid/bbs_token）
 *             → data-href="thread-&lt;id&gt;.htm" → 帖子页内嵌各盘分享链接
 *  MDrive     读 tvfan/Cloud-drive.txt 的 share= 行（自建分享库）
 * </pre>
 */
public final class ReplicaSources {

    public static final String ZPAN_SITE = "http://107.173.211.148";
    public static final String JPAN_SITE = "https://so.252035.xyz";
    public static final String SEED_SITE = "https://seedog.cc";
    public static final String KFZY_SITE = "https://www.kfzy.net";

    /** 内置夸父论坛会话（download1.json 的 csp_PanWebShareKF.ext.cookie），可被配置中心覆盖。 */
    private static final String DEFAULT_KFZY_COOKIE = "";

    private static final String UA = PanSouClient.UA;
    private static final long TTL = 300_000L;
    private static final Map<String, Cache> CACHE = new ConcurrentHashMap<>();

    private static final Pattern SHARE_ID =
            Pattern.compile("/(?:s|t)/([^/?&#]+)");
    private static final Pattern PASSCODE =
            Pattern.compile("[?&](?:pwd|password|passcode)=([^&#]+)");
    private static final Pattern SEED_MOVIE =
            Pattern.compile("title=\"([^\"]{2,160})\"[^>]*href=\"/movies/(\\d+)/\"");
    private static final Pattern SEED_LINK =
            Pattern.compile("data-link=\"([^\"]+)\"[^>]*href=\"(/link_start/\\?[^\"]+)\"");
    private static final Pattern KFZY_THREAD =
            Pattern.compile("data-href=\"(thread-\\d+\\.htm)\"");
    private static final Pattern KFZY_TITLE =
            Pattern.compile("<a[^>]*href=\"thread-\\d+\\.htm\"[^>]*>([\\s\\S]{2,240}?)</a>");
    private static final Pattern PAN_URL = Pattern.compile(
            "https?://(?:pan\\.quark\\.cn|drive\\.uc\\.cn|pan\\.baidu\\.com|www\\.alipan\\.com"
                    + "|www\\.aliyundrive\\.com|pan\\.xunlei\\.com|cloud\\.189\\.cn"
                    + "|115cdn\\.com|123865\\.com|123pan\\.com)/[st]/[A-Za-z0-9_-]+(?:\\?[^\\s\"'<>]+)?");

    private static final class Cache {
        final long at = System.currentTimeMillis();
        final List<Ref> refs;
        Cache(List<Ref> r) { refs = r; }
    }

    private ReplicaSources() {}

    // ------------------------------------------------------------ 对外入口

    public static List<Ref> home(String key) throws Exception {
        return search(key, "凡人修仙传");
    }

    public static List<Ref> search(String key, String kw) throws Exception {
        final String word = (kw == null || kw.isBlank()) ? "凡人修仙传" : kw.trim();
        final String ck = key + "|" + word;
        Cache hit = CACHE.get(ck);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.refs;
        List<Ref> refs;
        switch (key == null ? "" : key) {
            case "MDrive": refs = myDrive(word); break;
            case "seed": refs = seedHub(word); break;
            case "ZPan": refs = PanSouClient.searchAt(ZPAN_SITE, word); break;
            case "JPan": refs = PanSouClient.searchAt(JPAN_SITE, word); break;
            case "YpanSo": refs = kfzy(word); break;
            case "SoAiHaiYin":
            case "SoAiPanSoo":
            case "SoAiAiPanSo":
            case "AiNewPianKu":
                refs = ComprehensiveSources.search(key, word); break;
            default:
                // 截图第三批 16 个「💓…┃4K💓」站点：真实苹果 CMS 后端 + 活跃分享兜底
                refs = FourKSources.isFourK(key)
                        ? FourKSources.search(key, word)
                        : new ArrayList<>();
        }
        CACHE.put(ck, new Cache(refs));
        return refs;
    }

    /** 盘型中文名；★ 表示本服务可直接展开目录并点开即播。 */
    public static String providerName(String p) {
        if (p == null) return "网盘";
        switch (p) {
            case "quark": return "夸克网盘";
            case "uc": return "UC网盘";
            case "baidu": return "百度网盘";
            case "115": return "115网盘";
            case "aliyun": return "阿里云盘";
            case "xunlei": return "迅雷云盘";
            case "tianyi": return "天翼云盘";
            case "123": return "123云盘";
            case "magnet": return "磁力";
            case "mobile": return "移动云盘";
            case "config": return "配置中心";
            default: return p;
        }
    }

    public static boolean playable(String p) {
        return "quark".equals(p) || "uc".equals(p);
    }

    // ------------------------------------------------------------ 我的云盘┃配置

    private static List<Ref> myDrive(String kw) {
        List<Ref> out = new ArrayList<>();
        for (String[] row : ReplicaSites.cloudShares()) {
            if (!kw.isBlank() && !(row[1].contains(kw) || row[0].contains(kw))) {
                // 自建库按备注/URL 过滤；kw 为默认热词时仍保留全部
                if (!"凡人修仙传".equals(kw)) continue;
            }
            Ref f = ref(row[0], row[1]);
            if (f != null) { f.site = "MDrive"; out.add(f); }
        }
        return out;
    }

    // ------------------------------------------------------------ 聚剧┃四盘 SeedHub

    private static List<Ref> seedHub(String kw) throws Exception {
        String listUrl = SEED_SITE + "/s/" + enc(kw) + "/";
        String html = get(listUrl, hdr(SEED_SITE + "/", ""));
        List<String[]> movies = new ArrayList<>();
        java.util.Set<String> seenMovie = new java.util.HashSet<>();
        Matcher m = SEED_MOVIE.matcher(html);
        while (m.find() && movies.size() < 4) {
            if (seenMovie.add(m.group(2))) movies.add(new String[]{m.group(2), m.group(1)});
        }
        List<Ref> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int links = 0;
        for (String[] mv : movies) {
            if (links >= 8) break;
            String detail = SEED_SITE + "/movies/" + mv[0] + "/";
            String page;
            try { page = get(detail, hdr(SEED_SITE + "/", "")); }
            catch (Exception e) { continue; }
            Matcher lm = SEED_LINK.matcher(page);
            int perMovie = 0;
            while (lm.find() && links < 8 && perMovie < 3) {
                String href = lm.group(2).replace("&amp;", "&");
                // movie_title 含中文/emoji/空格，URI.create 会抛异常；只保留 redirect_to
                int amp = href.indexOf('&');
                if (amp > 0) href = href.substring(0, amp);
                String jump = SEED_SITE + href;
                String body;
                try { body = get(jump, hdr(detail, "")); }
                catch (Exception e) { continue; }
                perMovie++; links++;
                Matcher pm = PAN_URL.matcher(body);
                while (pm.find()) {
                    String url = pm.group();
                    Ref f = ref(url, mv[1] + " · " + lm.group(1));
                    if (f != null && seen.add(f.provider + ":" + f.shareId)) {
                        f.note = mv[1] + "｜" + lm.group(1) + "｜" + f.shareId;
                        f.site = "seed";
                        out.add(f);
                        break;
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt((Ref r) -> playable(r.provider) ? 0 : 1)
                .thenComparing(r -> r.provider == null ? "" : r.provider));
        return out;
    }

    // ------------------------------------------------------------ 盘她┃夸父

    private static List<Ref> kfzy(String kw) throws Exception {
        String cookie = PanConfig.get("kfzy_cookie");
        if (cookie.isBlank()) cookie = DEFAULT_KFZY_COOKIE;
        String url = KFZY_SITE + "/search.htm?keyword=" + enc(kw) + "&range=1";
        String html = get(url, hdr(KFZY_SITE + "/", cookie));
        List<String> threads = new ArrayList<>();
        java.util.Set<String> seenThread = new java.util.HashSet<>();
        Matcher tm = KFZY_THREAD.matcher(html);
        while (tm.find() && threads.size() < 8) {
            if (seenThread.add(tm.group(1))) threads.add(tm.group(1));
        }
        List<Ref> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String t : threads) {
            String page;
            try { page = get(KFZY_SITE + "/" + t, hdr(KFZY_SITE + "/", cookie)); }
            catch (Exception e) { continue; }
            String title = "夸父资源 " + t.replaceAll("\\D", "");
            Matcher titleM = KFZY_TITLE.matcher(page);
            if (titleM.find()) title = stripTags(titleM.group(1));
            Matcher pm = PAN_URL.matcher(page);
            while (pm.find()) {
                String share = pm.group();
                Ref f = ref(share, title);
                if (f != null && seen.add(f.provider + ":" + f.shareId)) {
                    f.note = title;
                    f.site = "YpanSo";
                    out.add(f);
                }
            }
            if (out.size() >= 30) break;
        }
        out.sort(Comparator.comparingInt((Ref r) -> playable(r.provider) ? 0 : 1));
        return out;
    }

    // ------------------------------------------------------------ 工具

    /** 把任意网盘分享 URL 归一化成 Ref（与 PanSouClient 的编码完全兼容）。 */
    static Ref ref(String url, String note) {
        if (url == null || !url.startsWith("http")) return null;
        Matcher sm = SHARE_ID.matcher(url);
        if (!sm.find()) return null;
        Ref f = new Ref();
        f.provider = providerOf(url);
        f.shareId = sm.group(1);
        Matcher pm = PASSCODE.matcher(url);
        f.passcode = pm.find() ? pm.group(1) : "";
        f.url = url;
        f.note = note == null ? "" : stripTags(note);
        f.source = hostOf(url);
        return f;
    }

    private static String providerOf(String url) {
        String h = hostOf(url);
        if (h.contains("quark")) return "quark";
        if (h.contains("drive.uc.cn") || h.contains("uc.cn")) return "uc";
        if (h.contains("baidu")) return "baidu";
        if (h.contains("115")) return "115";
        if (h.contains("alipan") || h.contains("aliyundrive")) return "aliyun";
        if (h.contains("xunlei")) return "xunlei";
        if (h.contains("189.cn")) return "tianyi";
        if (h.contains("123")) return "123";
        return h.isEmpty() ? "网盘" : h;
    }

    private static String hostOf(String url) {
        try {
            String s = url.substring(url.indexOf("://") + 3);
            int e = s.indexOf('/');
            return (e < 0 ? s : s.substring(0, e)).toLowerCase();
        } catch (Exception e) { return ""; }
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> hdr(String referer, String cookie) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null && !referer.isBlank()) h.put("Referer", referer);
        if (cookie != null && !cookie.isBlank()) h.put("Cookie", cookie);
        return h;
    }

    private static String get(String url, Map<String, String> h) throws Exception {
        PanSouClient.Resp r = PanSouClient.request(url, "GET", null, h);
        if (r.status >= 400) throw new IllegalStateException("HTTP " + r.status + " " + url);
        return r.body;
    }
}
