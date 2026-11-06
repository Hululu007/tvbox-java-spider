package com.github.catvod.lab;

import com.github.catvod.utils.HtmlParser;
import com.github.catvod.utils.HttpUtils;
import com.github.catvod.utils.Md5;
import com.github.catvod.utils.SafeFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 第 4 课主程序：把「爬虫」和「写文件」闭环。
 *
 * 运行前提（本机无 Maven 时用 ECJ）：
 *   java -jar lib/ecj-3.36.0.jar --release 21 -encoding UTF-8 \
 *        -cp "lib/jsoup-1.17.2.jar;lib/gson-2.10.1.jar" -d out $(find src/main/java -name "*.java")
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp "out;lib/jsoup-1.17.2.jar;lib/gson-2.10.1.jar" com.github.catvod.lab.WriteLab
 *
 * 产物全部落在 output/，每一步都读回校验，不做「写完就算成功」。
 */
public class WriteLab {

    private static final Path OUT = Paths.get("output");
    private static final String BASE = "https://quotes.toscrape.com";
    /** 假想的配置访问地址：相对路径资源就是相对它解析的 */
    private static final String CONFIG_URL = "http://127.0.0.1:8080/my-subscription.json";

    /** 抓取结果 + 它在第几页第几条，落盘时需要这些定位信息 */
    private record Row(int page, int index, HtmlParser.Quote quote) {}

