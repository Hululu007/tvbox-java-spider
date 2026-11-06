package com.github.catvod;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.ConcurrentCrawler;
import com.github.catvod.utils.ConfigParser;
import com.github.catvod.utils.HtmlParser;
import com.github.catvod.utils.Md5;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * 运行入口：把「配置 -> 加载 -> 解析 -> 调度 -> 输出」完整跑一遍。
 *
 * 编译运行（工程根目录）：
 *   mvn compile exec:java
 * 或在无 Maven 环境下用目录里的 lib/*.jar：
 *   javac -encoding UTF-8 -cp "lib/*" -d out $(find src/main/java -name "*.java")
 *   java  -cp "out;lib/*" com.github.catvod.Runner
 */
public class Runner {

    private static final String OFFLINE_HTML =
            "<html><head><title>Quotes</title></head><body>"
            + "<div class=\"quote\"><span class=\"text\">“Talk is cheap. Show me the code.”</span>"
            + "<small class=\"author\">Linus Torvalds</small>"
            + "<span><a href=\"/author/Linus-Torvalds\">(about)</a></span>"
            + "<div class=\"tags\">Tags: code</div></div>"
            + "<div class=\"quote\"><span class=\"text\">“程序必须为人阅读而写。”</span>"
            + "<small class=\"author\">Abelson</small>"
            + "<span><a href=\"/author/Abelson\">(about)</a></span>"
            + "<div class=\"tags\">Tags: books</div></div>"
            + "</body></html>";

    public static void main(String[] args) throws Exception {
        System.out.println("===== Java 爬虫教学 · 全流程演示 =====\n");

        String json = loadConfig();

        System.out.println("【1】订阅配置结构解析（纯本地，不发请求）");
        ConfigParser.Summary s = ConfigParser.parse(json);
        System.out.println(s.report());

        System.out.println("【2】spider 字段拆解 + MD5 完整性校验");
        Md5.SpiderSpec spec = Md5.parseSpider(s.spiderField);
        System.out.println(spec);
        byte[] fakeJar = "custom_spider.jar".getBytes(StandardCharsets.UTF_8);
        System.out.println("样本内容 md5 : " + Md5.hex(fakeJar));
        System.out.println("与配置摘要匹配: " + Md5.verify(fakeJar, spec.expectedHash)
                + "  <- 不一致时壳会丢弃缓存、重新下载");

        System.out.println("\n【3】csp_ 类名 -> 反射加载（壳加载 jar 的核心动作）");
        List<String> apis = new ArrayList<>(s.cspApis);
        if (!apis.contains("csp_DemoSpider")) {
            apis.add(0, "csp_DemoSpider");
        }
        Spider spider = null;
        int shown = Math.min(4, apis.size());
        for (int i = 0; i < shown; i++) {
            String api = apis.get(i);
            String className = ConfigParser.apiToClassName(api);
            try {
                Class<?> c = Class.forName(className);
                Spider instance = (Spider) c.getDeclaredConstructor().newInstance();
                instance.setSiteKey(api);
                System.out.println("  " + api + " -> " + className + "  [加载成功]");
                if (spider == null) {
                    spider = instance;
                }
            } catch (ClassNotFoundException e) {
                System.out.println("  " + api + " -> " + className + "  [本工程未内置，跳过]");
            }
        }

        System.out.println("\n【4】Jsoup 解析演示（离线 HTML，验证 CSS 选择器与绝对 URL）");
        List<HtmlParser.Quote> quotes = HtmlParser.parseQuotes(
                OFFLINE_HTML, "https://quotes.toscrape.com/");
        System.out.println("  提取到 " + quotes.size() + " 条：");
        for (HtmlParser.Quote q : quotes) {
            System.out.println("    " + q + " | abs:href = " + q.aboutUrl);
        }

        System.out.println("\n【5】Spider 生命周期调用（网络可用时为真实数据）");
        if (spider == null) {
            System.out.println("  未找到可加载的 Spider，跳过");
        } else {
            spider.init(null, "{}");
            System.out.println("  homeContent     -> " + cut(spider.homeContent(true)));
            System.out.println("  categoryContent -> " + cut(spider.categoryContent(
                    "1", "1", false, new HashMap<String, String>())));
            System.out.println("  searchContent   -> " + cut(spider.searchContent("life", true)));
            System.out.println("  detailContent   -> " + cut(spider.detailContent(
                    Arrays.asList("1#0"))));
            System.out.println("  playerContent   -> " + cut(spider.playerContent(
                    "原页面", "https://quotes.toscrape.com/author/Linus-Torvalds",
                    new ArrayList<String>())));
            spider.destroy();
        }

        System.out.println("\n【6】并发调度：线程池 + 限速 + 去重 + 重试");
        List<String> urls = Arrays.asList(
                "https://quotes.toscrape.com/",
                "https://quotes.toscrape.com/page/2/",
                "https://quotes.toscrape.com/page/3/",
                "https://quotes.toscrape.com/");   // 故意重复，验证去重
        try (ConcurrentCrawler crawler = new ConcurrentCrawler(3, 150)) {
            List<ConcurrentCrawler.Page> pages = crawler.fetch(urls);
            System.out.println("  返回 " + pages.size() + " 页（输入 4 条，去重后应为 3 条）：");
            for (ConcurrentCrawler.Page p : pages) {
                System.out.println("    " + p.url + " -> " + p.quotes.size() + " 条记录");
            }
            if (pages.isEmpty()) {
                System.out.println("  [提示] 当前网络不可达，并发/去重逻辑仍已执行；联网后重跑即可看到真实条数");
            }
        } catch (Exception e) {
            System.out.println("  [网络异常] " + e.getMessage() + "，调度逻辑已执行完毕");
        }

        System.out.println("\n===== 演示结束 =====");
    }

    private static String loadConfig() {
        List<Path> candidates = Arrays.asList(
                Paths.get("config/tvbox-sample.json"),
                Paths.get("java-spider-tutorial/config/tvbox-sample.json"));
        for (Path p : candidates) {
            if (Files.exists(p)) {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    System.out.println("  [警告] 读取配置失败: " + e.getMessage());
                }
            }
        }
        return "{\"spider\":\"\",\"sites\":[],\"parses\":[],\"rules\":[]}";
    }

    private static String cut(String json) {
        if (json == null || json.isEmpty()) {
            return "(空)";
        }
        String prefix = json.length() <= 200 ? json : json.substring(0, 200) + " ...";
        return prefix + "   [" + json.length() + " 字符]";
    }
}
