package com.github.catvod.pan;

import com.github.catvod.utils.SafeFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 7 课：复刻 download4.json 里的 5 个网盘站点。
 *
 * <p>原始条目都是 type:3 的 csp_*Guard dex 插件，必须加载 spider jar。
 * 这里把它们逐一还原成纯本地 type:1（苹果 CMS）站点，播放地址统一指向
 * 8898 服务已有的 /api/stream 流代理，因此「搜完点开就能播」，
 * 不依赖任何 jar，夸克 Cookie 仍由配置中心填写 / 扫码。</p>
 *
 * <pre>
 *  🗂我的云盘┃配置  csp_MyDriveGuard  ext.Cloud-drive=tvfan/Cloud-drive.txt  → 本地配置中心 + 自建分享库
 *  💡聚剧┃四盘     csp_SeedhubGuard   加密 ext → seedog.cc /s/&lt;kw&gt;/ → /movies/&lt;id&gt;/ → /link_start/
 *  🎈盘搜┃四盘     csp_S_zpsGuard     siteUrl=107.173.211.148  → PanSou 免登实例
 *  🦋易搜┃四盘     csp_S_zpsGuard     siteUrl=so.252035.xyz    → PanSou 免登实例
 *  🐌盘她┃夸父     csp_YpanSoGuard    ext.Cloud-drive → kfzy.net search.htm → thread-&lt;id&gt;.htm
 * </pre>
 */
public final class ReplicaSites {

    public static final String LOCAL = "http://127.0.0.1:8898";
    public static final Path CLOUD = Paths.get("tvfan", "Cloud-drive.txt");

    public static final class Def {
        public final String key, name, originApi, originExt, backend, kind, localApi;

        Def(String key, String name, String originApi, String originExt,
            String backend, String kind, String localApi) {
            this.key = key; this.name = name; this.originApi = originApi;
            this.originExt = originExt; this.backend = backend;
            this.kind = kind; this.localApi = localApi;
        }
    }

