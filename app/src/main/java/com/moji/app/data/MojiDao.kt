package com.moji.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MojiDao {
    @Query("""SELECT * FROM transactions WHERE deletedAt IS NULL
        AND occurredAt BETWEEN :after AND :before ORDER BY occurredAt DESC""")
    fun observeTransactionsBetween(after: Long, before: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun transactionCountForBackup(): Int

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC LIMIT :limit OFFSET :offset")
    suspend fun transactionBatchForBackup(limit: Int, offset: Int): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun transactionById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedupeKey = :key AND deletedAt IS NULL LIMIT 1")
    suspend fun transactionByDedupeKey(key: String): TransactionEntity?

    @Query("""SELECT * FROM transactions WHERE deletedAt IS NULL AND platform = :platform AND direction = :direction
        AND amountMinor = :amountMinor AND merchantNormalized IS :merchant AND occurredAt BETWEEN :after AND :before
        ORDER BY occurredAt DESC LIMIT 1""")
    suspend fun recentMatchingTransaction(
        platform: String, direction: String, amountMinor: Long, merchant: String?, after: Long, before: Long
    ): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Transaction
    suspend fun insertTransactionsAtomically(transactions: List<TransactionEntity>) {
        for (transaction in transactions) insertTransaction(transaction)
    }

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deletedAt = :deletedAt, status = 'DELETED', updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE transactions SET deletedAt = NULL, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, status: String = "ACTIVE", now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun categoriesNow(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Query("UPDATE categories SET hidden = :hidden WHERE id = :id")
    suspend fun setCategoryHidden(id: String, hidden: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceTransactions(transactions: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidate(candidate: TransactionCandidateEntity)

    @Query("SELECT * FROM transaction_candidates WHERE dedupeKey = :key AND status = 'PENDING_REVIEW' ORDER BY occurredAt DESC LIMIT 1")
    suspend fun pendingCandidateByDedupeKey(key: String): TransactionCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaptureEvent(event: CaptureEventEntity)

    @Query("SELECT * FROM transaction_candidates WHERE status = 'PENDING_REVIEW' ORDER BY occurredAt DESC")
    fun observePendingCandidates(): Flow<List<TransactionCandidateEntity>>

    @Query("UPDATE transaction_candidates SET status = :status, ignoredReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun updateCandidateStatus(id: String, status: String, reason: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM budgets WHERE periodMonth = :month ORDER BY categoryId")
    fun observeBudgets(month: String): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: BudgetEntity)

    @Query("SELECT * FROM merchant_rules WHERE enabled = 1 ORDER BY priority DESC, updatedAt DESC")
    suspend fun enabledRules(): List<MerchantRuleEntity>

    @Query("SELECT * FROM merchant_rules ORDER BY enabled DESC, priority DESC, updatedAt DESC")
    fun observeRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules WHERE id = :id LIMIT 1")
    suspend fun ruleById(id: String): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MerchantRuleEntity)

    @Query("UPDATE merchant_rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setRuleEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT * FROM budgets")
    suspend fun allBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM merchant_rules")
    suspend fun allRules(): List<MerchantRuleEntity>

    @Query("SELECT * FROM refund_links")
    suspend fun allRefundLinks(): List<RefundLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceBudgets(values: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceRules(values: List<MerchantRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceRefundLinks(values: List<RefundLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRefundLink(value: RefundLinkEntity)

    @Query("SELECT COALESCE(SUM(linkedAmountMinor), 0) FROM refund_links WHERE originalTransactionId = :originalId")
    suspend fun refundedAmount(originalId: String): Long

    @Transaction
    suspend fun recordRefund(original: TransactionEntity, refund: TransactionEntity, link: RefundLinkEntity) {
        val already = refundedAmount(original.id)
        require(already + link.linkedAmountMinor <= original.amountMinor)
        insertTransaction(refund)
        insertRefundLink(link)
        updateTransaction(original.copy(
            status = if (already + link.linkedAmountMinor == original.amountMinor) "FULLY_REFUNDED" else "PARTIALLY_REFUNDED",
            updatedAt = System.currentTimeMillis()
        ))
    }

    @Query("DELETE FROM capture_events WHERE retentionUntil < :now")
    suspend fun pruneCaptureEvents(now: Long)

    @Query("""DELETE FROM transaction_candidates
        WHERE status NOT IN ('PENDING_REVIEW', 'DETECTED', 'COLLECTING') AND updatedAt < :before""")
    suspend fun pruneTerminalCandidates(before: Long)

    @Transaction
    suspend fun postCandidate(candidate: TransactionCandidateEntity, transaction: TransactionEntity, event: CaptureEventEntity) {
        val existing = transaction.dedupeKey?.let { transactionByDedupeKey(it) }
        if (existing == null) insertTransaction(transaction)
        upsertCandidate(candidate.copy(
            status = if (existing == null) CandidateStatus.AUTO_POSTED.name else CandidateStatus.DUPLICATE_SUPPRESSED.name,
            transactionId = existing?.id ?: transaction.id
        ))
        insertCaptureEvent(event.copy(candidateId = candidate.id))
    }

    @Transaction
    suspend fun replaceBackup(
        transactions: List<TransactionEntity>, categories: List<CategoryEntity>,
        budgets: List<BudgetEntity>, rules: List<MerchantRuleEntity>, refunds: List<RefundLinkEntity>
    ) {
        clearRefunds(); clearTransactions(); clearCategories(); clearBudgets(); clearRules()
        replaceCategories(categories)
        replaceTransactions(transactions)
        replaceBudgets(budgets)
        replaceRules(rules)
        replaceRefundLinks(refunds)
    }

    @Query("DELETE FROM refund_links") suspend fun clearRefunds()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM merchant_rules") suspend fun clearRules()
}
