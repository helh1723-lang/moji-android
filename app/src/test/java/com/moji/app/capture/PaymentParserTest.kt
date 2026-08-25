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

    private fun snapshot(packageName:String,texts:List<String>) = PaymentSnapshot(packageName,texts,1_700_000_000_000,"ACCESSIBILITY","page")
}
