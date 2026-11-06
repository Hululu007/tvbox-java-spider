package com.github.catvod.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯 Java 读取 Android classes.dex 的字符串表。
 *
 * 为什么需要它：TVBox 的 "spider" 包很多其实是 dex 包（签名 + classes.dex），
 * 里面没有 .class，用普通 jar 工具或 CFR 会一无所获。壳用 DexClassLoader 加载。
 *
 * dex 文件头（小端）关键偏移：
 *   0x00 magic 8 字节  "dex\n035\0"
 *   0x20 file_size
 *   0x38 string_ids_size
 *   0x3c string_ids_off   -> uint32 数组，每项指向一条 string_data
 *   string_data = ULEB128 长度 + MUTF-8 字节 + \0
 *
 * 类描述符以 "L" 开头 ";" 结尾，如 Lcom/github/catvod/spider/XBPQ;
 * 从字符串表里筛出它们，就能在不反编译的情况下拿到完整类名清单。
 */
public final class DexScanner {

    private static final String CLASS_PREFIX = "Lcom/github/catvod/spider/";

    private final byte[] dex;

    private DexScanner(byte[] dex) {
        this.dex = dex;
    }

    /** 从 jar/zip 里取 classes.dex；不是 dex 包时返回 null */
    public static DexScanner fromPackage(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return new DexScanner(out.toByteArray());
            }
        }
    }

    public String magic() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            char c = (char) (dex[i] & 0xff);
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString();
    }

    public int size() {
        return dex.length;
    }

    public int stringCount() {
        return u32(0x38);
    }

    public int typeCount() {
        return u32(0x40);
    }

    /** 读出 string_ids 表里的全部字符串 */
    public List<String> readStrings() {
        int count = stringCount();
        int tableOff = u32(0x3c);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int dataOff = u32(tableOff + 4 * i);
            int[] lenAndPos = uleb128(dataOff);
            int len = lenAndPos[0];
            int pos = lenAndPos[1];
            if (len < 0 || pos < 0 || pos + len > dex.length) {
                continue;
            }
            out.add(new String(dex, pos, len, StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 字符串表里所有 com.github.catvod.spider.* 的类名（排除内部类） */
    public Set<String> spiderClassNames() {
        Set<String> result = new TreeSet<>();
        for (String s : readStrings()) {
            if (s.startsWith(CLASS_PREFIX) && s.endsWith(";") && !s.contains("$")) {
                result.add(s.substring(1, s.length() - 1).replace('/', '.'));
            }
        }
        return result;
    }

    private int u32(int off) {
        return (dex[off] & 0xff)
                | ((dex[off + 1] & 0xff) << 8)
                | ((dex[off + 2] & 0xff) << 16)
                | ((dex[off + 3] & 0xff) << 24);
    }

    /** 返回 [数值, 读取后的偏移] */
    private int[] uleb128(int off) {
        int result = 0;
        int shift = 0;
        int pos = off;
        while (pos < dex.length) {
            int b = dex[pos++] & 0xff;
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new int[]{result, pos};
    }
}
