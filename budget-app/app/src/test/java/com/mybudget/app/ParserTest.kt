package com.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private fun p(title: String?, text: String) = Parser.parse(title, text)

    @Test fun kbSms() {
        val r = p(null, "[KB]11/18 14:22\n123-**-456\n스타벅스\n체크카드출금\n5,600\n잔액1,234,567")!!
        assertEquals(Parser.EXPENSE, r.type)
        assertEquals(5600L, r.amount)
        assertEquals("스타벅스", r.merchant)
        assertEquals(1234567L, r.balance)
    }

    @Test fun shinhanSms() {
        val r = p("신한은행", "[신한은행] 11/18 14:22 110-***-123456 출금 5,600원 잔액 1,234,567원 스타벅스")!!
        assertEquals(5600L, r.amount)
        assertEquals("스타벅스", r.merchant)
        assertEquals(1234567L, r.balance)
    }

    @Test fun kakaobankPush() {
        val r = p("출금 12,000원", "이마트 강남점 → 내 통장(1234) 잔액 988,000원")!!
        assertEquals(12000L, r.amount)
        assertEquals("이마트 강남점", r.merchant)
        assertEquals(988000L, r.balance)
    }

    @Test fun tossPush() {
        val r = p("5,600원 결제", "GS25 역삼점 | 잔액 94,400원")!!
        assertEquals(5600L, r.amount)
        assertEquals("GS25 역삼점", r.merchant)
        assertEquals(94400L, r.balance)
    }

    @Test fun balanceBeforeAmount() {
        val r = p(null, "잔액 100,000원 출금 3,000원 편의점")!!
        assertEquals(3000L, r.amount)
        assertEquals(100000L, r.balance)
    }

    @Test fun samsungCard() {
        val r = p(null, "[Web발신]\n삼성카드 승인 홍*동\n45,000원 일시불\n11/18 14:22 쿠팡\n누적 523,400원")!!
        assertEquals(45000L, r.amount)
        assertEquals("쿠팡", r.merchant)
        assertNull(r.balance)
    }

    @Test fun cancel() {
        val r = p(null, "삼성카드 승인취소 홍*동 45,000원 11/18 쿠팡")!!
        assertEquals(-45000L, r.amount)
        assertEquals(Parser.EXPENSE, r.type)
        assertEquals("쿠팡", r.merchant)
    }

    @Test fun income() {
        val r = p("입금 2,500,000원", "급여 → 내 통장 잔액 3,734,567원")!!
        assertEquals(Parser.INCOME, r.type)
        assertEquals(2500000L, r.amount)
        assertEquals("급여", r.merchant)
        assertEquals(3734567L, r.balance)
    }

    @Test fun ignored() {
        assertNull(p("(광고) 특가", "지금 결제하면 10,000원 할인"))
        assertNull(p(null, "카드대금 300,000원 11/25 출금 예정입니다"))
        assertNull(p("엄마", "저녁 먹었니? 10,000원 보냈다"))
        assertNull(p(null, "인증번호 123456 결제 진행"))
        assertNull(p("배송 안내", "주문하신 상품이 출발했습니다"))
    }
}
