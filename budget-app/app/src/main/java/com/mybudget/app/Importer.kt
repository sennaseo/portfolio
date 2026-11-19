package com.mybudget.app

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToLong

/**
 * 뱅크샐러드 "가계부 내역" 엑셀(.xlsx) 가져오기.
 * 안드로이드 클래스에 의존하지 않는 순수 Kotlin (단위 테스트 대상).
 */
object Importer {
    /** amount 부호는 앱의 Tx와 같다: 지출 양수, 결제 취소 음수, 수입 양수. */
    class Row(val time: Long, val type: Int, val category: String, val merchant: String, val amount: Long)

    class Result(val rows: List<Row>, val skippedTransfers: Int)

    private val REQUIRED = listOf("날짜", "타입", "대분류", "내용", "금액")

    fun read(input: InputStream): Result {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) {
                    files[e.name] = zip.readBytes()
                }
            }
        }
        val strings = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        for (name in files.keys.filter { it.startsWith("xl/worksheets/") }.sorted()) {
            val grid = sheet(files.getValue(name), strings)
            val header = grid.firstOrNull() ?: continue
            if (REQUIRED.all { it in header.values }) return convert(grid)
        }
        throw IllegalArgumentException("가계부 내역 시트를 찾지 못했어요")
    }

    private fun convert(grid: List<Map<Int, String>>): Result {
        val col = grid[0].entries.associate { it.value.trim() to it.key }
        fun cell(row: Map<Int, String>, name: String) = col[name]?.let { row[it] }?.trim() ?: ""
        val out = ArrayList<Row>()
        var transfers = 0
        for (r in grid.drop(1)) {
            val amount = cell(r, "금액").replace(",", "").toDoubleOrNull()?.roundToLong() ?: continue
            if (amount == 0L) continue
            val time = excelTime(cell(r, "날짜"), cell(r, "시간")) ?: continue
            val merchant = cell(r, "내용").take(30)
            val category = cell(r, "대분류")
            when (cell(r, "타입")) {
                "지출" -> out.add(Row(time, Parser.EXPENSE, category, merchant, -amount))
                "수입" -> out.add(Row(time, Parser.INCOME, category, merchant, amount))
                else -> transfers++
            }
        }
        return Result(out, transfers)
    }

    /** 엑셀 날짜 일련번호(1899-12-30 기준 일수) + 하루 중 비율 → 기기 시간대의 시각. */
    private fun excelTime(date: String, time: String): Long? {
        val d = date.toDoubleOrNull() ?: return null
        val frac = (time.toDoubleOrNull() ?: 0.0) + (d - Math.floor(d))
        val c = Calendar.getInstance()
        c.clear()
        c.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        c.add(Calendar.DAY_OF_YEAR, Math.floor(d).toInt())
        c.add(Calendar.SECOND, (frac * 86400).roundToLong().toInt())
        return c.timeInMillis
    }

    private fun parser() = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        parser().parse(ByteArrayInputStream(xml), object : DefaultHandler() {
            val sb = StringBuilder()
            var inT = false
            var inPhonetic = false
            override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
                when (q) {
                    "si" -> sb.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) {
                    "si" -> out.add(sb.toString())
                    "rPh" -> inPhonetic = false
                    "t" -> inT = false
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }
        })
        return out
    }

    /** 시트를 행 목록으로 읽는다. 각 행은 열 번호(0부터) → 값. */
    private fun sheet(xml: ByteArray, strings: List<String>): List<Map<Int, String>> {
        val rows = ArrayList<Map<Int, String>>()
        parser().parse(ByteArrayInputStream(xml), object : DefaultHandler() {
            var row = HashMap<Int, String>()
            var colIdx = 0
            var type = ""
            val sb = StringBuilder()
            var capture = false
            override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
                when (q) {
                    "row" -> { row = HashMap(); colIdx = -1 }
                    "c" -> {
                        val ref = a?.getValue("r")
                        colIdx = if (ref != null) column(ref) else colIdx + 1
                        type = a?.getValue("t") ?: ""
                        sb.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = sb.toString()
                        val v = if (type == "s") strings.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: "" else raw
                        if (v.isNotEmpty()) row[colIdx] = v
                    }
                    "row" -> rows.add(row)
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) sb.append(ch, start, length)
            }
        })
        return rows
    }

    private fun column(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }
}
