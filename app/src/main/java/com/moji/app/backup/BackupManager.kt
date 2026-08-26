package com.moji.app.backup

import android.content.ContentResolver
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.moji.app.data.BudgetEntity
import com.moji.app.data.CategoryEntity
import com.moji.app.data.MerchantRuleEntity
import com.moji.app.data.MojiRepository
import com.moji.app.data.RefundLinkEntity
import com.moji.app.data.TransactionEntity
import com.moji.app.settings.MojiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val repository: MojiRepository, private val settings: MojiSettings) {
    suspend fun writeBackup(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val transactionCount = repository.transactionCountForBackup()
        require(transactionCount <= MAX_TRANSACTIONS) { "账单数量超过备份上限" }
        val categories = repository.categoriesNow()
        val budgets = repository.budgetsNow()
        val rules = repository.rulesNow()
        val refunds = repository.refundsNow()
        val userSettings = settings.backupValues()
        validateCollectionSizes(categories.size, budgets.size, rules.size, refunds.size)
        withTempFile("moji-backup-data", ".json") { dataFile ->
            val digest = MessageDigest.getInstance("SHA-256")
            dataFile.outputStream().buffered().use { rawOutput ->
                val digestOutput = java.security.DigestOutputStream(rawOutput, digest)
                JsonWriter(OutputStreamWriter(digestOutput, Charsets.UTF_8)).use { writer ->
                    writer.beginObject()
                    writer.name("transactions").beginArray()
                    var offset = 0
                    while (offset < transactionCount) {
                        val batch = repository.transactionBatchForBackup(BACKUP_BATCH_SIZE, offset)
                        if (batch.isEmpty()) break
                        batch.forEach { writer.writeJsonObject(transactionJson(it)) }
                        offset += batch.size
                    }
                    require(offset == transactionCount) { "备份期间账单数量发生变化，请重试" }
                    writer.endArray()
                    writer.name("categories").beginArray(); categories.forEach { writer.writeJsonObject(categoryJson(it)) }; writer.endArray()
                    writer.name("budgets").beginArray(); budgets.forEach { writer.writeJsonObject(budgetJson(it)) }; writer.endArray()
                    writer.name("rules").beginArray(); rules.forEach { writer.writeJsonObject(ruleJson(it)) }; writer.endArray()
                    writer.name("refunds").beginArray(); refunds.forEach { writer.writeJsonObject(refundJson(it)) }; writer.endArray()
                    writer.name("settings").writeJsonObject(JSONObject().apply {
                        put("darkTheme", userSettings.darkTheme); put("hideRecents", userSettings.hideRecents)
                    })
                    writer.endObject()
                }
            }
            require(dataFile.length() <= MAX_DATA_BYTES) { "备份数据超过 ${MAX_DATA_BYTES / 1024 / 1024} MB 上限" }
            val manifest = JSONObject().apply {
                put("formatVersion", 1)
                put("schemaVersion", 1)
                put("createdAt", System.currentTimeMillis())
                put("transactionCount", transactionCount)
                put("sha256", digest.digest().toHex())
                put("warning", "未加密的个人财务数据")
            }.toString(2).toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri, "w")?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY)); zip.write(manifest); zip.closeEntry()
                    zip.putNextEntry(ZipEntry(DATA_ENTRY)); dataFile.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
                }
            } ?: error("无法打开备份目标")
        }
    }

    suspend fun restoreBackup(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        withTempFile("moji-restore-data", ".json") { dataFile ->
            var manifestBytes: ByteArray? = null
            var entryCount = 0
            val seen = mutableSetOf<String>()
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryCount += 1
                        require(entryCount <= MAX_ZIP_ENTRIES) { "备份条目过多" }
                        validateBackupEntry(entry.name, entry.isDirectory, seen)
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifestBytes = ByteArrayOutputStream().also {
                                copyBounded(zip, it, MAX_MANIFEST_BYTES)
                            }.toByteArray()
                            DATA_ENTRY -> dataFile.outputStream().buffered().use {
                                copyBounded(zip, it, MAX_DATA_BYTES)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("无法读取备份")
            require(seen == ALLOWED_ENTRIES) { "备份缺少必要条目" }
            val manifest = JSONObject((manifestBytes ?: error("缺少清单")).toString(Charsets.UTF_8))
            require(manifest.getInt("formatVersion") == 1) { "不支持的备份版本" }
            require(manifest.getString("sha256") == sha256(dataFile)) { "备份校验失败" }
            val restored = JsonReader(dataFile.reader(Charsets.UTF_8).buffered()).use(::readBackupData)
            require(restored.transactions.size == manifest.getInt("transactionCount")) { "备份记录数不一致" }
            repository.replaceBackup(
                restored.transactions, restored.categories, restored.budgets, restored.rules, restored.refunds
            )
            restored.settings?.let {
                settings.restoreAppearance(it.optBoolean("darkTheme", false), it.optBoolean("hideRecents", false))
            }
        }
    }

    suspend fun writeCsv(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = TimeZone.getDefault() }
        val categories = repository.categoriesNow().associateBy { it.id }
        resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write('\uFEFF'.code)
            writer.appendLine("交易ID,日期时间,方向,金额,商户,分类,平台,来源,状态,计入统计,备注")
            val total = repository.transactionCountForBackup()
            var offset = 0
            while (offset < total) {
                val batch = repository.transactionBatchForBackup(BACKUP_BATCH_SIZE, offset)
                if (batch.isEmpty()) break
                batch.filter { it.deletedAt == null }.forEach { tx ->
                    val cells = listOf(
                        tx.id, date.format(Date(tx.occurredAt)), tx.direction,
                        "%.2f".format(Locale.ROOT, tx.amountMinor / 100.0), tx.merchantRaw.orEmpty(),
                        categories[tx.categoryId]?.name.orEmpty(), tx.platform, tx.source, tx.status,
                        tx.includeInStats.toString(), tx.note.orEmpty()
                    )
                    writer.appendLine(cells.joinToString(",") { csvCell(it) })
                }
                offset += batch.size
            }
        } ?: error("无法打开导出目标")
    }

    suspend fun writeXlsx(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val categories = repository.categoriesNow().associateBy { it.id }
        val headers = listOf("交易ID","日期时间","方向","金额","商户","分类","平台","来源","状态","计入统计","备注")
        resolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name:String, value:String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
                entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="默迹账单" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
                entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/><numFmt numFmtId="165" formatCode="0.00"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165" applyNumberFormat="1"/></cellXfs></styleSheet>""")
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                writer.append("<row r=\"1\">")
                headers.forEachIndexed { index, value -> writer.append(inlineCell(index, 1, value)) }
                writer.append("</row>")
                val total = repository.transactionCountForBackup()
                var offset = 0
                var row = 1
                while (offset < total) {
                    val batch = repository.transactionBatchForBackup(BACKUP_BATCH_SIZE, offset)
                    if (batch.isEmpty()) break
                    batch.filter { it.deletedAt == null }.forEach { tx ->
                        row += 1
                        writer.append("<row r=\"$row\">")
                        writer.append(inlineCell(0,row,tx.id))
                        writer.append(numberCell(1,row,excelDate(tx.occurredAt),1))
                        writer.append(inlineCell(2,row,tx.direction))
                        writer.append(numberCell(3,row,tx.amountMinor / 100.0,2))
                        writer.append(inlineCell(4,row,tx.merchantRaw.orEmpty()))
                        writer.append(inlineCell(5,row,categories[tx.categoryId]?.name.orEmpty()))
                        writer.append(inlineCell(6,row,tx.platform)); writer.append(inlineCell(7,row,tx.source)); writer.append(inlineCell(8,row,tx.status))
                        writer.append(inlineCell(9,row,if(tx.includeInStats) "是" else "否")); writer.append(inlineCell(10,row,tx.note.orEmpty()))
                        writer.append("</row>")
                    }
                    offset += batch.size
                }
                writer.append("</sheetData><autoFilter ref=\"A1:K$row\"/></worksheet>")
                writer.flush()
                zip.closeEntry()
            }
        } ?: error("无法打开导出目标")
    }

    private data class RestoredData(
        val transactions: List<TransactionEntity>,
        val categories: List<CategoryEntity>,
        val budgets: List<BudgetEntity>,
        val rules: List<MerchantRuleEntity>,
        val refunds: List<RefundLinkEntity>,
        val settings: JSONObject?
    )

    private fun readBackupData(reader: JsonReader): RestoredData {
        var transactions: List<TransactionEntity>? = null
        var categories: List<CategoryEntity>? = null
        var budgets: List<BudgetEntity>? = null
        var rules: List<MerchantRuleEntity>? = null
        var refunds: List<RefundLinkEntity>? = null
        var restoredSettings: JSONObject? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "transactions" -> transactions = reader.readObjectArray(MAX_TRANSACTIONS, ::parseTransaction)
                "categories" -> categories = reader.readObjectArray(MAX_CATEGORIES, ::parseCategory)
                "budgets" -> budgets = reader.readObjectArray(MAX_BUDGETS, ::parseBudget)
                "rules" -> rules = reader.readObjectArray(MAX_RULES, ::parseRule)
                "refunds" -> refunds = reader.readObjectArray(MAX_REFUNDS, ::parseRefund)
                "settings" -> restoredSettings = reader.readFlatObject()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "备份数据包含多余内容" }
        return RestoredData(
            transactions ?: error("缺少账单数据"),
            categories ?: error("缺少分类数据"),
            budgets ?: error("缺少预算数据"),
            rules ?: error("缺少规则数据"),
            refunds ?: error("缺少退款关联数据"),
            restoredSettings
        )
    }

    private fun validateCollectionSizes(categories: Int, budgets: Int, rules: Int, refunds: Int) {
        require(categories <= MAX_CATEGORIES) { "分类数量超过备份上限" }
        require(budgets <= MAX_BUDGETS) { "预算数量超过备份上限" }
        require(rules <= MAX_RULES) { "规则数量超过备份上限" }
        require(refunds <= MAX_REFUNDS) { "退款关联数量超过备份上限" }
    }

    private suspend fun <T> withTempFile(prefix: String, suffix: String, block: suspend (File) -> T): T {
        val file = File.createTempFile(prefix, suffix)
        return try { block(file) } finally { file.delete() }
    }

    private fun transactionJson(v: TransactionEntity) = JSONObject().apply {
        put("id",v.id); put("direction",v.direction); put("amountMinor",v.amountMinor); put("currency",v.currency)
        putNullable("merchantRaw",v.merchantRaw); putNullable("merchantNormalized",v.merchantNormalized); put("categoryId",v.categoryId)
        put("platform",v.platform); put("paymentMethod",v.paymentMethod); put("occurredAt",v.occurredAt); putNullable("capturedAt",v.capturedAt)
        put("source",v.source); put("status",v.status); put("includeInStats",v.includeInStats); putNullable("note",v.note)
        putNullable("confidence",v.confidence); putNullable("parserVersion",v.parserVersion); putNullable("dedupeKey",v.dedupeKey)
        putNullable("orderRefHash",v.orderRefHash); putNullable("deletedAt",v.deletedAt); put("createdAt",v.createdAt); put("updatedAt",v.updatedAt)
    }
    private fun parseTransaction(o: JSONObject) = TransactionEntity(
        id=o.getString("id"), direction=o.getString("direction"), amountMinor=o.getLong("amountMinor"), currency=o.getString("currency"),
        merchantRaw=o.optStringOrNull("merchantRaw"), merchantNormalized=o.optStringOrNull("merchantNormalized"), categoryId=o.getString("categoryId"),
        platform=o.getString("platform"), paymentMethod=o.getString("paymentMethod"), occurredAt=o.getLong("occurredAt"), capturedAt=o.optLongOrNull("capturedAt"),
        source=o.getString("source"), status=o.getString("status"), includeInStats=o.getBoolean("includeInStats"), note=o.optStringOrNull("note"),
        confidence=o.optDoubleOrNull("confidence")?.toFloat(), parserVersion=o.optStringOrNull("parserVersion"), dedupeKey=o.optStringOrNull("dedupeKey"),
        orderRefHash=o.optStringOrNull("orderRefHash"), deletedAt=o.optLongOrNull("deletedAt"), createdAt=o.getLong("createdAt"), updatedAt=o.getLong("updatedAt")
    )
    private fun categoryJson(v: CategoryEntity)=JSONObject().apply{put("id",v.id);putNullable("parentId",v.parentId);put("name",v.name);put("icon",v.icon);put("sortOrder",v.sortOrder);put("origin",v.origin);put("hidden",v.hidden);putNullable("deletedAt",v.deletedAt)}
    private fun parseCategory(o:JSONObject)=CategoryEntity(o.getString("id"),o.optStringOrNull("parentId"),o.getString("name"),o.getString("icon"),o.getInt("sortOrder"),o.getString("origin"),o.getBoolean("hidden"),o.optLongOrNull("deletedAt"))
    private fun budgetJson(v:BudgetEntity)=JSONObject().apply{put("id",v.id);put("periodMonth",v.periodMonth);putNullable("categoryId",v.categoryId);put("limitMinor",v.limitMinor);put("currency",v.currency);put("notified80",v.notified80);put("notified100",v.notified100);put("notified120",v.notified120)}
    private fun parseBudget(o:JSONObject)=BudgetEntity(o.getString("id"),o.getString("periodMonth"),o.optStringOrNull("categoryId"),o.getLong("limitMinor"),o.getString("currency"),o.getBoolean("notified80"),o.getBoolean("notified100"),o.getBoolean("notified120"))
    private fun ruleJson(v:MerchantRuleEntity)=JSONObject().apply{put("id",v.id);put("pattern",v.pattern);put("normalizedBrand",v.normalizedBrand);put("matchType",v.matchType);put("categoryId",v.categoryId);put("origin",v.origin);put("enabled",v.enabled);put("priority",v.priority);putNullable("lastUsedAt",v.lastUsedAt);put("createdAt",v.createdAt);put("updatedAt",v.updatedAt)}
    private fun parseRule(o:JSONObject)=MerchantRuleEntity(o.getString("id"),o.getString("pattern"),o.getString("normalizedBrand"),o.getString("matchType"),o.getString("categoryId"),o.getString("origin"),o.getBoolean("enabled"),o.getInt("priority"),o.optLongOrNull("lastUsedAt"),o.getLong("createdAt"),o.getLong("updatedAt"))
    private fun refundJson(v:RefundLinkEntity)=JSONObject().apply{put("refundTransactionId",v.refundTransactionId);put("originalTransactionId",v.originalTransactionId);put("linkedAmountMinor",v.linkedAmountMinor);put("matchMethod",v.matchMethod);put("matchConfidence",v.matchConfidence.toDouble())}
    private fun parseRefund(o:JSONObject)=RefundLinkEntity(o.getString("refundTransactionId"),o.getString("originalTransactionId"),o.getLong("linkedAmountMinor"),o.getString("matchMethod"),o.getDouble("matchConfidence").toFloat())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
    private fun csvCell(value:String)="\"${value.replace("\"","\"\"")}\""

    private fun inlineCell(column:Int,row:Int,value:String) = "<c r=\"${columnName(column)}$row\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    private fun numberCell(column:Int,row:Int,value:Number,style:Int) = "<c r=\"${columnName(column)}$row\" s=\"$style\"><v>$value</v></c>"
    private fun columnName(index:Int):String { var n=index+1; var result=""; while(n>0){n--;result=('A'.code+n%26).toChar()+result;n/=26};return result }
    private fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun excelDate(epochMillis:Long):Double =
        (epochMillis + TimeZone.getDefault().getOffset(epochMillis)) / 86_400_000.0 + 25_569.0

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATA_ENTRY = "data.json"
        val ALLOWED_ENTRIES = setOf(MANIFEST_ENTRY, DATA_ENTRY)
        const val MAX_ZIP_ENTRIES = 2
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_DATA_BYTES = 64L * 1024 * 1024
        const val MAX_TRANSACTIONS = 100_000
        const val MAX_CATEGORIES = 1_000
        const val MAX_BUDGETS = 10_000
        const val MAX_RULES = 10_000
        const val MAX_REFUNDS = 100_000
        const val BACKUP_BATCH_SIZE = 500
    }
}

