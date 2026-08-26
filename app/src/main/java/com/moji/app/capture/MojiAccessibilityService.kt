package com.moji.app.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moji.app.MojiApplication
import com.moji.app.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

class MojiAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consent = AtomicBoolean(false)
    private lateinit var processor: CaptureProcessor
    private val embeddedSessionStore by lazy { EmbeddedPaymentSessionStore(this) }
    private var settingsJob: Job? = null
    private val pendingSubmissions = mutableMapOf<Int, Job>()
    private val lastEmbeddedWindowScanAt = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = WeakReference(this)
        processor = CaptureProcessor(this)
        val settings = (application as MojiApplication).settings
        settingsJob = serviceScope.launch { settings.values.collect { consent.set(it.captureConsent) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!consent.get()) return
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in CaptureSources.accessibilityPackages) return
        if (CaptureSources.isEmbeddedPaymentHost(packageName)) {
            observeEmbeddedPaymentHost(event, packageName)
            return
        }
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(32)
        collectTexts(root, texts, depth = 0, budget = intArrayOf(160, 2_000))
        if (texts.isEmpty()) {
            serviceScope.launch {
                DebugCaptureSampler.recordEmptyAccessibilityTree(
                    context = this@MojiAccessibilityService,
                    packageName = packageName,
                    eventWindowId = event.windowId,
                    rootWindowId = root.windowId,
                    accessibilityEventType = event.eventType
                )
            }
            return
        }
        if (texts.none { text -> PAYMENT_HINTS.any(text::contains) }) return
        val now = System.currentTimeMillis()
        val snapshot = PaymentSnapshot(
            packageName = packageName,
            texts = texts,
            receivedAt = now,
            source = "ACCESSIBILITY",
            pageInstanceHash = LocalPaymentParser.sha256(
                "$packageName|${root.windowId}|${texts.take(32).joinToString("|")}"
            ),
            eventWindowId = event.windowId,
            rootWindowId = root.windowId,
            accessibilityEventType = event.eventType
        )
        pendingSubmissions.remove(root.windowId)?.cancel()
        pendingSubmissions[root.windowId] = serviceScope.launch {
            delay(PAGE_SETTLE_DELAY_MS)
            pendingSubmissions.remove(root.windowId)
            processor.submit(snapshot)
        }
    }

    private fun observeEmbeddedPaymentHost(event: AccessibilityEvent, packageName: String) {
        val root = rootInActiveWindow
        val receivedAt = System.currentTimeMillis()
        val eventWindowId = event.windowId
        val rootWindowId = root?.windowId
        val eventType = event.eventType
        val eventClassName = event.className?.toString()
        val texts = ArrayList<String>(64)
        val sharedBudget = intArrayOf(240, 3_000)
        event.text.mapNotNullTo(texts) { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        event.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
        event.source?.let { collectTexts(it, texts, depth = 0, budget = sharedBudget) }
        if (root != null && root != event.source) {
            collectTexts(root, texts, depth = 0, budget = sharedBudget)
        }
        var scannedWindowCount = 0
        var matchingWindowCount = 0
        val shouldScanWindows = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            receivedAt - (lastEmbeddedWindowScanAt[packageName] ?: 0L) >= EMBEDDED_WINDOW_SCAN_INTERVAL_MS
        if (shouldScanWindows && sharedBudget[0] > 0 && sharedBudget[1] > 0) {
            lastEmbeddedWindowScanAt[packageName] = receivedAt
            windows.take(MAX_INTERACTIVE_WINDOWS).forEach { window ->
                scannedWindowCount += 1
                val windowRoot = window.root ?: return@forEach
                val windowPackage = windowRoot.packageName?.toString()
                if (windowPackage != packageName && windowPackage != CaptureSources.ALIPAY_PACKAGE) return@forEach
                matchingWindowCount += 1
                collectTexts(windowRoot, texts, depth = 0, budget = sharedBudget)
            }
        }
        val distinctTexts = texts.distinct().take(64)
        val hasPaymentHint = hasEmbeddedCheckoutEvidence(distinctTexts)
        EmbeddedPaymentSessionTracker.observeHost(
            packageName = packageName,
            observedAt = receivedAt,
            strongCheckoutEvidence = hasPaymentHint
        )
        if (hasPaymentHint) {
            embeddedSessionStore.save(EmbeddedPaymentSession(packageName, receivedAt, true))
        }
        val windowKey = eventWindowId
        pendingSubmissions.remove(windowKey)?.cancel()
        pendingSubmissions[windowKey] = serviceScope.launch {
            delay(EMBEDDED_OBSERVATION_SETTLE_DELAY_MS)
            pendingSubmissions.remove(windowKey)
            DebugCaptureSampler.recordSourceObservation(
                context = this@MojiAccessibilityService,
                packageName = packageName,
                source = "ACCESSIBILITY",
                texts = distinctTexts,
                eventWindowId = eventWindowId,
                rootWindowId = rootWindowId,
                accessibilityEventType = eventType,
                eventClassName = eventClassName,
                scannedWindowCount = scannedWindowCount,
                matchingWindowCount = matchingWindowCount,
                resultCode = when {
                    root == null -> "EMBEDDED_HOST_ROOT_UNAVAILABLE"
                    distinctTexts.isEmpty() -> "EMBEDDED_HOST_EMPTY_TREE"
                    hasPaymentHint -> "EMBEDDED_PAYMENT_HINT_OBSERVED"
                    else -> "EMBEDDED_HOST_EVENT_NO_HINT"
                }
            )
            if (hasPaymentHint) {
                processor.submit(
                    PaymentSnapshot(
                        packageName = packageName,
                        texts = distinctTexts,
                        receivedAt = receivedAt,
                        source = "ACCESSIBILITY",
                        pageInstanceHash = LocalPaymentParser.sha256(
                            "$packageName|$rootWindowId|${distinctTexts.take(32).joinToString("|")}"
                        ),
                        eventWindowId = eventWindowId,
                        rootWindowId = rootWindowId,
                        accessibilityEventType = eventType
                    )
                )
            }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, output: MutableList<String>, depth: Int, budget: IntArray) {
        if (depth > 12 || budget[0]-- <= 0 || budget[1] <= 0) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotEmpty()) {
            val safe = value.take(budget[1].coerceAtMost(160))
            output += safe
            budget[1] -= safe.length
        }
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (description.isNotEmpty() && description != value && budget[1] > 0) {
            val safe = description.take(budget[1].coerceAtMost(160))
            output += safe
            budget[1] -= safe.length
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectTexts(child, output, depth + 1, budget)
        }
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() {
        CaptureFeedback.dismissFor(this)
        if (connectedService?.get() === this) connectedService = null
        settingsJob?.cancel()
        pendingSubmissions.values.forEach(Job::cancel)
        pendingSubmissions.clear()
        lastEmbeddedWindowScanAt.clear()
        if (::processor.isInitialized) processor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var connectedService: WeakReference<MojiAccessibilityService>? = null

        fun connectedInstance(): MojiAccessibilityService? = connectedService?.get()

        private val PAYMENT_HINTS = listOf("支付", "付款", "收款", "交易", "退款", "转账")
        private const val PAGE_SETTLE_DELAY_MS = 650L
        private const val EMBEDDED_OBSERVATION_SETTLE_DELAY_MS = 350L
        private const val EMBEDDED_WINDOW_SCAN_INTERVAL_MS = 500L
        private const val MAX_INTERACTIVE_WINDOWS = 12
    }
}
