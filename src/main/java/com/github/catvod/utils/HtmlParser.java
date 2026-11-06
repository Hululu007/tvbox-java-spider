package com.github.catvod.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析层：Jsoup + CSS 选择器，把 HTML 变成结构化数据。
 * 目标站点用公开爬虫练习站 quotes.toscrape.com，结构稳定、无需登录。
 *
 * 三个关键习惯：
 *   - Jsoup.parse(html, baseUrl) 传入 baseUrl，才能用 abs:href 拿绝对地址
 *   - selectFirst 可能返回 null，一定判空，否则站点改版就整页崩溃
 *   - 解析逻辑只做"HTML -> 数据"，不发请求，方便单元测试
 */
public final class HtmlParser {

    public static final class Quote {
        public final String text;
        public final String author;
        public final String aboutUrl;
        public final String tags;

        public Quote(String text, String author, String aboutUrl, String tags) {
            this.text = text;
            this.author = author;
            this.aboutUrl = aboutUrl;
            this.tags = tags;
        }

        @Override
        public String toString() {
            return "[" + author + "] " + text;
        }
    }

    private HtmlParser() {}

    public static List<Quote> parseQuotes(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        List<Quote> out = new ArrayList<>();
        for (Element q : doc.select("div.quote")) {
            Element textEl = q.selectFirst("span.text");
            Element authorEl = q.selectFirst("small.author");
            Element aboutEl = q.selectFirst("span a[href]");
            Element tagsEl = q.selectFirst("div.tags");
            out.add(new Quote(
                    textEl == null ? "" : textEl.text(),
                    authorEl == null ? "" : authorEl.text(),
                    aboutEl == null ? "" : aboutEl.attr("abs:href"),
                    tagsEl == null ? "" : tagsEl.text()));
        }
        return out;
    }

    public static String title(String html, String baseUrl) {
        return Jsoup.parse(html, baseUrl).title();
    }
}
