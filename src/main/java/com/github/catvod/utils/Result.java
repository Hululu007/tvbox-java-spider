package com.github.catvod.utils;

import com.google.gson.Gson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出层：TVBox 壳与 Spider 之间约定的是 JSON 字符串，不是 Java 对象。
 * 所有方法统一返回 String，靠固定字段名传数据 —— 这也是插件能跨版本工作的原因。
 *
 * 常用结构：
 *   homeContent   -> {"class":[...], "list":[...], "filters":{...}}
 *   category/search/detail -> {"list":[...], "page":1, "pagecount":1, "total":n}
 *   playerContent -> {"parse":0, "playUrl":"", "url":"直链", "header":{...}}
 *     parse=0 表示已是直链，壳不再走 parses 里的解析接口；
 *     parse=1 才会把 url 交给 "parses" 中的解析服务二次解析。
 */
public final class Result {

    private static final Gson GSON = new Gson();

    public static final class Cls {
        public String type_id;
        public String type_name;

        public Cls(String id, String name) {
            this.type_id = id;
            this.type_name = name;
        }
    }

    public static final class Vod {
        public String vod_id;
        public String vod_name;
        public String vod_pic;
        public String vod_remarks;
        public String vod_content;
        public String type_name;
        /** 播放源名称，多个用 $$$ 分隔 */
        public String vod_play_from;
        /** 每集的 "名称$链接"，多集用 # 分隔，多源用 $$$ 分隔 */
        public String vod_play_url;
    }

    private Result() {}

    public static String stringify(Object obj) {
        return GSON.toJson(obj);
    }

    public static String home(List<Cls> classes, List<Vod> vods) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", classes);
        m.put("list", vods);
        m.put("filters", Collections.emptyMap());
        return stringify(m);
    }

    public static String list(List<Vod> vods, int page, int pageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", vods);
        m.put("page", page);
        m.put("pagecount", pageCount);
        m.put("limit", vods.size());
        m.put("total", vods.size());
        return stringify(m);
    }

    public static String player(String url, String userAgent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parse", 0);
        m.put("playUrl", "");
        m.put("url", url);
        Map<String, String> h = new LinkedHashMap<>();
        if (userAgent != null && !userAgent.isEmpty()) {
            h.put("User-Agent", userAgent);
        }
        m.put("header", h);
        return stringify(m);
    }
}
