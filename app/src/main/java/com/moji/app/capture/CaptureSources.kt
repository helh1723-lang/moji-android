package com.moji.app.capture

import com.moji.app.data.Platform

internal enum class CaptureApp(val code: String, val platform: Platform, val embeddedPaymentHost: Boolean) {
    WECHAT("WECHAT", Platform.WECHAT, false),
    ALIPAY("ALIPAY", Platform.ALIPAY, false),
    TAOBAO("TAOBAO", Platform.ALIPAY, true),
    IDLEFISH("IDLEFISH", Platform.ALIPAY, true),
    UNKNOWN("UNKNOWN", Platform.OTHER, false)
}

internal object CaptureSources {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    const val TAOBAO_PACKAGE = "com.taobao.taobao"
    const val IDLEFISH_PACKAGE = "com.taobao.idlefish"

    private val byPackage = mapOf(
        WECHAT_PACKAGE to CaptureApp.WECHAT,
        ALIPAY_PACKAGE to CaptureApp.ALIPAY,
        TAOBAO_PACKAGE to CaptureApp.TAOBAO,
        IDLEFISH_PACKAGE to CaptureApp.IDLEFISH
    )

    val accessibilityPackages: Set<String> = byPackage.keys
    val notificationPackages: Set<String> = byPackage.keys

    fun appFor(packageName: String): CaptureApp = byPackage[packageName] ?: CaptureApp.UNKNOWN
    fun isDirectPaymentPackage(packageName: String): Boolean = appFor(packageName).let {
        it == CaptureApp.WECHAT || it == CaptureApp.ALIPAY
    }
    fun isEmbeddedPaymentHost(packageName: String): Boolean = appFor(packageName).embeddedPaymentHost
}

internal data class RedactedTextFeatures(
    val textCount: Int,
    val textShape: List<String>,
    val hasCurrencySymbol: Boolean,
    val hasDecimalAmountShape: Boolean,
    val hasYuanSuffixAmountShape: Boolean,
    val numericTokenCount: Int
)

internal fun redactedTextFeatures(texts: List<String>, maxFeatures: Int = 64): RedactedTextFeatures {
    val normalized = texts.map(String::trim).filter(String::isNotEmpty)
    return RedactedTextFeatures(
        textCount = normalized.size,
        textShape = normalized.take(maxFeatures).map(::redactText),
        hasCurrencySymbol = normalized.any(CURRENCY_SYMBOL::containsMatchIn),
        hasDecimalAmountShape = normalized.any(DECIMAL_AMOUNT::containsMatchIn),
        hasYuanSuffixAmountShape = normalized.any(YUAN_SUFFIX_AMOUNT::containsMatchIn),
        numericTokenCount = normalized.sumOf { NUMERIC_TOKEN.findAll(it).count() }
    )
}

private fun redactText(raw: String): String {
    val text = raw.trim()
    val keywords = SAFE_KEYWORDS.filter(text::contains)
    val shapes = buildList {
        if (keywords.isNotEmpty()) add("KEYWORD:${keywords.joinToString("+")}")
        if (CURRENCY_SYMBOL.containsMatchIn(text)) add("CURRENCY")
        if (DECIMAL_AMOUNT.containsMatchIn(text)) add("DECIMAL")
        if (YUAN_SUFFIX_AMOUNT.containsMatchIn(text)) add("YUAN_SUFFIX")
    }
    if (shapes.isNotEmpty()) return shapes.joinToString("|")
    if (text.any(Char::isDigit) && text.none(Char::isLetter)) return "NUMERIC:${text.length}"
    return "TEXT:${text.length}:${LocalPaymentParser.sha256(text).take(10)}"
}

private val CURRENCY_SYMBOL = Regex("[¥￥]")
private val DECIMAL_AMOUNT = Regex("(?<![0-9])[0-9]{1,9}\\.[0-9]{1,2}(?![0-9])")
private val YUAN_SUFFIX_AMOUNT = Regex("(?<![0-9])[0-9]{1,9}(?:\\.[0-9]{1,2})?\\s*元")
private val NUMERIC_TOKEN = Regex("[0-9]+(?:\\.[0-9]+)?")
private val SAFE_KEYWORDS = listOf(
    "支付成功", "付款成功", "交易成功", "支付失败", "付款失败", "退款成功", "已退款",
    "支付宝", "收银台", "确认付款", "立即付款", "支付", "付款", "交易", "退款", "转账",
    "返回商家", "完成", "关闭"
)
