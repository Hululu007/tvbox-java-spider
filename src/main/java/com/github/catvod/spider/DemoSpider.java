package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.HtmlParser;
import com.github.catvod.utils.HttpUtils;
import com.github.catvod.utils.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 一个能被壳通过 "csp_DemoSpider" 加载的完整 Spider 实现。
 *
 * 数据源用公开爬虫练习站 quotes.toscrape.com（无需登录、结构稳定）。
 * 演示了五个标准方法如何串成一条链路：
 *   homeContent -> categoryContent -> detailContent -> playerContent
 *                  searchContent
 *
 * 真实站点换掉 BASE、pageUrl()、parseQuotes() 三处即可，骨架不用改。
 */
public class DemoSpider extends Spider {

    private static final String BASE = "https://quotes.toscrape.com";

    @Override
    public void init(Object context, String extend) {
        super.init(context, extend);
        // ext 来自 sites[].ext：可以是接口地址，也可以是一段 JSON 配置。
        // 真实项目里通常在这里解析出 apiBase / token / cookie。
    }

    private String pageUrl(String pg) {
        int p;
        try {
            p = Integer.parseInt(pg);
        } catch (NumberFormatException e) {
            p = 1;
        }
        return p <= 1 ? BASE + "/" : BASE + "/page/" + p + "/";
    }

    private List<HtmlParser.Quote> fetch(String pg) throws Exception {
        String url = pageUrl(pg);
        String html = HttpUtils.getWithRetry(url, 2);
        return HtmlParser.parseQuotes(html, url);
    }

    /** 把解析出的实体转成壳认识的 Vod 卡片 */
    private List<Result.Vod> toVods(String pg, List<HtmlParser.Quote> quotes) {
        List<Result.Vod> list = new ArrayList<>();
        for (int i = 0; i < quotes.size(); i++) {
            HtmlParser.Quote q = quotes.get(i);
            Result.Vod v = new Result.Vod();
            v.vod_id = pg + "#" + i;          // 页码+序号编码进 id，详情时反查
            v.vod_name = q.author + "：" + shorten(q.text, 40);
            v.vod_pic = "";
            v.vod_remarks = q.tags;
            v.type_name = "第 " + pg + " 页";
            list.add(v);
        }
        return list;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Result.Cls> classes = new ArrayList<>();
        classes.add(new Result.Cls("1", "第 1 页"));
        classes.add(new Result.Cls("2", "第 2 页"));
        classes.add(new Result.Cls("3", "第 3 页"));
        try {
            return Result.home(classes, toVods("1", fetch("1")));
        } catch (Exception e) {
            // 网络失败也要返回合法 JSON，否则壳会整页报错
            return Result.home(classes, Collections.<Result.Vod>emptyList());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        String page = (pg == null || pg.isEmpty()) ? tid : pg;
        int p = 1;
        try {
            p = Integer.parseInt(page);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Result.list(toVods(String.valueOf(p), fetch(String.valueOf(p))), p, 3);
        } catch (Exception e) {
            return Result.list(Collections.<Result.Vod>emptyList(), p, 3);
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        List<Result.Vod> list = new ArrayList<>();
        for (String id : ids) {
            String[] parts = id.split("#", 2);
            if (parts.length != 2) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                List<HtmlParser.Quote> quotes = fetch(parts[0]);
                if (index < 0 || index >= quotes.size()) {
                    continue;
                }
                HtmlParser.Quote q = quotes.get(index);
                Result.Vod v = new Result.Vod();
                v.vod_id = id;
                v.vod_name = q.author;
                v.vod_content = q.text + "  " + q.tags;
                v.vod_remarks = q.author;
                v.vod_play_from = "原页面";
                // 格式：集数名$链接，多集用 # 分隔
                v.vod_play_url = "打开详情$" + (q.aboutUrl.isEmpty() ? BASE + "/" : q.aboutUrl);
                list.add(v);
            } catch (Exception ignored) {
                // 单条失败不影响其它条目
            }
        }
        return Result.list(list, 1, 1);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (key == null || key.trim().isEmpty()) {
            return Result.list(Collections.<Result.Vod>emptyList(), 1, 1);
        }
        String kw = key.trim().toLowerCase(Locale.ROOT);
        List<Result.Vod> hits = new ArrayList<>();
        // quick 搜索只扫第 1 页，全量搜索扫 3 页 —— 这是 quickSearch 的常见实现方式
        int pages = quick ? 1 : 3;
        for (int p = 1; p <= pages; p++) {
            try {
                List<HtmlParser.Quote> quotes = fetch(String.valueOf(p));
                for (int i = 0; i < quotes.size(); i++) {
                    HtmlParser.Quote q = quotes.get(i);
                    if ((q.text + " " + q.author).toLowerCase(Locale.ROOT).contains(kw)) {
                        Result.Vod v = toVods(String.valueOf(p),
                                Collections.singletonList(q)).get(0);
                        v.vod_id = p + "#" + i;
                        hits.add(v);
                    }
                }
            } catch (Exception ignored) {
                break;
            }
        }
        return Result.list(hits, 1, 1);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 真实项目里这里做的是：拿网页/网盘链接 -> 调用解析接口 / 嗅探 m3u8 -> 返回直链。
        // 本例 id 已经是绝对 URL，所以直接 parse=0 交给播放器。
        String url = (id == null || id.isEmpty()) ? BASE + "/" : id;
        return Result.player(url, HttpUtils.UA);
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