    public static void main(String[] args) throws Exception {
        SafeFiles.prepare(OUT);
        System.out.println("=== 第 4 课：爬虫结果怎么写文件 / 订阅配置怎么生成 ===");
        System.out.println();

        step1_diff();
        List<Row> rows = step2_crawl();
        step3_tsv(rows);
        step4_json(rows);
        step5_atomic();
        String md5 = step6_jar();
        Path resource = step7_relativeResource();
        Path config = step8_subscription(md5, resource);
        step9_verify(rows, md5, config);

        System.out.println();
        System.out.println("=== 全部写入与校验完成，产物清单 ===");
        try (Stream<Path> s = Files.walk(OUT)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                System.out.printf("  %-46s %8d bytes  md5=%s%n",
                        p.toString().replace('\\', '/'), Files.size(p),
                        SafeFiles.md5(p).substring(0, 8));
            }
        }
    }

    /* ---------- 1. 三份配置对比，写成报告 ---------- */

    private static void step1_diff() throws Exception {
        System.out.println("[1/9] 读取三份订阅配置，生成对比报告");
        String[] names = {"xhztv.json", "wex-20261106.json", "xiuxiu-20261106.json"};
        StringBuilder sb = new StringBuilder();
        sb.append("三份订阅配置对比报告\n")
          .append("生成时间: ").append(LocalDateTime.now()).append("\n")
          .append("=".repeat(72)).append("\n\n");
        for (String name : names) {
            Path p = Paths.get("config", name);
            JsonObject cfg = JsonParser.parseString(SafeFiles.readText(p)).getAsJsonObject();
            JsonArray sites = cfg.getAsJsonArray("sites");
            Map<String, Integer> apiCount = new LinkedHashMap<>();
            int noExt = 0, urlExt = 0, strExt = 0, objExt = 0, csp = 0, other = 0;
            for (JsonElement el : sites) {
                JsonObject s = el.getAsJsonObject();
                String api = s.has("api") ? s.get("api").getAsString() : "";
                apiCount.merge(api, 1, Integer::sum);
                if (api.startsWith("csp_")) {
                    csp++;
                } else {
                    other++;
                }
                JsonElement e = s.get("ext");
                if (e == null || e.isJsonNull()) {
                    noExt++;
                } else if (e.isJsonPrimitive()) {
                    if (e.getAsString().startsWith("http")) {
                        urlExt++;
                    } else {
                        strExt++;
                    }
                } else {
                    objExt++;
                }
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(apiCount.entrySet());
            top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .reversed());
            Md5.SpiderSpec spec = Md5.parseSpider(cfg.get("spider").getAsString());

            sb.append("■ ").append(name).append("  (").append(Files.size(p)).append(" bytes)\n")
              .append("  spider URL   : ").append(spec.url).append("\n")
              .append("  spider 校验  : ").append(spec.algorithm).append(" / ")
              .append(spec.expectedHash).append("\n")
              .append("  伪装后缀     : ").append(spec.disguisedExtension).append("\n")
              .append("  顶层字段     : ").append(cfg.keySet()).append("\n")
              .append("  sites        : ").append(sites.size())
              .append(" (csp_=").append(csp).append(", 非csp=").append(other).append(")\n")
              .append("  不同 api 数  : ").append(apiCount.size()).append("\n")
              .append("  ext 形态     : 无=").append(noExt).append(" URL=").append(urlExt)
              .append(" 字符串=").append(strExt).append(" 对象=").append(objExt).append("\n")
              .append("  parses/lives/flags : ")
              .append(arrSize(cfg, "parses")).append(" / ")
              .append(arrSize(cfg, "lives")).append(" / ")
              .append(arrSize(cfg, "flags")).append("\n")
              .append("  api 复用 TOP4:\n");
            for (int i = 0; i < Math.min(4, top.size()); i++) {
                sb.append("    ").append(top.get(i).getValue()).append("x  ")
                  .append(top.get(i).getKey()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("结论：三份都是同一套 schema（spider/wallpaper/logo/sites/lives/parses/flags），\n")
          .append("      差异只在「站点数量」和「ext 形态」。类复用率越高，ext 越重要。\n")
          .append("      xiuxiu 里的 \"tvfan/Cloud-drive.txt\" 是相对路径，说明订阅是一组文件而非单个 JSON。\n");
        Path report = SafeFiles.writeText(OUT.resolve("00-config-diff.txt"), sb.toString());
        System.out.println("   -> " + report + " (" + Files.size(report) + " bytes)");
        System.out.println();
    }

    private static int arrSize(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key).size() : 0;
    }

    /* ---------- 2. 真实抓取 ---------- */

    private static List<Row> step2_crawl() {
        System.out.println("[2/9] 真实抓取 " + BASE + " 第 1~3 页");
        List<Row> rows = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            String url = page == 1 ? BASE + "/" : BASE + "/page/" + page + "/";
            try {
                String html = HttpUtils.getWithRetry(url, 2);
                List<HtmlParser.Quote> qs = HtmlParser.parseQuotes(html, url);
                for (int i = 0; i < qs.size(); i++) {
                    rows.add(new Row(page, i, qs.get(i)));
                }
                System.out.printf("  第 %d 页: HTTP 200, %d 条, HTML %d 字符%n",
                        page, qs.size(), html.length());
            } catch (Exception e) {
                System.out.println("  第 " + page + " 页抓取失败: " + e.getClass().getSimpleName()
                        + " -> 使用离线样本兜底（明确标注，不伪造网络结果）");
                String sample = "<div class=\"quote\"><span class=\"text\">offline sample "
                        + page + "</span><small class=\"author\">sample</small>"
                        + "<a href=\"/author/sample\">(about)</a>"
                        + "<div class=\"tags\"><a class=\"tag\">demo</a></div></div>";
                for (HtmlParser.Quote q : HtmlParser.parseQuotes(sample, url)) {
                    rows.add(new Row(page, 0, q));
                }
            }
        }
        System.out.println("  合计 " + rows.size() + " 条记录待落盘");
        System.out.println();
        return rows;
    }

    /* ---------- 3. 写 TSV ---------- */

    private static void step3_tsv(List<Row> rows) throws Exception {
        System.out.println("[3/9] 写 TSV（爬虫结果表，逐行流式写）");
        List<String> header = List.of("page", "index", "author", "text", "tags", "about_url");
        List<List<String>> data = new ArrayList<>();
        for (Row r : rows) {
            HtmlParser.Quote q = r.quote();
            data.add(List.of(String.valueOf(r.page()), String.valueOf(r.index()),
                    q.author, q.text, q.tags, q.aboutUrl));
        }
        Path p = SafeFiles.writeTsv(OUT.resolve("quotes.tsv"), header, data);
        System.out.println("   -> " + p + " | " + SafeFiles.countLines(p) + " 行 (含表头) | "
                + Files.size(p) + " bytes");
        System.out.println("   表头: " + String.join(" | ", header));
        System.out.println();
    }

    /* ---------- 4. 写 Spider 风格的 JSON 结果 ---------- */

    private static void step4_json(List<Row> rows) throws Exception {
        System.out.println("[4/9] 写 JSON（homeContent 返回结构直接落盘）");
        JsonObject root = new JsonObject();
        root.addProperty("generated_at", LocalDateTime.now().toString());
        root.addProperty("source", BASE);
        root.addProperty("total", rows.size());
        JsonArray classes = new JsonArray();
        for (int p = 1; p <= 3; p++) {
            JsonObject c = new JsonObject();
            c.addProperty("type_id", String.valueOf(p));
            c.addProperty("type_name", "第 " + p + " 页");
            classes.add(c);
        }
        root.add("class", classes);
        JsonArray list = new JsonArray();
        for (Row r : rows) {
            JsonObject v = new JsonObject();
            v.addProperty("vod_id", r.page() + "#" + r.index());
            v.addProperty("vod_name", r.quote().author + "：" + shorten(r.quote().text, 40));
            v.addProperty("vod_pic", "");
            v.addProperty("vod_remarks", r.quote().tags);
            v.addProperty("type_name", "第 " + r.page() + " 页");
            list.add(v);
        }
        root.add("list", list);
        Path p = SafeFiles.writeJson(OUT.resolve("quotes.json"), root);
        JsonObject back = JsonParser.parseString(SafeFiles.readText(p)).getAsJsonObject();
        System.out.println("   -> " + p + " | " + Files.size(p) + " bytes | 读回 total="
                + back.get("total").getAsInt() + ", list=" + back.getAsJsonArray("list").size());
        System.out.println();
    }

    private static String shorten(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /* ---------- 5. 原子写入演示 ---------- */

    private static void step5_atomic() throws Exception {
        System.out.println("[5/9] 原子写入（同目录 tmp + ATOMIC_MOVE，避免半截配置）");
        Path p = OUT.resolve("atomic-demo.txt");
        SafeFiles.writeText(p, "第 1 次写入：旧内容\n");
        String before = SafeFiles.readText(p).trim();
        SafeFiles.atomicWriteText(p, "第 2 次写入：新内容（整体替换完成）\n");
        String after = SafeFiles.readText(p).trim();
        long tmpLeft;
        try (Stream<Path> s = Files.list(OUT)) {
            tmpLeft = s.filter(x -> x.getFileName().toString().endsWith(".tmp")).count();
        }
        System.out.println("   覆盖前: " + before);
        System.out.println("   覆盖后: " + after);
        System.out.println("   残留 .tmp 文件数: " + tmpLeft + " (必须为 0)");
        SafeFiles.appendLine(p, "第 3 次写入：APPEND 追加一行");
        System.out.println("   追加后行数: " + SafeFiles.countLines(p));
        System.out.println();
    }

    /* ---------- 6. 打包 jar + 算 md5 ---------- */

    private static String step6_jar() throws Exception {
        System.out.println("[6/9] 把编译产物打成 spider jar，并计算真实 md5");
        Path classesRoot = Paths.get("out");
        Path jar = OUT.resolve("custom_spider.jar");
        int n = SafeFiles.writeJar(jar, classesRoot);
        String md5 = SafeFiles.md5(jar);
        try (JarFile jf = new JarFile(jar.toFile())) {
            System.out.println("   -> " + jar + " | " + n + " 个 class | "
                    + jf.size() + " 个 zip 条目 | " + Files.size(jar) + " bytes");
            System.out.println("   MANIFEST: " + jf.getManifest().getMainAttributes().getValue("Spider-Entry"));
        }
        System.out.println("   md5 = " + md5);
        System.out.println();
        return md5;
    }

    /* ---------- 7. 相对路径资源 ---------- */

    private static Path step7_relativeResource() throws Exception {
        System.out.println("[7/9] 写相对路径子规则文件（对应 xiuxiu 的 ext: tvfan/Cloud-drive.txt）");
        Path res = SafeFiles.writeText(OUT.resolve("tvfan/Cloud-drive.txt"),
                "# 子规则文件示例：由主配置的 ext 用相对路径引用\n"
                + "# 壳会按主配置 URL 所在目录解析这个路径\n"
                + "site=quotes.toscrape.com\n"
                + "type=demo\n"
                + "pages=3\n");
        URI base = URI.create(CONFIG_URL);
        URI resolved = base.resolve("tvfan/Cloud-drive.txt");
        System.out.println("   -> " + res);
        System.out.println("   主配置 URL : " + base);
        System.out.println("   相对路径   : tvfan/Cloud-drive.txt");
        System.out.println("   解析结果   : " + resolved);
        System.out.println("   要点：相对路径不能写成 /tvfan/...（以 / 开头会丢掉主配置所在目录）");
        System.out.println();
        return res;
    }

    /* ---------- 8. 生成订阅配置（先写占位，再回填 md5） ---------- */

    private static Path step8_subscription(String md5, Path resource) throws Exception {
        System.out.println("[8/9] 生成订阅配置：先写占位 spider，再把真实 md5 回填");
        Path cfg = OUT.resolve("my-subscription.json");
        String placeholder = "http://127.0.0.1:8080/custom_spider.jar;md5;PENDING";
        JsonObject root = buildConfig(placeholder);
        SafeFiles.writeJson(cfg, root);
        String first = JsonParser.parseString(SafeFiles.readText(cfg))
                .getAsJsonObject().get("spider").getAsString();
        System.out.println("   第 1 次写入: " + first);

        // 关键动作：jar 写完才知道 md5，所以配置文件必须被「改写」一次
        String real = "http://127.0.0.1:8080/custom_spider.jar;md5;" + md5;
        root.addProperty("spider", real);
        SafeFiles.writeJson(cfg, root);   // 内部是原子覆盖
        String second = JsonParser.parseString(SafeFiles.readText(cfg))
                .getAsJsonObject().get("spider").getAsString();
        System.out.println("   第 2 次写入: " + second);
        System.out.println("   -> " + cfg + " | " + Files.size(cfg) + " bytes | 子资源 "
                + resource.getFileName());
        System.out.println();
        return cfg;
    }

    private static JsonObject buildConfig(String spider) {
        JsonObject root = new JsonObject();
        // 字段顺序只是阅读习惯；JSON 解析器不关心顺序，但 UTF-8 无 BOM 是硬要求
        root.addProperty("spider", spider);
        root.addProperty("wallpaper", "");
        root.addProperty("logo", "");
        JsonArray sites = new JsonArray();

        JsonObject s1 = new JsonObject();
        s1.addProperty("key", "DemoSpider");
        s1.addProperty("name", "🧪本地┃爬虫Demo🧪");
        s1.addProperty("type", 3);                  // 3 = jar/dex 插件
        s1.addProperty("api", "csp_DemoSpider");    // csp_ 前缀 -> 类名
        s1.addProperty("searchable", 1);
        s1.addProperty("quickSearch", 1);
        s1.addProperty("changeable", 0);
        JsonObject ext1 = new JsonObject();          // ext 形态之一：对象规则
        ext1.addProperty("Cloud-drive", "tvfan/Cloud-drive.txt");  // 相对路径资源
        ext1.addProperty("base", BASE);
        s1.add("ext", ext1);
        sites.add(s1);

        JsonObject s2 = new JsonObject();
        s2.addProperty("key", "DemoSpider2");
        s2.addProperty("name", "📄规则┃文件引用📄");
        s2.addProperty("type", 3);
        s2.addProperty("api", "csp_DemoSpider");    // 同一个类 + 不同 ext = 不同站点
        s2.addProperty("searchable", 1);
        JsonObject ext2 = new JsonObject();
        ext2.addProperty("json", "tvfan/Cloud-drive.txt");  // ext 形态之二：指向规则文件
        s2.add("ext", ext2);
        sites.add(s2);
        root.add("sites", sites);

        JsonArray parses = new JsonArray();
        JsonObject parse = new JsonObject();
        parse.addProperty("name", "本地解析");
        parse.addProperty("type", 1);
        parse.addProperty("url", "http://127.0.0.1:8080/parse?url=");
        parses.add(parse);
        root.add("parses", parses);

        JsonArray lives = new JsonArray();
        JsonObject live = new JsonObject();
        live.addProperty("name", "示例直播");
        live.addProperty("type", 0);
        live.addProperty("url", "https://example.invalid/live.m3u");
        live.addProperty("playerType", 2);
        lives.add(live);
        root.add("lives", lives);
        root.add("flags", new JsonArray());
        return root;
    }

    /* ---------- 9. 逐项读回校验 ---------- */

    private static void step9_verify(List<Row> rows, String md5, Path cfg) throws Exception {
        System.out.println("[9/9] 读回校验（写完必须验证，否则不算交付）");
        JsonObject back = JsonParser.parseString(SafeFiles.readText(cfg)).getAsJsonObject();
        String[] parts = back.get("spider").getAsString().split(";", 3);
        Path jar = OUT.resolve("custom_spider.jar");
        boolean spiderOk = parts.length == 3 && parts[2].equalsIgnoreCase(md5);
        boolean md5Ok = SafeFiles.md5(jar).equalsIgnoreCase(md5);
        // 关键：用完全不同的实现复算，避免「生成与校验共用同一个有 bug 的函数」的自洽假象
        String cross = independentMd5(jar);
        boolean crossOk = cross.equalsIgnoreCase(md5);
        boolean jarOk;
        int entries;
        try (JarFile jf = new JarFile(jar.toFile())) {
            entries = jf.size();
            jarOk = jf.getJarEntry("com/github/catvod/spider/DemoSpider.class") != null
                    && jf.getManifest() != null;
        }
        long tsvLines = SafeFiles.countLines(OUT.resolve("quotes.tsv"));
        boolean tsvOk = tsvLines == rows.size() + 1L;
        JsonObject result = JsonParser.parseString(
                SafeFiles.readText(OUT.resolve("quotes.json"))).getAsJsonObject();
        boolean jsonOk = result.get("total").getAsInt() == rows.size()
                && result.getAsJsonArray("list").size() == rows.size();
        boolean extOk = back.getAsJsonArray("sites").get(0).getAsJsonObject()
                .getAsJsonObject("ext").get("Cloud-drive").getAsString()
                .equals("tvfan/Cloud-drive.txt");
        boolean resOk = Files.exists(OUT.resolve("tvfan/Cloud-drive.txt"));
        long tmpLeft;
        try (Stream<Path> s = Files.list(OUT)) {
            tmpLeft = s.filter(x -> x.getFileName().toString().endsWith(".tmp")).count();
        }

        System.out.printf("  %-38s %s%n", "spider 第三段 == jar 真实 md5", ok(spiderOk));
        System.out.printf("  %-38s %s%n", "jar md5 复算一致", ok(md5Ok));
        System.out.printf("  %-38s %s (JDK 直算=%s)%n", "独立实现复算 md5 一致",
                ok(crossOk), cross);
        System.out.printf("  %-38s %s (%d 条目)%n", "jar 含 DemoSpider.class + MANIFEST",
                ok(jarOk), entries);
        System.out.printf("  %-38s %s (%d 行 / 期望 %d)%n", "TSV 行数 == 记录数 + 表头",
                ok(tsvOk), tsvLines, rows.size() + 1L);
        System.out.printf("  %-38s %s (total=%d)%n", "quotes.json 可解析且条数一致",
                ok(jsonOk), result.get("total").getAsInt());
        System.out.printf("  %-38s %s%n", "ext 相对路径写法正确", ok(extOk));
        System.out.printf("  %-38s %s%n", "子规则文件确实存在", ok(resOk));
        System.out.printf("  %-38s %s%n", "无残留 .tmp 临时文件", ok(tmpLeft == 0));
        boolean cfgOk = back.getAsJsonArray("sites").size() == 2;
        System.out.printf("  %-38s %s (%d 个站点)%n", "订阅配置可被 Gson 重新解析",
                ok(cfgOk), back.getAsJsonArray("sites").size());
        boolean allOk = spiderOk && md5Ok && crossOk && jarOk && tsvOk && jsonOk
                && extOk && resOk && tmpLeft == 0 && cfgOk;
        System.out.println("  " + "-".repeat(52));
        System.out.println("  总体: " + ok(allOk));
        if (!allOk) {
            throw new IllegalStateException("写入产物校验未全部通过，请检查上面的 FAIL 项");
        }
    }

    /** 与 SafeFiles.md5 完全独立的实现：JDK MessageDigest + HexFormat，不碰 Md5 类 */
    private static String independentMd5(Path p) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(p));
        return HexFormat.of().formatHex(digest);
    }

    private static String ok(boolean b) {
        return b ? "PASS" : "FAIL";
    }
}