    /** 顺序与 download4.json 中一致。 */
    public static final List<Def> SITES = List.of(
            new Def("MDrive", "🗂我的云盘┃配置", "csp_MyDriveGuard",
                    "{\"Cloud-drive\":\"tvfan/Cloud-drive.txt\"}",
                    "本地配置中心 + tvfan/Cloud-drive.txt 自建分享库",
                    "网盘配置中心", LOCAL + "/api/cms/MDrive/"),
            new Def("seed", "💡聚剧┃四盘", "csp_SeedhubGuard",
                    "\"5++kwLhNYm9UrO9wh7Dl7eKamTee4s/5\"（加密 ext，原样保留）",
                    "seedog.cc /s/<kw>/ → /movies/<id>/ → /link_start/",
                    "四盘资源站", LOCAL + "/api/cms/seed/"),
            new Def("ZPan", "🎈盘搜┃四盘", "csp_S_zpsGuard",
                    "{\"siteUrl\":\"http://107.173.211.148/\"}",
                    "PanSou 免登实例 107.173.211.148（8 盘）",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/ZPan/"),
            new Def("JPan", "🦋易搜┃四盘", "csp_S_zpsGuard",
                    "{\"siteUrl\":\"https://so.252035.xyz/\"}",
                    "PanSou 免登实例 so.252035.xyz（10 盘）",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/JPan/"),
            new Def("YpanSo", "🐌盘她┃夸父", "csp_YpanSoGuard",
                    "{\"Cloud-drive\":\"tvfan/Cloud-drive.txt\"}",
                    "kfzy.net search.htm → thread-<id>.htm（带论坛 Cookie）",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/YpanSo/"),
            // ---- download3.json 第二批：综合盘搜 / 4K 盘库 ----
            new Def("SoAiHaiYin", "🎠盘搜┃综合🎠", "csp_SoAiHaiYinGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "pansou.top /api/other/web_search SSE 四线路 + /api/other/save_url 解密",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/SoAiHaiYin/"),
            new Def("SoAiPanSoo", "🎠九七┃综合🎠", "csp_SoAiPanSooGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "pansou.top is_type=3（UC 免登线路）+ PanSou 免登实例 so.252035.xyz",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/SoAiPanSoo/"),
            new Def("SoAiAiPanSo", "🎠爱盘┃综合🎠", "csp_SoAiAiPanSoGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "aipanso.com AES-CBC 反爬 Cookie → /search?k= 列表 + 免登实例取链",
                    "盘搜/网盘搜索", LOCAL + "/api/cms/SoAiAiPanSo/"),
            new Def("AiNewPianKu", "💓盘库┃4K💓", "csp_AiNewPianKuGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "tvfan/Cloud-drive.txt 自建 4K 库 + 夸父 4K 帖子 + 盘搜 4K 条目",
                    "4K 网盘资源站", LOCAL + "/api/cms/AiNewPianKu/"),
            // ---- 截图第三批：download3.json 索引 4–20 的 16 个「💓…┃4K💓」资源站 ----
            new Def("HuaJuan4K", "💓花卷┃4K💓‍", "csp_AiNewHuaJuanGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://www.hjzhencai.top",
                    "hjzhencai.top 苹果 CMS 搜索 → 详情页夸克/UC 分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/HuaJuan4K/"),
            new Def("Wogg4K", "💓‍玩偶┃4K💓‍", "csp_AiNewWoggGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://www.wogg.net",
                    "wogg.net /vodsearch/ → /voddetail/ 内嵌多盘分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/Wogg4K/"),
            new Def("GuanYing4K", "💓‍观影┃4K💓‍", "csp_AiNewGuanYingGuard",
                    "无 ext（download3 加固 dex）；同源 download1 中文域名系列",
                    "中文域名站 PoW 浏览器验证，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/GuanYing4K/"),
            new Def("Qiwei4K", "💓‍七味┃4K💓‍", "csp_AiQwMkvGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://www.qwmkv.com",
                    "qwmkv.com /vs/ 搜索被 PoW 验证拦截，走免登盘搜兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/Qiwei4K/"),
            new Def("HuBan4K", "💓虎斑┃4K💓", "csp_AiNewHuBanGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=http://103.40.14.172:16968",
                    "虎斑 4K 苹果 CMS 搜索 → 详情页 UC/夸克/百度分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/HuBan4K/"),
            new Def("MuOu4K", "💓木偶┃4K💓", "csp_AiNewMuOuGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://www.muoua.top",
                    "muoua.top 苹果 CMS 搜索 → 详情页分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/MuOu4K/"),
            new Def("DuoDuo4K", "💓多多┃4K💓", "csp_AiNewDuoDuoGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://tv.yydsys.top",
                    "yydsys /index.php/vod/search/wd/<kw>.html → 详情页分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/DuoDuo4K/"),
            new Def("KuaiYing4K", "💓快影┃4K💓", "csp_AiNewKuaiYingGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "加固壳未暴露后端，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/KuaiYing4K/"),
            new Def("LaBi4K", "💓蜡笔┃4K💓", "csp_AiNewLaBiGuard",
                    "无 ext（download3 加固 dex）；同源站点 http://tvpanpan.site",
                    "tvpanpan.site 苹果 CMS 搜索 → 详情页分享",
                    "4K 网盘资源站", LOCAL + "/api/cms/LaBi4K/"),
            new Def("ShanDian4K", "💓闪电┃4K💓", "csp_AiNewShanDianGuard",
                    "无 ext（download3 加固 dex）；shandian.blog 已 522 下线",
                    "源站下线，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/ShanDian4K/"),
            new Def("JuTou4K", "💓剧透┃4K💓", "csp_AiNewJuTouGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "加固壳未暴露后端，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/JuTou4K/"),
            new Def("Libvio4K", "💓立播┃4K💓", "csp_AiNewLibvioGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://www.libvio.cam",
                    "libvio.cam /search/-------------.html 搜索 → 详情页",
                    "4K 网盘资源站", LOCAL + "/api/cms/Libvio4K/"),
            new Def("ZhiNan4K", "💓‍原盘┃4K💓‍", "csp_AiNewZhiNan4KGuard",
                    "无 ext（download3 加固 dex）；同源 download1「指南｜4K自扫」ext.site=https://4kzn.cc",
                    "4kzn.cc WordPress 站，搜索未开放，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/ZhiNan4K/"),
            new Def("WoNiu4K", "💓‍蜗牛┃4K💓‍", "csp_AiNewWoNiu4KGuard",
                    "无 ext（download3 加固 dex）；同源 download1 ext.site=https://wn4k.com",
                    "wn4k.com /vodsearch/-------------/ → /voddetail/<id>/",
                    "4K 网盘资源站", LOCAL + "/api/cms/WoNiu4K/"),
            new Def("PanMi4K", "💓盘迷┃4K💓", "csp_AiPan1MeGuard",
                    "无 ext（download3 加固 dex 内硬编码）",
                    "加固壳未暴露后端，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/PanMi4K/"),
            new Def("YiDong4K", "💓逸动┃4K💓", "csp_AiNewYiDong4KGuard",
                    "无 ext（download3 加固 dex）；同源 download1「移动｜4K」csp_Yidong4K",
                    "无 ext 站点，走免登盘搜 + 活跃 UC 兜底",
                    "4K 网盘资源站", LOCAL + "/api/cms/YiDong4K/")
    );

    /** 第二批（download3.json）的 4 个 key，用于独立订阅与对照表。 */
    public static final List<String> BATCH2_KEYS = List.of(
            "SoAiHaiYin", "SoAiPanSoo", "SoAiAiPanSo", "AiNewPianKu");

    /** 截图第三批：17 个「💓…┃4K💓」站点（盘库复用第二批实现）。 */
    public static final List<String> BATCH3_KEYS = List.of(
            "HuaJuan4K", "Wogg4K", "GuanYing4K", "Qiwei4K", "AiNewPianKu",
            "HuBan4K", "MuOu4K", "DuoDuo4K", "KuaiYing4K", "LaBi4K",
            "ShanDian4K", "JuTou4K", "Libvio4K", "ZhiNan4K", "WoNiu4K",
            "PanMi4K", "YiDong4K");

    private ReplicaSites() {}

    public static Def byKey(String key) {
        for (Def d : SITES) if (d.key.equalsIgnoreCase(key == null ? "" : key)) return d;
        return null;
    }

    /** CMS 站点条目：type:1，无 jar 依赖。 */
    private static JsonObject site(Def d) {
        JsonObject o = new JsonObject();
        o.addProperty("key", d.key);
        o.addProperty("name", d.name);
        o.addProperty("type", 1);
        o.addProperty("api", d.localApi);
        o.addProperty("searchable", 1);
        o.addProperty("quickSearch", 1);
        o.addProperty("filterable", 0);
        o.addProperty("changeable", 0);
        JsonObject ext = new JsonObject();
        ext.addProperty("origin_api", d.originApi);
        ext.addProperty("backend", d.backend);
        ext.addProperty("play_proxy", LOCAL + "/api/stream?ref=");
        o.add("ext", ext);
        return o;
    }

    /** 保留原主站 🌸全盘搜｜夸克4K（UC/夸克直链解析的实现来源）。 */
    private static JsonObject mainSite() {
        JsonObject o = new JsonObject();
        o.addProperty("key", "QuanPan4K");
        o.addProperty("name", "🌸全盘搜｜夸克4K");
        o.addProperty("type", 1);
        o.addProperty("api", LOCAL + "/api/cms/pan/");
        o.addProperty("searchable", 1);
        o.addProperty("quickSearch", 1);
        o.addProperty("filterable", 0);
        o.addProperty("changeable", 0);
        return o;
    }

    /** 可直接订阅：全部复刻站点 + 主站，全部 type:1、spider 留空。 */
    public static JsonObject subscription() { return subscription(SITES); }

    /** 第二批（download3.json）4 个综合盘搜 / 4K 站点的独立订阅。 */
    public static JsonObject subscriptionBatch2() { return subscription(batch2()); }

    /** 截图第三批 17 个 4K 站点的独立订阅。 */
    public static JsonObject subscriptionBatch3() { return subscription(batch3()); }

    public static List<Def> batch2() { return subset(BATCH2_KEYS); }

    public static List<Def> batch3() { return subset(BATCH3_KEYS); }

    private static List<Def> subset(List<String> keys) {
        List<Def> out = new ArrayList<>();
        for (String k : keys) {
            Def d = byKey(k);
            if (d != null) out.add(d);
        }
        return out;
    }

    private static JsonObject subscription(List<Def> defs) {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("spider", "");
        cfg.addProperty("wallpaper", "");
        cfg.addProperty("logo", "");
        cfg.addProperty("warningText",
                defs.size() + " 站点复刻版（无需 spider jar）｜网盘配置中心/填夸克 Cookie/扫码："
                        + LOCAL + "/ ｜播放经本地流代理自动补鉴权头");
        JsonArray arr = new JsonArray();
        for (Def d : defs) arr.add(site(d));
        arr.add(mainSite());
        cfg.add("sites", arr);
        JsonArray parse = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", "全盘搜直链");
        p.addProperty("type", 1);
        p.addProperty("url", LOCAL + "/api/parse?url=");
        parse.add(p);
        cfg.add("parses", parse);
        cfg.add("lives", new JsonArray());
        cfg.add("rules", new JsonArray());
        cfg.add("doh", new JsonArray());
        cfg.add("ads", new JsonArray());
        cfg.add("flags", new JsonArray());
        return cfg;
    }

    /** 复刻对照表：原 dex 契约 → 本地实现。 */
    public static JsonObject comparison() {
        return comparison(SITES,
                "config/download4.json（与 xiuxiu-20261106.json 同版）+ config/download3.json 第二批",
                "download4: https://xiuxiu-pro-new.meitudata.com/posters/8057468f8f30ba79c6dbbeb7fc1a052e.jpg;md5;b3fb6f369726e9c688dd5a7ad017d0dd"
                        + " ｜ download3: http://oss4liview.moji.com/thd_file/2026/11/04/690e7f13e63718e6ea88add0c59d4289.jpg;md5;86cb2cdcd99e29334004b80e48007e9a");
    }

    /** 第二批 4 站点的原契约对照表（download3 加固 dex）。 */
    public static JsonObject comparisonBatch2() {
        return comparison(batch2(),
                "config/download3.json（与 wex-20261106.json 同版）",
                "http://oss4liview.moji.com/thd_file/2026/11/04/690e7f13e63718e6ea88add0c59d4289.jpg;md5;86cb2cdcd99e29334004b80e48007e9a"
                        + "（wexguard 加固壳，classes.dex 仅含类名，实现加密在 assets/wexshinidie.guard）");
    }

    /** 截图第三批 17 站点的原契约对照表（download3 加固 dex + download1 同源 ext.site）。 */
    public static JsonObject comparisonBatch3() {
        return comparison(batch3(),
                "config/download3.json 索引 4–20（与 wex-20261106.json 同版）"
                        + " + config/download1.json 同名 4K 站明文 ext.site",
                "http://oss4liview.moji.com/thd_file/2026/11/04/690e7f13e63718e6ea88add0c59d4289.jpg;md5;86cb2cdcd99e29334004b80e48007e9a"
                        + "（wexguard 加固壳；download1 同源插件 md5 c78599dab489dae65c893e121df0de2f 已校验）");
    }

    private static JsonObject comparison(List<Def> defs, String source, String jar) {
        JsonObject root = new JsonObject();
        root.addProperty("source", source);
        root.addProperty("originSpiderJar", jar);
        root.addProperty("replicaType", "type:1 苹果 CMS 兼容，spider 留空，零 jar 依赖");
        root.addProperty("playProxy", LOCAL + "/api/stream?ref=");
        JsonArray arr = new JsonArray();
        for (Def d : defs) {
            JsonObject o = new JsonObject();
            o.addProperty("key", d.key);
            o.addProperty("name", d.name);
            o.addProperty("kind", d.kind);
            o.addProperty("originApi", d.originApi);
            o.addProperty("originExt", d.originExt);
            o.addProperty("replicaApi", d.localApi);
            o.addProperty("backend", d.backend);
            arr.add(o);
        }
        root.add("sites", arr);
        return root;
    }

    /**
     * 生成「我的云盘┃配置」真正读取的子规则文件。
     *
     * <p>原 dex 插件按主配置 URL 的目录解析相对路径 tvfan/Cloud-drive.txt；
     * 这里同样放到 tvfan/ 下，并由 8898 服务以 /tvfan/Cloud-drive.txt 提供
     * HTTP 读取，壳与本地服务读的是同一份。Cookie 只写状态，不落明文。</p>
     */
    public static String cloudDriveText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ==========================================================\n");
        sb.append("# 🗂我的云盘┃配置 · 复刻版子规则文件\n");
        sb.append("# 原契约: type:3 / csp_MyDriveGuard / ext.Cloud-drive=tvfan/Cloud-drive.txt\n");
        sb.append("# 壳按主配置 URL 目录解析；本服务同时以 /tvfan/Cloud-drive.txt 提供 HTTP 读取\n");
        sb.append("# ==========================================================\n");
        sb.append("config_center=").append(LOCAL).append("/\n");
        sb.append("qr_api=").append(LOCAL).append("/api/qr\n");
        sb.append("subscription=").append(LOCAL).append("/api/replica/config\n");
        sb.append("cookie_api=").append(LOCAL).append("/api/cookies\n");
        sb.append("quark_cookie=")
                .append(PanConfig.get("quark_cookie").isBlank() ? "未配置" : "已配置").append("\n");
        sb.append("kfzy_cookie=")
                .append(PanConfig.get("kfzy_cookie").isBlank() ? "使用内置会话" : "已配置").append("\n");
        sb.append("# ---- 自建分享库：share=<网盘分享URL>|<备注> ----\n");
        sb.append("# 每行一条，点开即播（夸克需在配置中心填 Cookie，UC 免登）\n");
        sb.append("share=https://pan.quark.cn/s/ebc3c234fae7|凡人修仙传 更198集 4K 国语中字\n");
        sb.append("share=https://pan.quark.cn/s/aa7319036536|凡人修仙传 2025 4K SDR 高码率 纯净版\n");
        sb.append("share=https://drive.uc.cn/s/a2b56c303b4b4|凡人修仙传 更198集 4K H265 HDR真彩\n");
        sb.append("share=https://drive.uc.cn/s/8fd1c7efa87d4?public=1|凡人修仙传 幕兰之战 年番4 198集\n");
        sb.append("share=https://drive.uc.cn/s/aaeb51ce89554|凡人修仙传之瀚海迷踪 4K 活跃UC 免登可播\n");
        return sb.toString();
    }

    /** 读取自建分享库，返回 {url, note} 数组。 */
    public static List<String[]> cloudShares() {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(CLOUD)) return out;
        try {
            for (String line : Files.readAllLines(CLOUD, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.startsWith("share=")) continue;
                String[] kv = s.substring(6).split("\\|", 2);
                if (kv.length > 0 && kv[0].startsWith("http")) {
                    out.add(new String[]{kv[0], kv.length > 1 ? kv[1] : ""});
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 启动时落盘：订阅、对照表、子规则文件，全部走第 4 课的原子写。 */
    public static void writeAll() throws Exception {
        Path out = Paths.get("output");
        SafeFiles.writeJson(out.resolve("replica-subscription.json"), subscription());
        SafeFiles.writeJson(out.resolve("replica-sites.json"), comparison());
        SafeFiles.writeJson(out.resolve("replica2-subscription.json"), subscriptionBatch2());
        SafeFiles.writeJson(out.resolve("replica2-sites.json"), comparisonBatch2());
        SafeFiles.writeJson(out.resolve("replica3-subscription.json"), subscriptionBatch3());
        SafeFiles.writeJson(out.resolve("replica3-sites.json"), comparisonBatch3());
        SafeFiles.writeText(CLOUD, cloudDriveText());
        System.out.println("  复刻站点 " + SITES.size() + " 个（第二批 " + BATCH2_KEYS.size()
                + "，第三批 " + BATCH3_KEYS.size() + "）| 已生成 output/replica*-subscription.json、"
                + "output/replica*-sites.json、" + CLOUD);
    }

    public static void main(String[] args) throws Exception {
        writeAll();
    }
}
