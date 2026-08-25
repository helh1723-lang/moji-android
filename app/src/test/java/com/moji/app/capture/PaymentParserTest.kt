package com.moji.app.capture

import com.moji.app.data.CandidateStatus
import com.moji.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentParserTest {
    @Test
    fun quickEditAmountAcceptsTwoDecimalsAndRejectsInvalidValues() {
        assertEquals(20L, parseQuickAmountMinor("0.20"))
        assertEquals(1680L, parseQuickAmountMinor("16.8"))
        assertNull(parseQuickAmountMinor("0"))
        assertNull(parseQuickAmountMinor("1.234"))
        assertNull(parseQuickAmountMinor("abc"))
    }

    private val parser = ParserRegistry()

    @Test fun parsesWechatSuccess() {
        val result = parser.parse(snapshot("com.tencent.mm", listOf("支付成功", "¥16.80", "商户：瑞幸咖啡")))!!
        assertEquals(1680L, result.amountMinor)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals("瑞幸咖啡", result.merchant)
        assertTrue(result.successful)
        assertTrue(result.confidence >= 0.85f)
    }

    @Test fun parsesWechatScreenshotLayoutAndInfersMerchant() {
        val result = parser.parse(snapshot("com.tencent.mm", listOf("支付成功", "拼多多", "¥ 0.20", "返回商家")))!!
        assertTrue(result.paymentRelevant)
        assertTrue(result.successful)
        assertEquals(20L, result.amountMinor)
        assertEquals("拼多多", result.merchant)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals("PARSED", result.resultCode)
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(snapshot("com.tencent.mm", listOf("支付成功", "拼多多", "¥0.20")), result))
    }

    @Test fun recognizesSuccessSplitAcrossAccessibilityNodes() {
        val result = parser.parse(snapshot("com.tencent.mm", listOf("支付", "成功", "拼多多", "¥0.20", "返回商家")))!!
        assertTrue(result.successful)
        assertEquals("拼多多", result.merchant)
    }

    @Test fun ignoresMallPageWithPaymentWordsAndPrice() {
        val input = snapshot("com.tencent.mm", listOf("拼多多", "交易保障", "¥0.20", "去支付"))
        val result = parser.parse(input)!!
        assertFalse(result.paymentRelevant)
        assertEquals("NOT_PAYMENT_RESULT", result.resultCode)
        assertNull(determineCandidateStatus(input, result))
    }

    @Test fun rejectsAmbiguousAmountsOnSuccessPage() {
        val input = snapshot("com.tencent.mm", listOf("支付成功", "拼多多", "商品金额 ¥1.00", "实付 ¥0.20", "返回商家"))
        val result = parser.parse(input)!!
        assertNull(result.amountMinor)
        assertEquals("AMOUNT_CONFLICT", result.resultCode)
        assertEquals(CandidateStatus.PARSE_FAILED, determineCandidateStatus(input, result))
    }

    @Test fun completeNotificationCanFallbackToAutoPosting() {
        val input = PaymentSnapshot(
            "com.tencent.mm",
            listOf("支付成功", "拼多多", "¥0.20"),
            1_700_000_000_000,
            LocalPaymentParser.SOURCE_NOTIFICATION,
            "notification"
        )
        val result = parser.parse(input)!!
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(input, result))
    }

    @Test fun successfulNotificationWithoutMerchantAutoPostsAsUnknownMerchant() {
        val input = PaymentSnapshot(
            "com.tencent.mm",
            listOf("支付成功", "¥0.20"),
            1_700_000_000_000,
            LocalPaymentParser.SOURCE_NOTIFICATION,
            "notification-incomplete"
        )
        val result = parser.parse(input)!!
        assertNull(result.merchant)
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(input, result))
    }

    @Test fun failedPaymentNeverSucceeds() {
        val result = parser.parse(snapshot("com.eg.android.AlipayGphone", listOf("支付失败", "余额不足", "￥20.00")))!!
        assertTrue(result.failed)
        assertFalse(result.successful)
    }

    @Test fun transferDirectionIsPending() {
        val result = parser.parse(snapshot("com.tencent.mm", listOf("转账成功", "￥88.00", "收款方：小王")))!!
        assertNull(result.direction)
    }

    @Test fun unsupportedPackageIgnored() {
        assertNull(parser.parse(snapshot("com.example.other", listOf("支付成功", "￥1.00"))))
    }

    @Test fun recognizesAlibabaEmbeddedPaymentHostsAsAlipayParserSources() {
        assertEquals(CaptureApp.TAOBAO, CaptureSources.appFor("com.taobao.taobao"))
        assertEquals(CaptureApp.IDLEFISH, CaptureSources.appFor("com.taobao.idlefish"))
        assertTrue(CaptureSources.isEmbeddedPaymentHost("com.taobao.taobao"))
        assertTrue(CaptureSources.isEmbeddedPaymentHost("com.taobao.idlefish"))
        assertFalse(CaptureSources.isDirectPaymentPackage("com.taobao.taobao"))
        val checkout = parser.parse(snapshot("com.taobao.taobao", listOf("支付宝", "确认付款", "￥1.00")))!!
        assertFalse(checkout.paymentRelevant)
        assertEquals("NOT_PAYMENT_RESULT", checkout.resultCode)
    }

    @Test fun parsesIdlefishEmbeddedAlipaySuccessWithYuanSuffixAmount() {
        val input = snapshot(
            "com.taobao.idlefish",
            listOf("支付宝支付成功 0.04 元", "付款给", "闲鱼卖家", "完成")
        )
        val result = parser.parse(input)!!
        assertTrue(result.paymentRelevant)
        assertTrue(result.successful)
        assertEquals(4L, result.amountMinor)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(input, result))
    }

    @Test fun taobaoProductAndCheckoutPagesNeverAutoPostWithoutSuccessState() {
        val product = snapshot(
            "com.taobao.taobao",
            listOf("商品详情", "交易保障", "￥0.04", "立即购买")
        )
        val checkout = snapshot(
            "com.taobao.taobao",
            listOf("支付宝", "确认付款", "￥0.04")
        )
        listOf(product, checkout).forEach { input ->
            val result = parser.parse(input)!!
            assertFalse(result.paymentRelevant)
            assertNull(determineCandidateStatus(input, result))
        }
    }

    @Test fun diagnosticTextFeaturesRevealAmountShapesWithoutKeepingRawText() {
        val features = redactedTextFeatures(listOf("支付宝交易成功", "成功支付 12.34 元"))
        assertTrue(features.hasDecimalAmountShape)
        assertTrue(features.hasYuanSuffixAmountShape)
        assertEquals(2, features.textCount)
        assertTrue(features.textShape.any { it.contains("KEYWORD") && it.contains("DECIMAL") })
        assertFalse(features.textShape.any { it.contains("12.34") })
    }

    @Test fun emptyEmbeddedWindowCanBeRepresentedWithoutRawPaymentData() {
        val features = redactedTextFeatures(emptyList())
        assertEquals(0, features.textCount)
        assertTrue(features.textShape.isEmpty())
        assertFalse(features.hasCurrencySymbol)
        assertEquals(0, features.numericTokenCount)
    }

    @Test fun embeddedAlipayNotificationRequiresRecentHostSessionAndStrongPaymentShape() {
        val now = 1_700_000_100_000L
        val notification = listOf("交易提醒", "支付宝支付 0.04 元")
        assertNull(fuseEmbeddedHostWithAlipayNotification(notification, null))

        EmbeddedPaymentSessionTracker.resetForTest()
        EmbeddedPaymentSessionTracker.observeHost("com.taobao.idlefish", now - 2_000L, true)
        val session = EmbeddedPaymentSessionTracker.recentForNotification(now)
        val fused = fuseEmbeddedHostWithAlipayNotification(notification, session)
        assertTrue(fused?.contains("支付成功") == true)
        val parsed = parser.parse(
            PaymentSnapshot("com.taobao.idlefish", fused!!, now, "NOTIFICATION", "fused")
        )!!
        assertEquals(4L, parsed.amountMinor)
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(
            PaymentSnapshot("com.taobao.idlefish", fused, now, "NOTIFICATION", "fused"), parsed
        ))
    }

    @Test fun embeddedNotificationFusionRejectsIncomeRefundFailureAndAmbiguousAmounts() {
        val session = EmbeddedPaymentSession("com.taobao.taobao", 1_000L, true)
        val rejected = listOf(
            listOf("交易提醒", "支付宝收款 0.04 元已到账"),
            listOf("交易提醒", "支付宝退款 0.04 元"),
            listOf("交易提醒", "支付宝支付失败 0.04 元"),
            listOf("交易提醒", "支付宝支付 0.04 元，优惠前 1.00 元")
        )
        rejected.forEach { assertNull(fuseEmbeddedHostWithAlipayNotification(it, session)) }
    }

    @Test fun weakHostWindowSessionExpiresQuicklyButCheckoutSessionSurvivesPaymentFlow() {
        EmbeddedPaymentSessionTracker.resetForTest()
        EmbeddedPaymentSessionTracker.observeHost("com.taobao.taobao", 1_000L, false)
        assertTrue(EmbeddedPaymentSessionTracker.recentForNotification(30_000L) != null)
        assertNull(EmbeddedPaymentSessionTracker.recentForNotification(32_000L))

        EmbeddedPaymentSessionTracker.observeHost("com.taobao.taobao", 50_000L, true)
        assertTrue(EmbeddedPaymentSessionTracker.recentForNotification(349_000L) != null)
        assertNull(EmbeddedPaymentSessionTracker.recentForNotification(351_000L))
    }

    @Test fun twoLegitimateSameAmountNotificationsInsideOneTimeBucketRemainDistinct() {
        val first = PaymentSnapshot(
            packageName = "com.taobao.idlefish",
            texts = listOf("支付成功", "0.10 元"),
            receivedAt = 1_700_000_001_000L,
            source = "NOTIFICATION",
            pageInstanceHash = "page-1",
            sourceEventId = "notification-key@1000"
        )
        val second = first.copy(
            receivedAt = first.receivedAt + 5_000L,
            pageInstanceHash = "page-2",
            sourceEventId = "notification-key@6000"
        )
        val firstParsed = parser.parse(first)!!
        val secondParsed = parser.parse(second)!!
        assertEquals(10L, firstParsed.amountMinor)
        assertEquals(10L, secondParsed.amountMinor)
        assertTrue(firstParsed.dedupeKey != secondParsed.dedupeKey)
    }

    @Test fun repeatedCallbackForSameNotificationKeepsStableDedupeIdentity() {
        val original = PaymentSnapshot(
            packageName = "com.taobao.idlefish",
            texts = listOf("支付成功", "0.10 元"),
            receivedAt = 1_700_000_001_000L,
            source = "NOTIFICATION",
            pageInstanceHash = "page-1",
            sourceEventId = "notification-key@1000"
        )
        val callback = original.copy(receivedAt = original.receivedAt + 2_000L)
        assertEquals(parser.parse(original)!!.dedupeKey, parser.parse(callback)!!.dedupeKey)
    }

    @Test fun notificationIdentityCoalescesCallbacksButSeparatesLaterSameAmountPayment() {
        val tracker = NotificationEventIdentityTracker(callbackWindowMs = 8_000L)
        val first = tracker.resolve("alipay-notification", "same-redacted-payment", 1_000L)
        val callback = tracker.resolve("alipay-notification", "same-redacted-payment", 3_000L)
        val secondPayment = tracker.resolve("alipay-notification", "same-redacted-payment", 20_000L)
        assertEquals(first, callback)
        assertTrue(first != secondPayment)
    }

    @Test fun embeddedCheckoutEvidenceAcceptsPaymentActionWithAmountButNotProductPriceAlone() {
        assertTrue(hasEmbeddedCheckoutEvidence(listOf("付款", "支付 0.10 元")))
        assertTrue(hasEmbeddedCheckoutEvidence(listOf("确认付款", "￥0.10")))
        assertFalse(hasEmbeddedCheckoutEvidence(listOf("商品详情", "￥0.10", "收藏")))
        assertFalse(hasEmbeddedCheckoutEvidence(listOf("支付成功 0.10 元")))
        assertFalse(hasEmbeddedCheckoutEvidence(listOf("付款失败", "￥0.10")))
    }

    @Test fun strongHostSessionAllowsTransactionAmountNotificationWithoutPaymentWord() {
        val notification = listOf("交易提醒", "0.10 元")
        val strong = EmbeddedPaymentSession("com.taobao.taobao", 1_000L, true)
        val weak = strong.copy(strongCheckoutEvidence = false)
        assertNull(fuseEmbeddedHostWithAlipayNotification(notification, weak))
        val fused = fuseEmbeddedHostWithAlipayNotification(notification, strong)
        assertTrue(fused?.contains("支付成功") == true)
        val parsed = parser.parse(
            PaymentSnapshot("com.taobao.taobao", fused!!, 1_000L, "NOTIFICATION", "fused")
        )!!
        assertEquals(10L, parsed.amountMinor)
        assertEquals(CandidateStatus.PARSED, determineCandidateStatus(
            PaymentSnapshot("com.taobao.taobao", fused, 1_000L, "NOTIFICATION", "fused"), parsed
        ))
    }

    private fun snapshot(packageName:String,texts:List<String>) = PaymentSnapshot(packageName,texts,1_700_000_000_000,"ACCESSIBILITY","page")
}
