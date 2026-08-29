package com.moji.app.data

import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.moji.app.voice.VoiceDraft
import java.text.Normalizer
import java.util.UUID

class MojiRepository(private val database: MojiDatabase) {
    private val dao = database.dao()

    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val pendingCandidates: Flow<List<TransactionCandidateEntity>> = dao.observePendingCandidates()
    val rules: Flow<List<MerchantRuleEntity>> = dao.observeRules()
    fun transactionsBetween(after: Long, before: Long): Flow<List<TransactionEntity>> =
        dao.observeTransactionsBetween(after, before)

    suspend fun seedCategories() {
        if (dao.categoryCount() > 0) return
        val names = listOf(
            Triple("food_meal", "正餐", "🍜"), Triple("food_takeout", "外卖", "🥡"),
            Triple("food_drink", "咖啡奶茶", "☕"), Triple("food_snack", "零食", "🍪"),
            Triple("transport_public", "公交地铁", "🚇"), Triple("transport_taxi", "打车", "🚕"),
            Triple("shopping_daily", "日用品", "🧴"), Triple("shopping_online", "网购", "📦"),
            Triple("entertainment", "娱乐", "🎮"), Triple("living", "生活", "🏠"),
            Triple("study", "学习", "📚"), Triple("medical", "医疗", "💊"),
            Triple("social", "人情", "🎁"), Triple("income", "收入", "💰"),
            Triple("other", "其他", "◌")
        )
        dao.insertCategories(names.mapIndexed { index, item ->
            CategoryEntity(id = item.first, name = item.second, icon = item.third, sortOrder = index)
        })
    }

    suspend fun saveTransaction(
        existingId: String? = null,
        amountMinor: Long,
        direction: Direction,
        merchant: String?,
        categoryId: String,
        occurredAt: Long,
        note: String?,
        includeInStats: Boolean = true,
        createMerchantRule: Boolean = false,
        source: TransactionSource = TransactionSource.MANUAL
    ) {
        require(amountMinor > 0)
        val existing = existingId?.let { dao.transactionById(it) }
        val now = System.currentTimeMillis()
        val transaction = (existing ?: TransactionEntity(
            amountMinor = amountMinor,
            categoryId = categoryId,
            occurredAt = occurredAt
        )).copy(
            amountMinor = amountMinor,
            direction = direction.name,
            merchantRaw = merchant?.trim()?.takeIf { it.isNotEmpty() },
            merchantNormalized = normalizeMerchant(merchant),
            categoryId = categoryId,
            occurredAt = occurredAt,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            includeInStats = includeInStats,
            source = if (existing == null) source.name else existing.source,
            updatedAt = now
        )
        if (existing == null) dao.insertTransaction(transaction) else dao.updateTransaction(transaction)
        if (createMerchantRule && transaction.merchantNormalized != null) {
            dao.upsertRule(MerchantRuleEntity(
                pattern = transaction.merchantNormalized,
                normalizedBrand = transaction.merchantNormalized,
                matchType = "EXACT",
                categoryId = categoryId
            ))
        }
    }

    /** Multi-bill confirmation stays in memory until the final save, then commits as one Room transaction. */
    suspend fun saveVoiceTransactions(drafts: List<VoiceDraft>) = database.withTransaction {
        require(drafts.isNotEmpty()) { "没有待保存账单" }
        drafts.forEach { draft ->
            val categoryId = draft.categoryIds.singleOrNull() ?: error("请先为每笔账单选择一个分类")
            val amount = draft.amountMinor ?: error("请先补充每笔账单金额")
            saveTransaction(
                amountMinor = amount,
                direction = draft.direction,
                merchant = draft.merchant,
                categoryId = categoryId,
                occurredAt = draft.occurredAt,
                note = draft.note,
                createMerchantRule = false,
                source = TransactionSource.VOICE
            )
        }
    }

