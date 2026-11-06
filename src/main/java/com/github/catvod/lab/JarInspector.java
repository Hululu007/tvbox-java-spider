package com.github.catvod.lab;

import com.github.catvod.utils.ConfigParser;
import com.github.catvod.utils.Md5;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 第 2 课：拿一份真实订阅配置 + 它声明的 spider jar 做静态分析。
 *
 * 全程只读，不执行 jar 里任何代码：
 *   1. 配置结构概览
 *   2. sites.api 的形态（csp_ 类名 / .js 脚本 / 其它）
 *   3. 同一个 api 被多个站点复用 —— 这正是 init(context, extend) 里 extend 存在的意义
 *   4. sites.ext 的四种形态，以及加密串的 base64 特征
 *   5. 下载包的 MD5 完整性校验
 *   6. jar 内 class 清单与 csp_ 名称逐一对照，找出可加载 / 缺失的类
 */
public class JarInspector {

    private static final Path CFG = Paths.get("config/xhztv.json");
    private static final Path JAR = Paths.get("lab/Yoursmile.jar");
    private static final String SPIDER_PKG = "com/github/catvod/spider/";

    public static void main(String[] args) throws Exception {
        System.out.println("===== 第 2 课 · 真实订阅配置与 spider jar 静态分析 =====\n");

        String json = new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        System.out.println("【1】配置结构概览");
        ConfigParser.Summary summary = ConfigParser.parse(json);
        System.out.println(summary.report());
        System.out.println("parses 形态 : " + describeParses(root));
        System.out.println("flags      : " + arr(root, "flags").size() + " 个播放标识");
        System.out.println("lives 扩展 : " + describeLives(root));

        JsonArray sites = arr(root, "sites");
        System.out.println("\n【2】sites.api 的形态");
        Map<String, Integer> apiUses = new TreeMap<>();
        List<String> cspNames = new ArrayList<>();
        List<String> jsApis = new ArrayList<>();
        int other = 0;
        for (JsonElement e : sites) {
            JsonObject site = e.getAsJsonObject();
            String api = str(site, "api");
            if (api.startsWith("csp_")) {
                String className = api.substring(4);
                cspNames.add(className);
                apiUses.merge(className, 1, Integer::sum);
            } else if (api.endsWith(".js")) {
                jsApis.add(api);
            } else {
                other++;
            }
        }
        System.out.println("  jar 插件 (csp_) : " + cspNames.size() + " 个站点，"
                + apiUses.size() + " 个不同类");
        System.out.println("  JS 插件 (.js)   : " + jsApis.size() + " 个站点");
        System.out.println("  其它           : " + other);
        if (!jsApis.isEmpty()) {
            System.out.println("  JS 引擎地址     : " + jsApis.get(0));
        }

        System.out.println("\n【3】同一个类被多个站点复用（靠 extend 区分）");
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(apiUses.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> en : sorted) {
            if (en.getValue() > 1) {
                System.out.println("  " + en.getKey() + "  ×" + en.getValue());
            }
        }

        System.out.println("\n【4】sites.ext 的四种形态");
        Map<String, Integer> extKinds = new LinkedHashMap<>();
        List<String> encrypted = new ArrayList<>();
        for (JsonElement e : sites) {
            JsonObject site = e.getAsJsonObject();
            if (!site.has("ext")) {
                extKinds.merge("无", 1, Integer::sum);
                continue;
            }
            JsonElement ext = site.get("ext");
            String kind;
            if (ext.isJsonObject()) {
                kind = "规则对象(" + ext.getAsJsonObject().size() + " 字段)";
            } else {
                String v = ext.getAsString();
                if (v.contains("127.0.0.1:9978")) {
                    kind = "本地代理 token";
                } else if (v.startsWith("http://") || v.startsWith("https://")) {
                    kind = "远程 URL / 子配置";
                } else if (v.length() > 60 && v.matches("[A-Za-z0-9+/=]+")) {
                    kind = "加密串";
                    encrypted.add(v);
                } else {
                    kind = "其它字符串";
                }
            }
            extKinds.merge(kind, 1, Integer::sum);
        }
        extKinds.forEach((k, v) -> System.out.println("  " + k + " : " + v + " 个"));

        System.out.println("\n【5】加密串的 base64 特征");
        for (String enc : encrypted) {
            try {
                byte[] raw = Base64.getDecoder().decode(enc);
                StringBuilder head = new StringBuilder();
                for (int i = 0; i < Math.min(12, raw.length); i++) {
                    head.append(String.format("%02x ", raw[i] & 0xff));
                }
                System.out.println("  明文长 " + enc.length() + " -> 解码 " + raw.length
                        + " 字节, 16 倍数=" + (raw.length % 16 == 0)
                        + ", 首字节 " + head.toString().trim());
            } catch (IllegalArgumentException ex) {
                System.out.println("  非标准 base64: " + enc.substring(0, 20) + "...");
            }
        }
        System.out.println("  -> 长度是 16 的倍数且首字节无明显 magic，符合 AES/DES 分组密文特征；"
                + "\n     密钥通常在 jar 的常量里，下一步反编译找它。");

        System.out.println("\n【6】spider 包完整性校验");
        Md5.SpiderSpec spec = Md5.parseSpider(str(root, "spider"));
        System.out.println(spec);
        if (!Files.exists(JAR)) {
            System.out.println("  本地尚无 jar，跳过后续分析");
            return;
        }
        byte[] jarBytes = Files.readAllBytes(JAR);
        String localMd5 = Md5.hex(jarBytes);
        System.out.println("  本地大小 : " + jarBytes.length + " 字节");
        System.out.println("  本地 md5 : " + localMd5);
        boolean ok = Md5.verify(jarBytes, spec.expectedHash);
        System.out.println("  摘要匹配 : " + ok);
        if (!ok) {
            System.out.println("  文件不完整或已更新，仅按已落地部分尝试解析 zip 结构");
        }

        System.out.println("\n【7】jar 内 class 清单与 csp_ 名称对照");
        inspectJar(jarBytes, cspNames);
    }

