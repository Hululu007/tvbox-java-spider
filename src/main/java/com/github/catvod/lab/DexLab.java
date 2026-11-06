package com.github.catvod.lab;

import com.github.catvod.utils.ConfigParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 第 3 课：不依赖任何反编译器，直接读 dex 字符串表验证配置与代码的一致性。
 *
 * 这是逆向 / 静态分析里性价比最高的一步：
 *   类名、方法名、加密算法、URL 模板、规则关键字都作为常量留在字符串表里。
 */
public class DexLab {

    public static void main(String[] args) throws Exception {
        System.out.println("===== 第 3 课 · dex 静态分析 =====\n");

        String json = new String(Files.readAllBytes(Paths.get("config/xhztv.json")),
                StandardCharsets.UTF_8);
        Set<String> needed = new TreeSet<>();
        for (String api : ConfigParser.parse(json).cspApis) {
            needed.add("com.github.catvod.spider." + api.substring(4));
        }

        DexScanner scanner = DexScanner.fromPackage(Paths.get("lab/Yoursmile.jar"));
        if (scanner == null) {
            System.out.println("包内无 classes.dex，不是 dex 插件");
            return;
        }

        System.out.println("【1】dex 文件头");
        System.out.println("  magic       : " + scanner.magic().trim());
        System.out.println("  大小        : " + scanner.size() + " 字节");
        System.out.println("  string_ids  : " + scanner.stringCount());
        System.out.println("  type_ids    : " + scanner.typeCount());

        List<String> strings = scanner.readStrings();
        Set<String> classes = scanner.spiderClassNames();

        System.out.println("\n【2】类名清单与订阅配置对照");
        System.out.println("  com.github.catvod.spider 下的类名 : " + classes.size());
        int hit = 0;
        List<String> miss = new ArrayList<>();
        for (String cls : needed) {
            if (classes.contains(cls)) {
                hit++;
            } else {
                miss.add(cls);
            }
        }
        System.out.println("  配置引用的不同类 : " + needed.size());
        System.out.println("  命中 : " + hit + "   缺失 : " + miss.size() + "  " + miss);
        System.out.println("  配置里的几个关键类是否在包中:");
        for (String k : Arrays.asList("XBPQ", "Bili", "AppMao", "AppLd", "Init", "Proxy", "XPath")) {
            System.out.println("    " + k + " : "
                    + (classes.contains("com.github.catvod.spider." + k) ? "存在" : "缺失"));
        }

        System.out.println("\n【3】Spider 契约方法名（字符串表里应能找到）");
        for (String m : Arrays.asList("homeContent", "homeVideoContent", "categoryContent",
                "detailContent", "searchContent", "playerContent", "init", "destroy")) {
            System.out.println("  " + m + " : " + count(strings, m));
        }

        System.out.println("\n【4】加解密相关常量（解释 ext 里的 base64 密文）");
        for (String k : Arrays.asList("AES", "AES/CBC/PKCS5Padding", "AES/CTR/PKCS5Padding",
                "DESede", "DESede/CBC/PKCS7Padding", "Base64",
                "Ljavax/crypto/Cipher;", "Ljavax/crypto/spec/SecretKeySpec;",
                "Ljavax/crypto/spec/IvParameterSpec;")) {
            int c = countExact(strings, k);
            if (c > 0) {
                System.out.println("  " + k + " : " + c);
            }
        }

        System.out.println("\n【5】规则引擎关键字（csp_XBPQ 靠 ext 对象驱动）");
        Map<String, Integer> ruleHits = new LinkedHashMap<>();
        for (String k : Arrays.asList("&&", "嗅探词", "分类url", "搜索url", "数组", "[替换:",
                "m3u8", ".mp4", "{cateId}", "{catePg}", "{wd}", "post;")) {
            ruleHits.put(k, countContains(strings, k));
        }
        ruleHits.forEach((k, v) -> System.out.println("  " + k + " : " + v));

        System.out.println("\n  -> 结论：dex 内确实存在通用规则引擎，站点差异全部下沉到 ext；");
        System.out.println("     ext 里的加密串由包内 AES/DESede 逻辑解密，密钥需反编译具体类才能定位。");
    }

    private static int count(List<String> strings, String exact) {
        return countExact(strings, exact);
    }

    private static int countExact(List<String> strings, String value) {
        int n = 0;
        for (String s : strings) {
            if (s.equals(value)) {
                n++;
            }
        }
        return n;
    }

    private static int countContains(List<String> strings, String needle) {
        int n = 0;
        for (String s : strings) {
            if (s.contains(needle)) {
                n++;
            }
        }
        return n;
    }
}
