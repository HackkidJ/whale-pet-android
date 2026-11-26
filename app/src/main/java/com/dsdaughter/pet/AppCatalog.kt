package com.dsdaughter.pet

import android.content.Context

/** 常用应用名/包名映射，供意图识别与任务代理共用。 */
object AppCatalog {
    val packages: Map<String, String> = mapOf(
        "设置" to "com.android.settings",
        "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "淘宝" to "com.taobao.taobao",
        "美团" to "com.sankuai.meituan",
        "夸克" to "com.quark.browser",
        "闲鱼" to "com.taobao.idlefish",
        "咸鱼" to "com.taobao.idlefish",
        "支付宝" to "com.eg.android.AlipayGphone",
        "浏览器" to "com.heytap.browser",
        "抖音" to "com.ss.android.ugc.aweme",
        "小红书" to "com.xingin.xhs",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "高德" to "com.autonavi.minimap",
        "网易云音乐" to "com.netease.cloudmusic",
        "settings" to "com.android.settings",
        "wechat" to "com.tencent.mm",
        "bilibili" to "tv.danmaku.bili",
        "taobao" to "com.taobao.taobao",
        "meituan" to "com.sankuai.meituan",
        "quark" to "com.quark.browser",
        "xianyu" to "com.taobao.idlefish",
    )

    fun resolve(name: String): String? {
        val key = name.trim().lowercase()
        packages[key]?.let { return it }
        if (name.contains('.')) return name
        return packages.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
    }

    fun label(context: Context, packageName: String): String =
        packages.entries.firstOrNull { it.value == packageName }?.key ?: packageName

    /** 文本里是否提到了某个 App。 */
    fun mentionsApp(text: String): Boolean {
        val lowered = text.lowercase()
        return packages.keys.any { lowered.contains(it) }
    }
}
