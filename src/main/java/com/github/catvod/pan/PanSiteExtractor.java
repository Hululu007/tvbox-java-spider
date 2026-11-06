package com.github.catvod.pan;

import com.github.catvod.utils.SafeFiles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第 6 课：模仿 download1-4，读取里面的「网盘配置中心 / 盘搜 / 网盘搜索类」接口。
 *
 * <p>产出三份文件：</p>
 * <ul>
 *   <li>output/pan-sites.json —— 全部网盘相关站点清单（含来源、类别、是否需要 spider jar）</li>
 *   <li>output/pan-subscription.json —— 全量订阅（本地主站 + 原配置所有网盘站点 + 解析接口）</li>
 *   <li>output/pan-local.json —— 纯本地版，不依赖任何 jar，点开即播</li>
 * </ul>
 */
public final class PanSiteExtractor {

    private static final String LOCAL = "http://127.0.0.1:8898";

    private static final String[] CONFIG_KW = {
            "配置中心", "配置┃", "我的┃网盘", "我的云盘", "panconfig", "mypan", "mydrive", "mdrive"
    };
    private static final String[] SEARCH_KW = {
            "盘搜", "盘搜索", "全盘搜", "盘她", "盘他", "搜搜", "趣盘", "综合",
            "pansou", "pansearch", "qupanso", "fishps", "zpan", "jpan", "ypanso",
            "bpanso", "kkss", "uuss", "soai", "quanpan"
    };
    private static final String[] RESOURCE_KW = {
            "夸克", "网盘", "115", "alist", "webdav", "panwebshare", "fishcloud",
            "wogg", "seedhub", "market", "panquark", "fakeai115", "cloud-drive"
    };

    private PanSiteExtractor() {}

    public static final class Site {
        public String source, category, key, name, api, extText, jar;
        public JsonObject raw;
    }

    private static String text(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }

    private static boolean hit(String blob, String[] kws) {
        for (String k : kws) if (blob.contains(k)) return true;
        return false;
    }

    private static String category(String blob) {
        if (hit(blob, CONFIG_KW)) return "网盘配置中心";
        if (hit(blob, SEARCH_KW)) return "盘搜/网盘搜索";
        return "网盘资源站";
    }