    suspend fun softDelete(id: String) = dao.softDelete(id, System.currentTimeMillis())
    suspend fun restore(id: String) = dao.restore(id)
    suspend fun upsertBudget(budget: BudgetEntity) = dao.upsertBudget(budget)
    fun budgets(month: String) = dao.observeBudgets(month)
    suspend fun transactionCountForBackup() = dao.transactionCountForBackup()
    suspend fun transactionBatchForBackup(limit: Int, offset: Int) = dao.transactionBatchForBackup(limit, offset)
    suspend fun transactionById(id: String) = dao.transactionById(id)
    suspend fun categoriesNow() = dao.categoriesNow()
    suspend fun budgetsNow() = dao.allBudgets()
    suspend fun rulesNow() = dao.allRules()
    suspend fun refundsNow() = dao.allRefundLinks()
    suspend fun replaceBackup(
        transactions: List<TransactionEntity>, categories: List<CategoryEntity>, budgets: List<BudgetEntity>,
        rules: List<MerchantRuleEntity>, refunds: List<RefundLinkEntity>
    ) = dao.replaceBackup(transactions, categories, budgets, rules, refunds)
    suspend fun postCandidate(candidate: TransactionCandidateEntity, transaction: TransactionEntity, event: CaptureEventEntity) =
        dao.postCandidate(candidate, transaction, event)
    suspend fun saveCaptureEvent(event: CaptureEventEntity) = dao.insertCaptureEvent(event)
    suspend fun savePending(candidate: TransactionCandidateEntity, event: CaptureEventEntity) {
        val existing = candidate.dedupeKey?.let { dao.pendingCandidateByDedupeKey(it) }
        val effective = existing?.copy(
            direction = candidate.direction ?: existing.direction,
            amountMinor = candidate.amountMinor ?: existing.amountMinor,
            merchant = candidate.merchant ?: existing.merchant,
            confidence = maxOf(existing.confidence, candidate.confidence),
            conflictCode = candidate.conflictCode ?: existing.conflictCode,
            updatedAt = System.currentTimeMillis()
        ) ?: candidate
        dao.upsertCandidate(effective)
        dao.insertCaptureEvent(event.copy(candidateId = effective.id))
    }
    suspend fun confirmCandidate(candidate: TransactionCandidateEntity, direction: Direction, categoryId: String = "other") {
        val amount = candidate.amountMinor ?: return
        val now = System.currentTimeMillis()
        val transaction = TransactionEntity(
            direction = direction.name,
            amountMinor = amount,
            merchantRaw = candidate.merchant,
            merchantNormalized = normalizeMerchant(candidate.merchant),
            categoryId = categoryId,
            platform = candidate.platform,
            occurredAt = candidate.occurredAt,
            capturedAt = now,
            source = TransactionSource.AUTO.name,
            confidence = candidate.confidence,
            dedupeKey = candidate.dedupeKey
        )
        val existing = candidate.dedupeKey?.let { dao.transactionByDedupeKey(it) }
        if (existing == null) dao.insertTransaction(transaction)
        dao.upsertCandidate(candidate.copy(status = CandidateStatus.CORRECTED.name, transactionId = existing?.id ?: transaction.id, updatedAt = now))
    }
    suspend fun ignoreCandidate(id: String) = dao.updateCandidateStatus(id, CandidateStatus.IGNORED.name, "USER_IGNORED")
    suspend fun pruneCaptureEvents(now: Long) = dao.pruneCaptureEvents(now)
    suspend fun pruneTerminalCandidates(before: Long) = dao.pruneTerminalCandidates(before)
    suspend fun categoryForMerchant(merchant: String?): String? {
        val normalized = normalizeMerchant(merchant) ?: return null
        return dao.enabledRules().firstOrNull { rule ->
            when (rule.matchType) {
                "EXACT" -> normalized == rule.pattern
                "KEYWORD", "BRAND" -> normalized.contains(rule.pattern)
                else -> false
            }
        }?.categoryId
    }
    suspend fun recentCaptureMatch(platform: String, direction: String, amountMinor: Long, merchant: String?, occurredAt: Long) =
        dao.recentMatchingTransaction(platform, direction, amountMinor, normalizeMerchant(merchant), occurredAt - 8_000L, occurredAt + 8_000L)
    suspend fun addCategory(name: String, icon: String) {
        val clean = name.trim().take(20)
        require(clean.isNotEmpty())
        dao.upsertCategory(CategoryEntity(
            id = "user_${UUID.randomUUID()}", name = clean, icon = icon.trim().take(4).ifBlank { "◌" },
            sortOrder = 1_000, origin = "USER"
        ))
    }
    suspend fun setCategoryHidden(id: String, hidden: Boolean) = dao.setCategoryHidden(id, hidden)
    suspend fun saveRule(id: String?, pattern: String, matchType: String, categoryId: String) {
        val normalizedPattern = normalizeMerchant(pattern)?.take(80) ?: error("规则内容不能为空")
        require(matchType in setOf("EXACT", "KEYWORD", "BRAND")) { "不支持的匹配方式" }
        val existing = if (id == null) null else dao.ruleById(id)
        val now = System.currentTimeMillis()
        val base = existing ?: MerchantRuleEntity(
            pattern = normalizedPattern,
            normalizedBrand = normalizedPattern,
            matchType = matchType,
            categoryId = categoryId
        )
        dao.upsertRule(
            base.copy(
                pattern = normalizedPattern,
                normalizedBrand = normalizedPattern,
                matchType = matchType,
                categoryId = categoryId,
                enabled = existing?.enabled ?: true,
                updatedAt = now
            )
        )
    }
    suspend fun setRuleEnabled(id: String, enabled: Boolean) = dao.setRuleEnabled(id, enabled)
    suspend fun deleteRule(id: String) = dao.deleteRule(id)
    suspend fun recordRefund(originalId: String, amountMinor: Long) {
        val original = dao.transactionById(originalId) ?: error("原交易不存在")
        require(original.direction == Direction.EXPENSE.name && amountMinor > 0)
        val refund = TransactionEntity(
            direction = Direction.INCOME.name,
            amountMinor = amountMinor,
            merchantRaw = "退款 · ${original.merchantRaw ?: "未知商户"}",
            merchantNormalized = original.merchantNormalized,
            categoryId = original.categoryId,
            platform = original.platform,
            occurredAt = System.currentTimeMillis(),
            source = TransactionSource.MANUAL.name,
            status = TransactionStatus.REFUND.name,
            includeInStats = true,
            note = "关联退款"
        )
        dao.recordRefund(original, refund, RefundLinkEntity(
            refundTransactionId = refund.id,
            originalTransactionId = original.id,
            linkedAmountMinor = amountMinor,
            matchMethod = "USER",
            matchConfidence = 1f
        ))
    }

    companion object {
        fun normalizeMerchant(value: String?): String? = value
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.replace(Regex("[\\s·•_-]+"), "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }
}
