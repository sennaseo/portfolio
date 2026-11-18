package com.mybudget.app

/**
 * 은행·카드 알림/문자 문구에서 거래 정보를 뽑아낸다.
 * 안드로이드 클래스에 의존하지 않는 순수 Kotlin (단위 테스트 대상).
 */
object Parser {
    const val EXPENSE = 0
    const val INCOME = 1

    /** amount: 지출이면 양수, 결제 취소(환불)면 음수. 입금은 양수. */
    data class Parsed(val type: Int, val amount: Long, val merchant: String, val balance: Long?)

    private const val NUM = """(?:\d{1,3}(?:,\d{3})+|\d+)"""

    private val IGNORE = Regex("""광고|예정|거절|명세서|인증번호|실패|오류|한도초과""")
    private val KEYWORD = Regex("""출금|입금|결제|승인|사용|이체|송금|체크카드|취소""")
    private val AMOUNT = Regex("""($NUM)\s*원""")
    private val NOT_AMOUNT_PREFIX = Regex("""(잔액|누적|한도|잔여|잔고|적립|포인트|할인)(금액|액)?\s*[:：]?\s*-?\s*$""")
    private val BALANCE = Regex("""(?:잔액|잔고)\s*[:：]?\s*(-?)\s*($NUM)""")
    private val BARE_NUMBER_LINE = Regex("""^\s*($NUM)\s*$""")

    private val STRIP = listOf(
        Regex("""\[[^\]]*]"""),
        Regex("""\([^)]*\)"""),
        Regex("""(잔액|누적|한도|잔여|잔고|적립|포인트|할인)(금액|액)?\s*[:：]?\s*-?\s*$NUM\s*원?"""),
        Regex("""$NUM\s*원(을|이)?"""),
        Regex("""\d{1,2}\s*[/.월]\s*\d{1,2}일?"""),
        Regex("""\d{1,2}:\d{2}(:\d{2})?"""),
        Regex("""[\d*]+(-[\d*]+)+"""),
        Regex("""\S*\*+\S*"""),
        Regex("""\S*(은행|카드|뱅크)\S*"""),
        Regex("""Web발신|체크|신용|출금|입금|결제|승인|취소|사용|이체|송금|일시불|\d+개월|완료|안내|알림|해외|국내|내 통장|되었습니다|했습니다|됐어요|했어요"""),
    )
    private val SPLIT = Regex("""[\n|→>]""")
    private val MEANINGFUL = Regex("""[가-힣A-Za-z]{2,}|[가-힣A-Za-z].*[가-힣A-Za-z0-9]""")

    fun num(s: String): Long = s.replace(",", "").toLongOrNull() ?: 0L

    fun parse(title: String?, text: String?): Parsed? {
        val all = listOfNotNull(title, text).joinToString("\n").trim()
        if (all.isEmpty() || IGNORE.containsMatchIn(all) || !KEYWORD.containsMatchIn(all)) return null

        var amount = 0L
        for (m in AMOUNT.findAll(all)) {
            val prefix = all.substring(maxOf(0, m.range.first - 12), m.range.first)
            if (NOT_AMOUNT_PREFIX.containsMatchIn(prefix)) continue
            amount = num(m.groupValues[1])
            if (amount > 0) break
        }
        if (amount <= 0) {
            // KB 문자처럼 금액이 '원' 없이 한 줄에 숫자만 있는 형식
            for (line in all.lines()) {
                val m = BARE_NUMBER_LINE.find(line) ?: continue
                amount = num(m.groupValues[1])
                if (amount > 0) break
            }
        }
        if (amount <= 0) return null

        val balance = BALANCE.find(all)?.let {
            val v = num(it.groupValues[2])
            if (it.groupValues[1] == "-") -v else v
        }

        val cancel = all.contains("취소")
        val income = !cancel && all.contains("입금") && !all.contains("출금")
        return Parsed(
            type = if (income) INCOME else EXPENSE,
            amount = if (cancel) -amount else amount,
            merchant = merchant(all),
            balance = balance,
        )
    }

    private fun merchant(all: String): String {
        var s = all
        for (r in STRIP) s = r.replace(s, " ")
        for (seg in s.split(SPLIT)) {
            val c = seg.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', ':', '-', '/', '·')
            if (MEANINGFUL.containsMatchIn(c)) return c.take(30)
        }
        return ""
    }
}
