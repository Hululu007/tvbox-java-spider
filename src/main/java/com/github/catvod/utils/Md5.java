package com.github.catvod.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 对应配置里的 spider 字段：
 *   "spider": "http://xxx/xxx.jpg;md5;86cb2cdcd99e29334004b80e48007e9a"
 *
 * 三段用分号分隔：下载地址 / 校验算法 / 期望摘要。
 * .jpg 是伪装后缀（CDN 常把 jar 当图片缓存），壳不靠后缀判断类型，
 * 下载后算 MD5 与第三段比对：一致才写入本地缓存并交给 DexClassLoader 加载。
 * 这样源站换包时壳能感知到，也能防止下载到被篡改的 jar。
 */
public final class Md5 {

    public static final class SpiderSpec {
        public final String url;
        public final String algorithm;
        public final String expectedHash;
        public final boolean disguisedExtension;

        SpiderSpec(String url, String algorithm, String expectedHash, boolean disguised) {
            this.url = url;
            this.algorithm = algorithm;
            this.expectedHash = expectedHash;
            this.disguisedExtension = disguised;
        }

        @Override
        public String toString() {
            return "下载地址 : " + url + "\n"
                    + "校验算法 : " + (algorithm.isEmpty() ? "(未指定)" : algorithm) + "\n"
                    + "期望摘要 : " + (expectedHash.isEmpty() ? "(未指定，壳将不做校验)" : expectedHash) + "\n"
                    + "伪装后缀 : " + (disguisedExtension ? "是（非 .jar，靠内容识别）" : "否");
        }
    }

    private Md5() {}

    public static SpiderSpec parseSpider(String field) {
        if (field == null || field.isEmpty()) {
            return new SpiderSpec("", "", "", false);
        }
        String[] parts = field.split(";", 3);
        String url = parts[0];
        String alg = parts.length > 1 ? parts[1] : "";
        String hash = parts.length > 2 ? parts[2] : "";
        boolean disguised = !url.toLowerCase().contains(".jar");
        return new SpiderSpec(url, alg, hash, disguised);
    }

    /**
     * 注意语义：这里传进去的是「原始数据」，函数会先求 MD5 再转十六进制。
     * 不是「把摘要字节转成 hex」。若手上已经是 digest，请用 toHex()。
     * 曾踩过的坑：md5(md5(file)) 双重哈希，生成与校验都用它时自洽但全错。
     */
    public static String hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 纯转换：把已有的摘要字节转成小写十六进制，不做任何哈希 */
    public static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(byte[] jarBytes, String expectedHash) {
        return expectedHash != null && !expectedHash.isEmpty()
                && hex(jarBytes).equalsIgnoreCase(expectedHash);
    }
}