    /** 读取 4 份配置，返回去重后的网盘站点。 */
    public static List<Site> extract() throws Exception {
        List<Site> all = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Path p = Paths.get("config", "download" + i + ".json");
            if (!Files.exists(p)) continue;
            JsonObject cfg = JsonParser.parseString(
                    Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            String spider = text(cfg, "spider");
            for (JsonElement el : cfg.getAsJsonArray("sites")) {
                JsonObject s = el.getAsJsonObject();
                String key = text(s, "key"), name = text(s, "name"), api = text(s, "api");
                String ext = s.has("ext") ? s.get("ext").toString() : "";
                String blob = (key + " " + name + " " + api + " " + ext).toLowerCase();
                if (!hit(blob, CONFIG_KW) && !hit(blob, SEARCH_KW) && !hit(blob, RESOURCE_KW)) continue;
                Site site = new Site();
                site.source = "download" + i;
                site.category = category(blob);
                site.key = key; site.name = name; site.api = api;
                site.extText = ext.length() > 300 ? ext.substring(0, 300) + "..." : ext;
                site.jar = spider;
                site.raw = s;
                all.add(site);
            }
        }
        // 按 (name, api, ext) 去重，保留第一次出现并累计来源
        Map<String, Site> dedup = new LinkedHashMap<>();
        for (Site s : all) {
            String k = s.name + "|" + s.api + "|" + s.extText;
            Site old = dedup.get(k);
            if (old == null) { dedup.put(k, s); }
            else if (!old.source.contains(s.source)) {
                old.source += "," + s.source;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static JsonObject siteJson(Site s) {
        JsonObject o = s.raw.deepCopy();
        o.addProperty("name", "[" + s.source.replace("download", "D") + "·" + shortCat(s.category) + "] " + s.name);
        return o;
    }

    private static String shortCat(String c) {
        if (c.startsWith("网盘配置")) return "配置";
        if (c.startsWith("盘搜")) return "盘搜";
        return "资源";
    }

    /** 本地主站：type:1 苹果 CMS 兼容，播放地址指向流代理。 */
    private static JsonObject localSite() {
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

    /** 原版 JS 插件：由本地服务动态注入 Cookie 后托管。 */
    private static JsonObject jsSite() {
        JsonObject o = new JsonObject();
        o.addProperty("key", "QuanPanJs");
        o.addProperty("name", "🌸全盘搜｜JS原版(需壳JS引擎)");
        o.addProperty("type", 3);
        o.addProperty("api", LOCAL + "/quanpan_spider.js");
        o.addProperty("searchable", 1);
        o.addProperty("quickSearch", 1);
        o.addProperty("changeable", 0);
        return o;
    }

    private static JsonArray parses() {
        JsonArray a = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", "全盘搜直链");
        p.addProperty("type", 1);
        p.addProperty("url", LOCAL + "/api/parse?url=");
        a.add(p);
        return a;
    }

    public static JsonObject build(boolean withOriginal) throws Exception {
        List<Site> sites = extract();
        JsonObject cfg = new JsonObject();
        cfg.addProperty("spider", withOriginal ? download1Spider() : "");
        cfg.addProperty("wallpaper", "");
        cfg.addProperty("logo", "");
        cfg.addProperty("warningText",
                "网盘配置中心（填夸克 Cookie / 扫码）：" + LOCAL + "/  "
                        + "｜搜索后端 quanpan.xyz｜播放经本地流代理，无需壳支持 Cookie");

        JsonArray arr = new JsonArray();
        arr.add(localSite());
        arr.add(jsSite());
        if (withOriginal) {
            for (Site s : sites) arr.add(siteJson(s));
        }
        cfg.add("sites", arr);
        cfg.add("parses", parses());
        cfg.add("lives", new JsonArray());
        cfg.add("rules", new JsonArray());
        cfg.add("doh", new JsonArray());
        cfg.add("ads", new JsonArray());
        cfg.add("flags", new JsonArray());
        return cfg;
    }

    private static String download1Spider() {
        try {
            return text(JsonParser.parseString(Files.readString(
                    Paths.get("config", "download1.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject(), "spider");
        } catch (Exception e) { return ""; }
    }

    /** 生成清单 + 两份订阅配置，全部走第 4 课的原子写入。 */
    public static void writeAll() throws Exception {
        List<Site> sites = extract();
        Map<String, Integer> byCat = new LinkedHashMap<>();
        Map<String, Integer> bySrc = new LinkedHashMap<>();
        JsonArray list = new JsonArray();
        Set<String> apis = new LinkedHashSet<>();
        for (Site s : sites) {
            JsonObject o = new JsonObject();
            o.addProperty("source", s.source);
            o.addProperty("category", s.category);
            o.addProperty("key", s.key);
            o.addProperty("name", s.name);
            o.addProperty("api", s.api);
            o.addProperty("ext", s.extText);
            o.addProperty("needsJar", s.api.startsWith("csp_"));
            o.addProperty("spider", s.jar);
            list.add(o);
            byCat.merge(s.category, 1, Integer::sum);
            for (String src : s.source.split(",")) bySrc.merge(src, 1, Integer::sum);
            if (s.api.startsWith("csp_")) apis.add(s.api.substring(4));
        }
        JsonObject report = new JsonObject();
        report.addProperty("from", "config/download1.json ~ download4.json");
        report.addProperty("total", sites.size());
        report.add("byCategory", new GsonBuilder().create().toJsonTree(byCat));
        report.add("bySource", new GsonBuilder().create().toJsonTree(bySrc));
        report.addProperty("distinctCspClasses", apis.size());
        report.add("sites", list);

        Path out = Paths.get("output");
        SafeFiles.writeJson(out.resolve("pan-sites.json"), report);
        SafeFiles.writeJson(out.resolve("pan-subscription.json"), build(true));
        SafeFiles.writeJson(out.resolve("pan-local.json"), build(false));
        System.out.println("  网盘站点 " + sites.size() + " 个 | 分类 " + byCat
                + " | 来源 " + bySrc + " | csp 类 " + apis.size());
        System.out.println("  已生成 output/pan-sites.json, pan-subscription.json, pan-local.json");
    }

    public static void main(String[] args) throws Exception {
        writeAll();
    }
}
