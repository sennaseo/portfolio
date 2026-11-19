package com.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {
    private fun xlsx(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val strings = listOf("날짜", "시간", "타입", "대분류", "소분류", "내용", "금액", "화폐", "결제수단", "메모",
        "지출", "식비", "점심", "행복한순정감자탕", "KRW", "체크카드", "이체", "내계좌이체", "홍길동", "수입", "급여", "회사", "요약")

    private fun s(col: String, row: Int, v: String) = """<c r="$col$row" t="s"><v>${strings.indexOf(v)}</v></c>"""
    private fun n(col: String, row: Int, v: String) = """<c r="$col$row"><v>$v</v></c>"""

    private fun sample(): ByteArray {
        val sst = "<sst>" + strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        val summary = """<worksheet><sheetData><row r="3">${s("B", 3, "요약")}</row></sheetData></worksheet>"""
        val header = "ABCDEFGHIJ".mapIndexed { i, c -> s(c.toString(), 1, strings[i]) }.joinToString("")
        // 46345 = 2026-11-19, 0.5 = 12:00:00
        val rows = listOf(
            n("A", 2, "46345") + n("B", 2, "0.5") + s("C", 2, "지출") + s("D", 2, "식비") + s("F", 2, "행복한순정감자탕") + n("G", 2, "-11000"),
            n("A", 3, "46345") + n("B", 3, "0.25") + s("C", 3, "이체") + s("D", 3, "내계좌이체") + s("F", 3, "홍길동") + n("G", 3, "-100000"),
            n("A", 4, "46344") + n("B", 4, "0.75") + s("C", 4, "수입") + s("D", 4, "급여") + s("F", 4, "회사") + n("G", 4, "2500000"),
            n("A", 5, "46344") + n("B", 5, "0.75") + s("C", 5, "지출") + s("D", 5, "식비") + s("F", 5, "행복한순정감자탕") + n("G", 5, "3000"),
        )
        val sheet = "<worksheet><sheetData><row r=\"1\">$header</row>" +
            rows.mapIndexed { i, r -> "<row r=\"${i + 2}\">$r</row>" }.joinToString("") + "</sheetData></worksheet>"
        return xlsx("xl/sharedStrings.xml" to sst, "xl/worksheets/sheet1.xml" to summary, "xl/worksheets/sheet2.xml" to sheet)
    }

    @Test fun readsBanksaladSheet() {
        val r = Importer.read(ByteArrayInputStream(sample()))
        assertEquals(3, r.rows.size)
        assertEquals(1, r.skippedTransfers)

        val spend = r.rows[0]
        assertEquals(Parser.EXPENSE, spend.type)
        assertEquals(11000L, spend.amount)
        assertEquals("식비", spend.category)
        assertEquals("행복한순정감자탕", spend.merchant)
        val c = Calendar.getInstance().apply { timeInMillis = spend.time }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, c.get(Calendar.MONTH))
        assertEquals(19, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))

        assertEquals(Parser.INCOME, r.rows[1].type)
        assertEquals(2500000L, r.rows[1].amount)
        // 지출 타입인데 금액이 양수면 결제 취소(환불)
        assertEquals(-3000L, r.rows[2].amount)
    }

    /** 실제 내보내기 파일로 확인할 때: BANKSALAD_SAMPLE=/path/file.xlsx 로 실행 */
    @Test fun realFileIfProvided() {
        val path = System.getenv("BANKSALAD_SAMPLE") ?: return
        val r = File(path).inputStream().use { Importer.read(it) }
        val spend = r.rows.filter { it.type == Parser.EXPENSE }
        println("rows=${r.rows.size} transfers=${r.skippedTransfers} expenseSum=${spend.sumOf { it.amount }}")
        spend.groupBy { it.category }.forEach { (k, v) -> println("  $k ${v.sumOf { it.amount }} (${v.size})") }
    }
}
