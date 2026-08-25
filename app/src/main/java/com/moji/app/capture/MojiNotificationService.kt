package com.moji.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.moji.app.MojiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MojiNotificationService : NotificationListenerService() {
    private lateinit var processor: CaptureProcessor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val consent = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        processor = CaptureProcessor(this)
        serviceScope.launch { (application as MojiApplication).settings.values.collect { consent.set(it.captureConsent) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!consent.get()) return
        val item = sbn ?: return
        if (item.packageName !in ALLOWED_PACKAGES) return
        val extras = item.notification.extras
        val texts = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ).map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.none { text -> PAYMENT_HINTS.any(text::contains) }) return
        val now = System.currentTimeMillis()
        processor.submit(
            PaymentSnapshot(
                packageName = item.packageName,
                texts = texts,
                receivedAt = item.postTime.takeIf { it > 0 } ?: now,
                source = "NOTIFICATION",
                pageInstanceHash = LocalPaymentParser.sha256("${item.packageName}|${item.key}|${texts.joinToString("|")}")
            )
        )
    }

    override fun onDestroy() {
        if (::processor.isInitialized) processor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val ALLOWED_PACKAGES = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
        private val PAYMENT_HINTS = listOf("支付", "付款", "收款", "交易", "退款", "转账")
    }
}
