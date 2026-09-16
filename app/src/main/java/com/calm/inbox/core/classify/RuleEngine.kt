package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity

data class RuleResult(
    val category: Category,
    val importance: Int
)

class RuleEngine(
    private val packageMap: Map<String, Category> = DEFAULT_PACKAGE_MAP
) {

    fun classify(item: NotificationEntity): RuleResult? =
        packageMap[item.packageName]?.let { RuleResult(it, importanceOf(it)) }

    companion object {
        val DEFAULT_PACKAGE_MAP: Map<String, Category> = mapOf(
            "com.eg.android.AlipayGphone" to Category.FINANCE,
            "com.tencent.mm" to Category.SOCIAL,
            "com.tencent.mobileqq" to Category.SOCIAL,
            "com.alibaba.android.rimet" to Category.WORK,
            "com.taobao.taobao" to Category.SHOPPING,
            "com.tmall.wireless" to Category.SHOPPING,
            "com.jd.jrapp" to Category.SHOPPING,
            "com.jingdong.app.mall" to Category.SHOPPING,
            "com.xunmeng.pinduoduo" to Category.SHOPPING,
            "com.sankuai.meituan" to Category.SHOPPING,
            "com.sankuai.meituan.takeoutnew" to Category.SHOPPING,
            "com.dianping.so33" to Category.SHOPPING,
            "me.ele" to Category.SHOPPING,
            "com.sfbest.sunline" to Category.EXPRESS,
            "com.sf.sfww" to Category.EXPRESS,
            "com.zto.express" to Category.EXPRESS,
            "com.ytoexpress.package" to Category.EXPRESS,
            "com.sto.express" to Category.EXPRESS,
            "com.yunda.express" to Category.EXPRESS,
            "cn.jiguang.dailydeals" to Category.MARKETING,
            "com.ss.android.ugc.aweme" to Category.SOCIAL,
            "com.smile.gifmaker" to Category.SOCIAL,
            "com.tencent.qqlive" to Category.SOCIAL,
            "com.netease.cloudmusic" to Category.SOCIAL,
            "com.weico.international" to Category.SOCIAL,
            "com.baidu.BaiduMap" to Category.SYSTEM,
            "com.autonavi.minimap" to Category.SYSTEM,
            "com.UCMobile" to Category.SYSTEM,
            "com.quark.browser" to Category.SYSTEM,
            "com.icbc" to Category.FINANCE,
            "com.chinamworld.main" to Category.FINANCE,
            "cmb.pb" to Category.FINANCE,
            "com.cmbchina.ccd.pluto.cmbActivity" to Category.FINANCE,
            "com.android.bankcomm" to Category.FINANCE,
            "com.android.chrome" to Category.SYSTEM,
            "com.miui.securitycenter" to Category.SYSTEM
        )

        fun importanceOf(category: Category): Int = when (category) {
            Category.VERIFICATION -> 5
            Category.EXPRESS, Category.FINANCE, Category.WORK -> 4
            Category.SOCIAL, Category.SHOPPING, Category.SYSTEM, Category.OTHER -> 2
            Category.MARKETING -> 1
            Category.UNCATEGORIZED -> 0
        }
    }
}
