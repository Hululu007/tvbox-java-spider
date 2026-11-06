package com.github.catvod.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 调度层：并发 + 限速 + 去重 + 重试 + 超时。
 *
 * 这是"能跑的 demo"和"能用的爬虫"之间的分界线：
 *   - 线程池：IO 密集型，线程数可以略大于 CPU 核数
 *   - Semaphore 串行化发起时刻，保证请求之间最小间隔，别把对方站点打挂
 *   - ConcurrentHashMap.newKeySet()：并发安全的已抓取集合，天然去重
 *   - Future.get(timeout)：单个任务卡死不能拖垮整批
 */
public final class ConcurrentCrawler implements AutoCloseable {

    public static final class Page {
        public final String url;
        public final List<HtmlParser.Quote> quotes;

        Page(String url, List<HtmlParser.Quote> quotes) {
            this.url = url;
            this.quotes = quotes;
        }
    }

    private final ExecutorService pool;
    private final Semaphore gate = new Semaphore(1);
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final long intervalMs;

    public ConcurrentCrawler(int threads, long intervalMs) {
        this.pool = Executors.newFixedThreadPool(threads);
        this.intervalMs = Math.max(0, intervalMs);
    }

    public List<Page> fetch(List<String> urls) {
        List<Future<Page>> futures = new ArrayList<>();
        for (final String url : urls) {
            if (!seen.add(url)) {
                System.out.println("  [去重] 跳过重复 URL: " + url);
                continue;
            }
            futures.add(pool.submit(new Callable<Page>() {
                @Override
                public Page call() throws Exception {
                    gate.acquire();
                    try {
                        // 只在"发起请求前"串行，下载过程仍然并发
                        if (intervalMs > 0) {
                            Thread.sleep(intervalMs);
                        }
                    } finally {
                        gate.release();
                    }
                    String html = HttpUtils.getWithRetry(url, 2);
                    return new Page(url, HtmlParser.parseQuotes(html, url));
                }
            }));
        }

        List<Page> result = new ArrayList<>();
        for (Future<Page> f : futures) {
            try {
                result.add(f.get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.println("  [失败] " + rootCause(e));
            }
        }
        return result;
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
