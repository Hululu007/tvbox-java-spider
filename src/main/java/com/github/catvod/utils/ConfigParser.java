package com.github.catvod.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取 TVBox 订阅配置，把里面和爬虫有关的字段拆出来。
 * 这一步纯本地 JSON 解析，不发起任何网络请求 —— 学爬虫先学会读数据源配置。
 *
 * 关键映射：
 *   spider             -> 自定义爬虫 jar 的地址 + md5
 *   sites[].type = 3   -> 走 jar / js 扩展，其余 type 走壳内置采集
 *   sites[].api = csp_X-> 从 jar 里加载 com.github.catvod.spider.X
 *   sites[].ext        -> 传给 Spider.init 的第二个参数
 *   parses[].type = 1  -> json 解析接口（把网盘/网页链接转成直链）
 *   rules              -> 嗅探规则：hosts 匹配域名，regex 匹配 URL，script 自动点击
 *   headers / lives.ua -> 请求头伪装
 *   ads                -> 广告域名黑名单
 */
public final class ConfigParser {

    public static final class Summary {
        public String spiderField = "";
        public int sites, parses, rules, lives, doh, ads, headers;
        public int cspSites;
        public final List<String> cspApis = new ArrayList<>();
        public final List<String> parseNames = new ArrayList<>();
        public final List<String> ruleNames = new ArrayList<>();

        public String report() {
            StringBuilder b = new StringBuilder();
            b.append("spider   : ").append(spiderField).append('\n');
            b.append("sites    : ").append(sites)
             .append(" 个（type=3 且 api 以 csp_ 开头：").append(cspSites).append(" 个）\n");
            b.append("csp 类名 : ").append(preview(cspApis, 6)).append('\n');
            b.append("parses   : ").append(parses).append(" 个  ").append(parseNames).append('\n');
            b.append("rules    : ").append(rules).append(" 组  ").append(ruleNames).append('\n');
            b.append("lives    : ").append(lives)
             .append(" | doh: ").append(doh)
             .append(" | ads: ").append(ads)
             .append(" | headers: ").append(headers).append('\n');
            return b.toString();
        }

        private static String preview(List<String> list, int n) {
            List<String> sub = list.subList(0, Math.min(n, list.size()));
            return sub + (list.size() > n ? " ... 共 " + list.size() + " 个" : "");
        }
    }

    private ConfigParser() {}

    public static Summary parse(String jsonText) {
        String text = jsonText;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);            // 去掉 UTF-8 BOM
        }
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Summary s = new Summary();

        s.spiderField = str(root, "spider");
        s.sites = size(root, "sites");
        s.parses = size(root, "parses");
        s.rules = size(root, "rules");
        s.lives = size(root, "lives");
        s.doh = size(root, "doh");
        s.ads = size(root, "ads");
        s.headers = size(root, "headers");

        JsonArray sites = arr(root, "sites");
        for (JsonElement e : sites) {
            JsonObject site = e.getAsJsonObject();
            String api = str(site, "api");
            if (site.has("type") && site.get("type").getAsInt() == 3 && api.startsWith("csp_")) {
                s.cspSites++;
                s.cspApis.add(api);
            }
        }

        for (JsonElement e : arr(root, "parses")) {
            s.parseNames.add(str(e.getAsJsonObject(), "name"));
        }
        for (JsonElement e : arr(root, "rules")) {
            s.ruleNames.add(str(e.getAsJsonObject(), "name"));
        }
        return s;
    }

    /** "csp_NewDouBanGuard" -> "com.github.catvod.spider.NewDouBanGuard" */
    public static String apiToClassName(String api) {
        if (api == null || !api.startsWith("csp_")) {
            throw new IllegalArgumentException("不是 csp_ 开头的 api: " + api);
        }
        return "com.github.catvod.spider." + api.substring(4);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static int size(JsonObject o, String key) {
        return arr(o, key).size();
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