    private static void inspectJar(byte[] jarBytes, List<String> cspNames) {
        File file = JAR.toFile();
        try (JarFile jar = new JarFile(file)) {
            int total = 0;
            Set<String> spiderClasses = new TreeSet<>();
            Map<String, Integer> packages = new TreeMap<>();
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                total++;
                int slash = name.lastIndexOf('/');
                String pkg = slash < 0 ? "(default)" : name.substring(0, slash);
                packages.merge(pkg, 1, Integer::sum);
                if (name.startsWith(SPIDER_PKG)) {
                    String simple = name.substring(SPIDER_PKG.length()).replace(".class", "");
                    if (!simple.contains("$") && !simple.contains("/")) {
                        spiderClasses.add(simple);
                    }
                }
            }
            System.out.println("  class 总数      : " + total);
            System.out.println("  包分布 (前 8)   :");
            packages.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(p -> System.out.println("    " + p.getKey() + " : " + p.getValue()));
            System.out.println("  spider 实现类   : " + spiderClasses.size() + " 个");
            System.out.println("  " + spiderClasses);

            Set<String> needed = new TreeSet<>(cspNames);
            int hit = 0, miss = 0;
            for (String cls : needed) {
                boolean found = spiderClasses.contains(cls);
                if (found) {
                    hit++;
                } else {
                    miss++;
                    System.out.println("    [缺失] " + cls);
                }
            }
            System.out.println("  对照结果        : 命中 " + hit + " / 缺失 " + miss
                    + "（共 " + needed.size() + " 个不同类）");
        } catch (Exception e) {
            System.out.println("  jar 解析失败: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static String describeParses(JsonObject root) {
        Map<String, Integer> byType = new TreeMap<>();
        List<String> names = new ArrayList<>();
        for (JsonElement e : arr(root, "parses")) {
            JsonObject o = e.getAsJsonObject();
            byType.merge(str(o, "type"), 1, Integer::sum);
            names.add(str(o, "name"));
        }
        return byType + "  " + names;
    }

    private static String describeLives(JsonObject root) {
        JsonArray lives = arr(root, "lives");
        if (lives.size() == 0) {
            return "无";
        }
        JsonObject o = lives.get(0).getAsJsonObject();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            keys.add(en.getKey());
        }
        return keys.toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
