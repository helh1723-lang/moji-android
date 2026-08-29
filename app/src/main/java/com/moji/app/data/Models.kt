package com.moji.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class Direction { EXPENSE, INCOME, TRANSFER }
enum class Platform { WECHAT, ALIPAY, MANUAL, IMPORT, OTHER }
enum class TransactionSource { AUTO, MANUAL, VOICE, IMPORT }
enum class TransactionStatus { ACTIVE, PENDING_REVIEW, PARTIALLY_REFUNDED, FULLY_REFUNDED, REFUND, DELETED }
enum class CandidateStatus { DETECTED, COLLECTING, PARSED, DUPLICATE_SUPPRESSED, AUTO_POSTED, PENDING_REVIEW, PARSE_FAILED, PAYMENT_FAILED, IGNORED, CORRECTED, FINALIZED }

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["occurredAt"]),
        Index(value = ["categoryId", "occurredAt"]),
        Index(value = ["merchantNormalized"])
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val direction: String = Direction.EXPENSE.name,
    val amountMinor: Long,
    val currency: String = "CNY",
    val merchantRaw: String? = null,
    val merchantNormalized: String? = null,
    val categoryId: String,
    val platform: String = Platform.MANUAL.name,
    val paymentMethod: String = "UNKNOWN",
    val occurredAt: Long,
    val capturedAt: Long? = null,
    val source: String = TransactionSource.MANUAL.name,
    val status: String = TransactionStatus.ACTIVE.name,
    val includeInStats: Boolean = true,
    val note: String? = null,
    val confidence: Float? = null,
    val parserVersion: String? = null,
    val dedupeKey: String? = null,
    val orderRefHash: String? = null,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories", indices = [Index(value = ["parentId"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val icon: String,
    val sortOrder: Int,
    val origin: String = "SYSTEM",
    val hidden: Boolean = false,
    val deletedAt: Long? = null
)

@Entity(tableName = "capture_events", indices = [Index(value = ["receivedAt"]), Index(value = ["candidateId"])])
data class CaptureEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val platform: String,
    val eventType: String,
    val pageType: String,
    val receivedAt: Long,
    val parserVersion: String,
    val resultCode: String,
    val candidateId: String? = null,
    val contentHash: String? = null,
    val pageInstanceHash: String? = null,
    val retentionUntil: Long
)

@Entity(tableName = "transaction_candidates", indices = [Index(value = ["dedupeKey"]), Index(value = ["status"])])
data class TransactionCandidateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val platform: String,
    val direction: String?,
    val amountMinor: Long?,
    val merchant: String?,
    val occurredAt: Long,
    val confidence: Float,
    val conflictCode: String? = null,
    val status: String,
    val dedupeKey: String?,
    val transactionId: String? = null,
    val ignoredReason: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "refund_links",
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["refundTransactionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["originalTransactionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["refundTransactionId"], unique = true), Index(value = ["originalTransactionId"])]
)
data class RefundLinkEntity(
    @PrimaryKey val refundTransactionId: String,
    val originalTransactionId: String,
    val linkedAmountMinor: Long,
    val matchMethod: String,
    val matchConfidence: Float
)

@Entity(tableName = "merchant_rules", indices = [Index(value = ["priority"])])
data class MerchantRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    val normalizedBrand: String,
    val matchType: String,
    val categoryId: String,
    val origin: String = "USER",
    val enabled: Boolean = true,
    val priority: Int = 100,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "budgets", indices = [Index(value = ["periodMonth", "categoryId"], unique = true)])
data class BudgetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val periodMonth: String,
    val categoryId: String? = null,
    val limitMinor: Long,
    val currency: String = "CNY",
    val notified80: Boolean = false,
    val notified100: Boolean = false,
    val notified120: Boolean = false
)

data class TransactionWithCategory(
    val transaction: TransactionEntity,
    val category: CategoryEntity?
)

data class LedgerSummary(
    val monthExpenseMinor: Long = 0,
    val monthIncomeMinor: Long = 0,
    val todayExpenseMinor: Long = 0
)
