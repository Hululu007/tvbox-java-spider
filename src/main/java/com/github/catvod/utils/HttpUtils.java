package com.github.catvod.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 层：JDK 11+ 原生 HttpClient，零第三方依赖。
 *
 * 爬虫的四个基本动作都在这里：
 *   1. 伪装 UA / 自定义 Header（配置里的 headers / lives[].ua 就是干这个的）
 *   2. 超时控制（连接 / 单请求）
 *   3. 自动跟随重定向
 *   4. 按响应 Content-Type 判断编码，避免中文乱码
 *
 * 换成 TVBox 真实 jar 时，用 OkHttp 实现同样的四个动作即可。
 */
public final class HttpUtils {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpUtils() {}

    public static String get(String url) throws IOException, InterruptedException {
        return get(url, null);
    }

    public static String get(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/json,*/*")
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<byte[]> resp = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " -> " + url);
        }
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        return decode(resp.body(), ct);
    }

    /** POST JSON，很多采集接口是 POST 而不是 GET */
    public static String postJson(String url, String json, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<byte[]> resp = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " -> " + url);
        }
        return decode(resp.body(), resp.headers().firstValue("Content-Type").orElse(""));
    }

    /** 取二进制，用于下载 spider jar 后做 MD5 校验 */
    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " -> " + url);
        }
        return resp.body();
    }

    /** 失败重试 + 线性退避，爬虫必备；被限流/偶发超时时很有效 */
    public static String getWithRetry(String url, int retries)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int i = 0; i <= retries; i++) {
            try {
                return get(url);
            } catch (IOException e) {
                last = e;
                if (i < retries) {
                    Thread.sleep(300L * (i + 1));
                }
            }
        }
        throw last;
    }

    private static String decode(byte[] body, String contentType) {
        String ct = contentType.toLowerCase();
        Charset cs;
        if (ct.contains("gbk") || ct.contains("gb2312") || ct.contains("gb18030")) {
            cs = Charset.forName("GBK");
        } else if (ct.contains("iso-8859-1")) {
            cs = StandardCharsets.ISO_8859_1;
        } else {
            cs = StandardCharsets.UTF_8;
        }
        return new String(body, cs);
    }
}
