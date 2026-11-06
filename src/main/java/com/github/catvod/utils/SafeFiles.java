package com.github.catvod.utils;

import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * 第 4 课：Java 写文件全集。
 *
 * 爬虫跑完只是内存里的一堆对象，真正能交付的是磁盘上的文件。
 * 这里按「由简到繁」封装 6 种写入姿势，每一种都对应一个真实场景：
 *
 *   1. writeText     一次性小文件（配置片段、状态标记）
 *   2. writeLines    流式逐行（10 万条结果不会撑爆内存）
 *   3. appendLine    增量追加（断点续爬的日志 / checkpoint）
 *   4. atomicWrite   原子替换（配置半截写坏 = 整个壳加载失败，必须原子）
 *   5. writeJson     结构化落盘（Gson pretty printing，订阅配置就是这么来的）
 *   6. writeTsv      表格结果（TSV 比 CSV 安全：正文里的逗号不用处理）
 *   7. writeJar      zip/jar 容器（spider 插件就是个带 .class 的压缩包）
 *
 * 统一约定：UTF-8、无 BOM、\n 换行。中文配置文件乱码 90% 是这里没统一。
 */
public final class SafeFiles {

    private SafeFiles() {}

    public static Path prepare(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    /* ---------- 1. 一次性写：适合几 KB 以内 ---------- */

    public static Path writeText(Path target, String text) throws IOException {
        ensureParent(target);
        // Files.writeString 默认 = CREATE + TRUNCATE_EXISTING + WRITE
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return target;
    }

    /* ---------- 2. 流式逐行：内容不必全部驻留内存 ---------- */

    public static Path writeLines(Path target, List<String> lines) throws IOException {
        ensureParent(target);
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line == null ? "" : line);
                w.newLine();
            }
        }
        return target;
    }

    /* ---------- 3. 追加：增量日志 / 断点续爬 ---------- */

    public static Path appendLine(Path target, String line) throws IOException {
        ensureParent(target);
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            w.write(line == null ? "" : line);
            w.newLine();
        }
        return target;
    }

    /* ---------- 4. 原子写：先写同目录临时文件，再整体 rename ---------- */

    public static Path atomicWrite(Path target, byte[] data) throws IOException {
        ensureParent(target);
        Path dir = target.toAbsolutePath().getParent();
        // 临时文件必须在同一分区，否则 ATOMIC_MOVE 会失败
        Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（网络盘/FAT）不支持原子移动，降级为普通覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    public static Path atomicWriteText(Path target, String text) throws IOException {
        return atomicWrite(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /* ---------- 5. 写 JSON：结构化配置 ---------- */

    public static Path writeJson(Path target, Object value) throws IOException {
        String json = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()   // 不让 Gson 把 < > & 转成 \u003c，emoji/中文保持原样
                .create()
                .toJson(value);
        // 配置文件一律走原子写：写一半的 JSON 会让整个订阅加载失败
        return atomicWriteText(target, json + "\n");
    }

    /* ---------- 6. 写 TSV：爬虫结果表 ---------- */

    public static Path writeTsv(Path target, List<String> header, List<List<String>> rows)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(joinCells(header));
        for (List<String> row : rows) {
            lines.add(joinCells(row));
        }
        return writeLines(target, lines);
    }

    private static String joinCells(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(cell(cells.get(i)));
        }
        return sb.toString();
    }

    /** 单元格里出现制表符/换行会破坏列结构，必须清洗 */
    private static String cell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /* ---------- 7. 写 jar：本质是写 zip ---------- */

    /**
     * 把编译产物目录里的 .class 打成 jar。
     * TVBox 的 spider 包就是这个形状（真实壳多用 classes.dex，见第 3 课）。
     */
    public static int writeJar(Path target, Path classesRoot) throws IOException {
        ensureParent(target);
        List<Path> classes;
        try (Stream<Path> s = Files.walk(classesRoot)) {
            classes = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "java-spider-tutorial/WriteLab");
        mf.getMainAttributes().putValue("Spider-Entry", "com.github.catvod.spider.DemoSpider");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(target), mf)) {
            for (Path c : classes) {
                // zip 条目分隔符永远是 /，Windows 的 \ 必须替换
                String name = classesRoot.relativize(c).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(name));
                Files.copy(c, jos);
                jos.closeEntry();
            }
        }
        return classes.size();
    }

    /* ---------- 读回与校验 ---------- */

    public static String readText(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static long countLines(Path p) throws IOException {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.count();
        }
    }

    /** 对文件字节求一次 MD5。Md5.hex 内部已包含 digest 步骤，不可再算一次 */
    public static String md5(Path p) throws IOException {
        return Md5.hex(Files.readAllBytes(p));
    }

    private static void ensureParent(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