private fun JSONObject.putNullable(key:String,value:Any?){put(key,value?:JSONObject.NULL)}
private fun JSONObject.optStringOrNull(key:String)=if(isNull(key)) null else getString(key)
private fun JSONObject.optLongOrNull(key:String)=if(isNull(key)) null else getLong(key)
private fun JSONObject.optDoubleOrNull(key:String)=if(isNull(key)) null else getDouble(key)

internal fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        total += read
        require(total <= maxBytes) { "备份条目超过大小上限" }
        output.write(buffer, 0, read)
    }
}

internal fun validateBackupEntry(name: String, isDirectory: Boolean, seen: MutableSet<String>) {
    require(!isDirectory && name in setOf("manifest.json", "data.json") && seen.add(name)) {
        "备份包含未知或重复条目"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun <T> JsonReader.readObjectArray(limit: Int, parser: (JSONObject) -> T): List<T> {
    val result = ArrayList<T>()
    beginArray()
    while (hasNext()) {
        require(result.size < limit) { "备份记录数量超过上限" }
        result += parser(readFlatObject())
    }
    endArray()
    return result
}

private fun JsonReader.readFlatObject(): JSONObject = JSONObject().also { value ->
    beginObject()
    while (hasNext()) {
        val name = nextName()
        when (peek()) {
            JsonToken.NULL -> { nextNull(); value.put(name, JSONObject.NULL) }
            JsonToken.BOOLEAN -> value.put(name, nextBoolean())
            JsonToken.STRING -> value.put(name, nextString())
            JsonToken.NUMBER -> {
                val number = nextString()
                value.put(name, if (number.contains('.') || number.contains('e', true)) number.toDouble() else number.toLong())
            }
            else -> error("备份字段结构不受支持")
        }
    }
    endObject()
}

private fun JsonWriter.writeJsonObject(objectValue: JSONObject): JsonWriter = apply {
    beginObject()
    val keys = objectValue.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        name(key)
        when (val item = objectValue.get(key)) {
            JSONObject.NULL -> nullValue()
            is Boolean -> value(item)
            is Number -> value(item)
            else -> value(item.toString())
        }
    }
    endObject()
}
