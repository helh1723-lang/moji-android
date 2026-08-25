package com.moji.app.backup

import android.content.ContentResolver
import android.net.Uri
import com.moji.app.data.BudgetEntity
import com.moji.app.data.CategoryEntity
import com.moji.app.data.MerchantRuleEntity
import com.moji.app.data.MojiRepository
import com.moji.app.data.RefundLinkEntity
import com.moji.app.data.TransactionEntity
import com.moji.app.settings.MojiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        val transactions = repository.allForBackup()
        val categories = repository.categoriesNow()
        val budgets = repository.budgetsNow()
        val rules = repository.rulesNow()
        val refunds = repository.refundsNow()
        val userSettings = settings.backupValues()
        val data = JSONObject().apply {
            put("transactions", JSONArray(transactions.map(::transactionJson)))
            put("categories", JSONArray(categories.map(::categoryJson)))
            put("budgets", JSONArray(budgets.map(::budgetJson)))
            put("rules", JSONArray(rules.map(::ruleJson)))
            put("refunds", JSONArray(refunds.map(::refundJson)))
            put("settings", JSONObject().apply { put("darkTheme", userSettings.darkTheme); put("hideRecents", userSettings.hideRecents) })
        }.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject().apply {
            put("formatVersion", 1)
            put("schemaVersion", 1)
            put("createdAt", System.currentTimeMillis())
            put("transactionCount", transactions.size)
            put("sha256", sha256(data))
            put("warning", "未加密的个人财务数据")
        }.toString(2).toByteArray(Charsets.UTF_8)
        resolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                zip.putNextEntry(ZipEntry("data.json")); zip.write(data); zip.closeEntry()
            }
        } ?: error("无法打开备份目标")
    }

    suspend fun restoreBackup(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val entries = mutableMapOf<String, ByteArray>()
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val bytes = ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
                    entries[entry.name] = bytes
                    entry = zip.nextEntry
                }
            }
        } ?: error("无法读取备份")
        val manifestBytes = entries["manifest.json"] ?: error("缺少清单")
        val dataBytes = entries["data.json"] ?: error("缺少数据")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.getInt("formatVersion") == 1) { "不支持的备份版本" }
        require(manifest.getString("sha256") == sha256(dataBytes)) { "备份校验失败" }
        val data = JSONObject(dataBytes.toString(Charsets.UTF_8))
        val transactions = data.getJSONArray("transactions").mapObjects(::parseTransaction)
        require(transactions.size == manifest.getInt("transactionCount")) { "备份记录数不一致" }
        repository.replaceBackup(
            transactions = transactions,
            categories = data.getJSONArray("categories").mapObjects(::parseCategory),
            budgets = data.getJSONArray("budgets").mapObjects(::parseBudget),
            rules = data.getJSONArray("rules").mapObjects(::parseRule),
            refunds = data.getJSONArray("refunds").mapObjects(::parseRefund)
        )
        data.optJSONObject("settings")?.let {
            settings.restoreAppearance(it.optBoolean("darkTheme", false), it.optBoolean("hideRecents", false))
        }
    }

    suspend fun writeCsv(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = TimeZone.getDefault() }
        val categories = repository.categoriesNow().associateBy { it.id }
        resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write('\uFEFF'.code)
            writer.appendLine("交易ID,日期时间,方向,金额,商户,分类,平台,来源,状态,计入统计,备注")
            repository.allForBackup().filter { it.deletedAt == null }.forEach { tx ->
                val cells = listOf(
                    tx.id, date.format(Date(tx.occurredAt)), tx.direction,
                    "%.2f".format(Locale.ROOT, tx.amountMinor / 100.0), tx.merchantRaw.orEmpty(),
                    categories[tx.categoryId]?.name.orEmpty(), tx.platform, tx.source, tx.status,
                    tx.includeInStats.toString(), tx.note.orEmpty()
                )
                writer.appendLine(cells.joinToString(",") { csvCell(it) })
            }
        } ?: error("无法打开导出目标")
    }

    suspend fun writeXlsx(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val transactions = repository.allForBackup().filter { it.deletedAt == null }
        val categories = repository.categoriesNow().associateBy { it.id }
        val headers = listOf("交易ID","日期时间","方向","金额","商户","分类","平台","来源","状态","计入统计","备注")
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            append("<row r=\"1\">")
            headers.forEachIndexed { index, value -> append(inlineCell(index, 1, value)) }
            append("</row>")
            transactions.forEachIndexed { rowIndex, tx ->
                val row = rowIndex + 2
                append("<row r=\"$row\">")
                append(inlineCell(0,row,tx.id))
                append(numberCell(1,row,excelDate(tx.occurredAt),1))
                append(inlineCell(2,row,tx.direction))
                append(numberCell(3,row,tx.amountMinor / 100.0,2))
                append(inlineCell(4,row,tx.merchantRaw.orEmpty()))
                append(inlineCell(5,row,categories[tx.categoryId]?.name.orEmpty()))
                append(inlineCell(6,row,tx.platform)); append(inlineCell(7,row,tx.source)); append(inlineCell(8,row,tx.status))
                append(inlineCell(9,row,if(tx.includeInStats) "是" else "否")); append(inlineCell(10,row,tx.note.orEmpty()))
                append("</row>")
            }
            append("</sheetData><autoFilter ref=\"A1:K${transactions.size + 1}\"/></worksheet>")
        }
        resolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name:String, value:String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
                entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="默迹账单" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
                entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/><numFmt numFmtId="165" formatCode="0.00"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165" applyNumberFormat="1"/></cellXfs></styleSheet>""")
                entry("xl/worksheets/sheet1.xml", sheet)
            }
        } ?: error("无法打开导出目标")
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

    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun csvCell(value:String)="\"${value.replace("\"","\"\"")}\""

    private fun inlineCell(column:Int,row:Int,value:String) = "<c r=\"${columnName(column)}$row\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    private fun numberCell(column:Int,row:Int,value:Number,style:Int) = "<c r=\"${columnName(column)}$row\" s=\"$style\"><v>$value</v></c>"
    private fun columnName(index:Int):String { var n=index+1; var result=""; while(n>0){n--;result=('A'.code+n%26).toChar()+result;n/=26};return result }
    private fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun excelDate(epochMillis:Long):Double =
        (epochMillis + TimeZone.getDefault().getOffset(epochMillis)) / 86_400_000.0 + 25_569.0
}

private fun JSONObject.putNullable(key:String,value:Any?){put(key,value?:JSONObject.NULL)}
private fun JSONObject.optStringOrNull(key:String)=if(isNull(key)) null else getString(key)
private fun JSONObject.optLongOrNull(key:String)=if(isNull(key)) null else getLong(key)
private fun JSONObject.optDoubleOrNull(key:String)=if(isNull(key)) null else getDouble(key)
private fun <T> JSONArray.mapObjects(block:(JSONObject)->T)=List(length()){block(getJSONObject(it))}
