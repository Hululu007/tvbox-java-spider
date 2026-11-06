package com.github.catvod.crawler;

import java.util.HashMap;
import java.util.List;

/**
 * CatVod / TVBox 的爬虫抽象基类。
 *
 * 真实工程里签名是：
 *   public void init(android.content.Context context, String extend)
 * 这里把 Context 换成 Object，是为了让纯 JVM 环境也能编译运行，
 * 方法名、参数顺序、返回 String 的约定完全一致。
 *
 * 加载链路（配置 -> 代码）：
 *   sites[].type = 3
 *   sites[].api  = "csp_XxxGuard"
 *   spider       = "http://.../xxx.jpg;md5;<hash>"
 *      ↓ 壳下载 jar 并校验 MD5
 *      ↓ DexClassLoader 加载 com.github.catvod.spider.XxxGuard
 *      ↓ 反射 newInstance()，注入 siteKey，调用 init(context, ext)
 *      ↓ 按用户操作依次调用下面这些方法
 *
 * 每个方法都返回 JSON 字符串，字段名见 utils/Result.java。
 */
public abstract class Spider {

    /** 由加载器注入，标识当前服务于哪个 site key */
    protected String siteKey;

    /** 配置里 sites[].ext 的内容，可能是 URL 字符串，也可能是 JSON 字符串 */
    protected String ext;

    public void init(Object context, String extend) {
        this.ext = extend == null ? "" : extend;
    }

    public void init(Object context) {
        init(context, "");
    }

    public void setSiteKey(String key) {
        this.siteKey = key;
    }

    /** 首页：分类列表 + 推荐视频 */
    public abstract String homeContent(boolean filter);

    /** 首页推荐位（可选实现） */
    public String homeVideoContent() {
        return "";
    }

    /** 分类页：tid=分类 id，pg=页码，filter=是否带筛选，extend=筛选参数 */
    public abstract String categoryContent(String tid, String pg, boolean filter,
                                           HashMap<String, String> extend);

    /** 详情页：入参是视频 id 列表，返回含播放列表的 Vod */
    public abstract String detailContent(List<String> ids);

    /** 搜索：quick=true 表示快速搜索（下拉联想），应尽快返回少量结果 */
    public abstract String searchContent(String key, boolean quick);

    /** 播放解析：把播放 id 换成真正直链；parse=0 表示无需二次解析 */
    public abstract String playerContent(String flag, String id, List<String> vipFlags);

    /** 直播解析 */
    public String liveContent(String url) {
        return "";
    }

    /** 释放资源 */
    public void destroy() {}
}
