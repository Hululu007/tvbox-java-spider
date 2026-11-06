package com.github.catvod.pan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网盘配置中心：Cookie 的本地持久化。
 *
 * <p>对应 JS 插件里的 USER_COOKIES / $env.cookies。TVBox 壳里那部分由
 * 「配置中心」站点注入环境变量，这里改成一份本地 JSON + 一个扫码可打开的网页。</p>
 *
 * <p>三条硬规矩（第 4 课结论）：UTF-8 无 BOM、原子写、写完读回校验。</p>
 */
public final class PanConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Paths.get("output", "pan", "cookies.json");

    /** 配置键 -> 中文说明，顺序即配置页表单顺序。 */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("quark_cookie", "夸克 Cookie（pan.quark.cn，播放夸克必填）");
        FIELDS.put("baidu_cookie", "百度 Cookie（pan.baidu.com）");
        FIELDS.put("pan115_cookie", "115 Cookie（115.com）");
        FIELDS.put("uc_cookie", "UC Cookie（可选，UC 现在由接口自动下发 __pugs）");
        FIELDS.put("kfzy_cookie", "夸父资源社 Cookie（盘她┃夸父 抓帖用，留空用内置会话）");
        FIELDS.put("host", "全盘搜主站（留空用 https://quanpan.xyz）");
    }

    private PanConfig() {}

    public static JsonObject load() {
        JsonObject def = new JsonObject();
        for (String k : FIELDS.keySet()) def.addProperty(k, "");
        if (!Files.exists(FILE)) return def;
        try {
            JsonObject saved = JsonParser.parseString(
                    Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : FIELDS.keySet()) {
                if (saved.has(k) && saved.get(k).isJsonPrimitive()) {
                    def.addProperty(k, saved.get(k).getAsString());
                }
            }
        } catch (Exception ignored) {
            // 配置损坏时回退到默认值，不让整个服务起不来
        }
        return def;
    }

    /** 原子写：同目录 tmp -> ATOMIC_MOVE，避免半截 JSON。 */
    public static void save(JsonObject cfg) throws IOException {
        Files.createDirectories(FILE.getParent());
        Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(cfg), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
        // 读回校验：写进去的和读出来的一致
        JsonObject back = JsonParser.parseString(
                Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String k : FIELDS.keySet()) {
            String expect = cfg.has(k) ? cfg.get(k).getAsString() : "";
            String actual = back.has(k) ? back.get(k).getAsString() : "";
            if (!expect.equals(actual)) {
                throw new IOException("配置读回校验失败: " + k);
            }
        }
    }

    public static String get(String key) {
        JsonObject o = load();
        return o.has(key) ? o.get(key).getAsString().trim() : "";
    }

    public static void set(String key, String value) throws IOException {
        JsonObject o = load();
        o.addProperty(key, value == null ? "" : value.trim());
        save(o);
    }

    /** 脱敏状态：只报「已配置 / 长度」，绝不回显 Cookie 明文。 */
    public static JsonObject status() {
        JsonObject cfg = load();
        JsonObject out = new JsonObject();
        for (String k : FIELDS.keySet()) {
            String v = cfg.has(k) ? cfg.get(k).getAsString() : "";
            JsonObject one = new JsonObject();
            one.addProperty("configured", !v.isBlank());
            one.addProperty("length", v.length());
            one.addProperty("hint", FIELDS.get(k));
            out.add(k, one);
        }
        return out;
    }

    public static String site() {
        String h = get("host");
        return h.startsWith("http") ? h.replaceAll("/+$", "") : "https://quanpan.xyz";
    }
}
