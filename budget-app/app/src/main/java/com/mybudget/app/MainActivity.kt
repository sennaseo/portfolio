package com.mybudget.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val dateFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    private val dayFmt = SimpleDateFormat("M월 d일", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Store.load(this)

        findViewById<View>(R.id.btn_settings).setOnClickListener { settings() }
        findViewById<View>(R.id.balance_card).setOnClickListener { editBalance() }
        findViewById<View>(R.id.btn_access).setOnClickListener { openAccessSettings() }
        findViewById<View>(R.id.btn_add_budget).setOnClickListener { editBudget(null) }
        findViewById<View>(R.id.btn_add_tx).setOnClickListener { addTx() }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(NotiListener.EXTRA_TX, 0L) ?: 0L
        if (id == 0L) return
        intent?.removeExtra(NotiListener.EXTRA_TX)
        Store.txs.firstOrNull { it.id == id }?.let { editTx(it) }
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        Store.onChange = null
    }

    private fun accessGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun openAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    // ---------- 화면 그리기 ----------

    private fun render() {
        val now = System.currentTimeMillis()
        val start = Store.periodStart(now)
        val bal = Store.balance

        findViewById<TextView>(R.id.balance).text = bal?.let { won(it) } ?: "—"
        findViewById<TextView>(R.id.balance_sub).text = when {
            bal == null -> "아직 잔액 알림을 받지 못했어요 · 눌러서 직접 입력"
            else -> "${Store.balanceLabel.ifEmpty { "직접 입력" }} · ${dateFmt.format(Date(Store.balanceTime))} 기준"
        }

        val totalRemain = Store.budgets.sumOf { Store.remaining(it) }
        val summary = StringBuilder("예산 잔여 합계  ${won(totalRemain)}")
        if (bal != null && Store.budgets.isNotEmpty()) {
            val free = bal - totalRemain
            summary.append(if (free >= 0) "\n예산에 묶이지 않은 돈  ${won(free)}" else "\n통장 잔액이 남은 예산보다 ${won(-free)} 부족해요")
        }
        findViewById<TextView>(R.id.summary).text = summary

        findViewById<View>(R.id.access_banner).visibility = if (accessGranted()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.period).text = "예산 · ${dayFmt.format(Date(start))}부터"

        val budgetBox = findViewById<LinearLayout>(R.id.budgets)
        budgetBox.removeAllViews()
        for (b in Store.budgets) {
            val v = layoutInflater.inflate(R.layout.row_budget, budgetBox, false)
            val spent = Store.spent(b.id)
            val remain = b.amount - spent
            val color = BudgetWidget.levelColor(this, remain, b.amount)
            v.findViewById<TextView>(R.id.b_name).text = b.name
            v.findViewById<TextView>(R.id.b_remain).apply {
                text = won(remain)
                if (remain < 0) setTextColor(color)
            }
            v.findViewById<ProgressBar>(R.id.b_bar).apply {
                progress = BudgetWidget.permille(remain, b.amount)
                progressTintList = ColorStateList.valueOf(color)
            }
            v.findViewById<TextView>(R.id.b_sub).text = "예산 ${won(b.amount)} 중 ${won(spent)} 사용"
            v.setOnClickListener { editBudget(b) }
            budgetBox.addView(v)
        }
        if (Store.budgets.isEmpty()) {
            budgetBox.addView(TextView(this).apply {
                text = "'+ 예산 추가'를 눌러 식비·교통비 같은 예산을 만들어 보세요."
                setTextColor(getColor(R.color.sub)); textSize = 13f
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        }

        val un = Store.unassigned()
        findViewById<View>(R.id.unassigned_title).visibility = if (un.isEmpty()) View.GONE else View.VISIBLE
        val unBox = findViewById<LinearLayout>(R.id.unassigned)
        unBox.removeAllViews()
        un.forEach { unBox.addView(txRow(it, unBox)) }

        val recent = synchronized(Store) { Store.txs.sortedByDescending { it.time }.take(60) }
        val recentBox = findViewById<LinearLayout>(R.id.recent)
        recentBox.removeAllViews()
        recent.forEach { recentBox.addView(txRow(it, recentBox)) }
        findViewById<View>(R.id.recent_empty).visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun txRow(t: Tx, parent: ViewGroup): View {
        val v = layoutInflater.inflate(R.layout.row_tx, parent, false)
        v.findViewById<TextView>(R.id.t_merchant).text = t.merchant.ifEmpty { "(이름 없음)" }
        val income = t.type == Parser.INCOME
        v.findViewById<TextView>(R.id.t_sub).text =
            dateFmt.format(Date(t.time)) + " · " + if (income) "입금" else Store.budgetName(t.budgetId)
        v.findViewById<TextView>(R.id.t_amount).apply {
            text = when {
                income -> "+" + won(t.amount)
                t.amount < 0 -> "+" + won(-t.amount) + " 취소"
                else -> "-" + won(t.amount)
            }
            if (income || t.amount < 0) setTextColor(getColor(R.color.good))
        }
        v.setOnClickListener { editTx(t) }
        return v
    }

    // ---------- 대화상자 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun form(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
    }

    private fun LinearLayout.field(hint: String, value: String, number: Boolean): EditText {
        val e = EditText(context)
        e.hint = hint
        e.setText(value)
        e.inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
        addView(e)
        return e
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun editBudget(b: Budget?) {
        val f = form()
        val name = f.field("이름 (예: 식비)", b?.name ?: "", false)
        val amount = f.field("한 달 예산 (원)", b?.amount?.toString() ?: "", true)
        val kw = f.field("자동 분류 키워드 (쉼표로 구분, 예: 스타벅스,배달의민족)", b?.keywords ?: "", false)
        val d = AlertDialog.Builder(this)
            .setTitle(if (b == null) "예산 추가" else "예산 수정")
            .setView(f)
            .setPositiveButton("저장") { _, _ ->
                val n = name.text.toString().trim()
                val a = amount.text.toString().toLongOrNull()
                if (n.isEmpty() || a == null || a < 0) { toast("이름과 금액을 입력해 주세요"); return@setPositiveButton }
                if (b == null) Store.budgets.add(Budget(Store.newId(), n, a, kw.text.toString().trim()))
                else { b.name = n; b.amount = a; b.keywords = kw.text.toString().trim() }
                // 새 키워드에 맞는 미분류 내역을 바로 배정
                synchronized(Store) {
                    Store.txs.filter { it.type == Parser.EXPENSE && it.budgetId == Store.UNASSIGNED }
                        .forEach { it.budgetId = Store.autoBudget(it.merchant) }
                }
                Store.save(this)
            }
            .setNegativeButton("취소", null)
        if (b != null) d.setNeutralButton("삭제") { _, _ ->
            AlertDialog.Builder(this)
                .setMessage("'${b.name}' 예산을 삭제할까요? 이 예산의 내역은 미분류로 돌아갑니다.")
                .setPositiveButton("삭제") { _, _ ->
                    synchronized(Store) {
                        Store.budgets.remove(b)
                        Store.txs.filter { it.budgetId == b.id }.forEach { it.budgetId = Store.UNASSIGNED }
                        Store.merchantMap.entries.removeAll { it.value == b.id }
                    }
                    Store.save(this)
                }
                .setNegativeButton("취소", null).show()
        }
        d.show()
    }

    private fun editTx(t: Tx) {
        if (t.type == Parser.INCOME) {
            AlertDialog.Builder(this)
                .setTitle("${t.merchant}  +${won(t.amount)}")
                .setMessage(t.raw.ifEmpty { "직접 입력한 내역" })
                .setPositiveButton("닫기", null)
                .setNeutralButton("내역 삭제") { _, _ -> deleteTx(t) }
                .show()
            return
        }
        val f = form()
        val always = CheckBox(this).apply {
            text = "'${t.merchant}'은(는) 앞으로도 이 예산으로"
            isChecked = t.source.isNotEmpty() && t.merchant.isNotEmpty()
            visibility = if (t.merchant.isEmpty()) View.GONE else View.VISIBLE
        }
        f.addView(always)
        var dialog: AlertDialog? = null
        val options = Store.budgets.map { it.id to "${it.name}  (잔여 ${won(Store.remaining(it))})" } +
            listOf(Store.EXCLUDED to "예산 제외 (이체·저축 등)", Store.UNASSIGNED to "미분류로 두기")
        for ((id, label) in options) {
            f.addView(TextView(this).apply {
                text = (if (t.budgetId == id) "✓ " else "") + label
                textSize = 16f
                setTextColor(getColor(if (t.budgetId == id) R.color.accent else R.color.text))
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener {
                    t.budgetId = id
                    if (always.isChecked && always.visibility == View.VISIBLE) {
                        if (id == Store.UNASSIGNED) Store.merchantMap.remove(t.merchant) else Store.merchantMap[t.merchant] = id
                        if (id != Store.UNASSIGNED) synchronized(Store) {
                            Store.txs.filter { it.type == Parser.EXPENSE && it.budgetId == Store.UNASSIGNED && it.merchant == t.merchant }
                                .forEach { it.budgetId = id }
                        }
                    }
                    getSystemService(NotificationManager::class.java).cancel(t.id.toInt())
                    Store.save(this@MainActivity)
                    dialog?.dismiss()
                }
            })
        }
        val amountText = if (t.amount < 0) "+${won(-t.amount)} 취소" else "-${won(t.amount)}"
        dialog = AlertDialog.Builder(this)
            .setTitle("${t.merchant}  $amountText")
            .setView(android.widget.ScrollView(this).apply { addView(f) })
            .setNegativeButton("닫기", null)
            .setNeutralButton("내역 삭제") { _, _ -> deleteTx(t) }
            .show()
    }

    private fun deleteTx(t: Tx) {
        synchronized(Store) { Store.txs.remove(t) }
        getSystemService(NotificationManager::class.java).cancel(t.id.toInt())
        Store.save(this)
    }

    private fun addTx() {
        if (Store.budgets.isEmpty()) { toast("먼저 예산을 추가해 주세요"); return }
        val f = form()
        val amount = f.field("금액 (원)", "", true)
        val memo = f.field("메모 (예: 현금 점심)", "", false)
        var chosen = 0
        AlertDialog.Builder(this)
            .setTitle("지출 직접 입력")
            .setView(f)
            .setPositiveButton("다음") { _, _ ->
                val a = amount.text.toString().toLongOrNull()
                if (a == null || a == 0L) { toast("금액을 입력해 주세요"); return@setPositiveButton }
                AlertDialog.Builder(this)
                    .setTitle("어느 예산에서 썼나요?")
                    .setSingleChoiceItems(Store.budgets.map { it.name }.toTypedArray(), 0) { _, i -> chosen = i }
                    .setPositiveButton("저장") { _, _ ->
                        val b = Store.budgets.getOrNull(chosen) ?: return@setPositiveButton
                        synchronized(Store) {
                            Store.txs.add(Tx(Store.newId(), System.currentTimeMillis(), a, Parser.EXPENSE,
                                memo.text.toString().trim().ifEmpty { "직접 입력" }, b.id, "", ""))
                        }
                        Store.save(this)
                    }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun editBalance() {
        val f = form()
        val e = f.field("현재 통장 잔액 (원)", Store.balance?.toString() ?: "", true)
        AlertDialog.Builder(this)
            .setTitle("통장 잔액 직접 수정")
            .setMessage("다음 입출금 알림이 오면 알림의 잔액으로 다시 맞춰집니다.")
            .setView(f)
            .setPositiveButton("저장") { _, _ ->
                val v = e.text.toString().toLongOrNull() ?: return@setPositiveButton
                Store.balance = v; Store.balanceTime = System.currentTimeMillis(); Store.balanceLabel = ""
                Store.save(this)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun settings() {
        val src = if (Store.balancePkg.isEmpty()) "가장 최근 알림" else Store.seenApps[Store.balancePkg] ?: Store.balancePkg
        val items = arrayOf(
            "예산 시작일: 매월 ${Store.resetDay}일",
            "잔액 기준 앱: $src",
            "감지에서 제외할 앱",
            "알림 문구 테스트",
            "통장 잔액 직접 수정",
            "알림 접근 설정 열기",
        )
        AlertDialog.Builder(this).setTitle("설정").setItems(items) { _, i ->
            when (i) {
                0 -> pickResetDay()
                1 -> pickBalanceApp()
                2 -> pickBlockedApps()
                3 -> testParse()
                4 -> editBalance()
                5 -> openAccessSettings()
            }
        }.show()
    }

    private fun pickResetDay() {
        val f = form()
        val e = f.field("1 ~ 28", Store.resetDay.toString(), true)
        AlertDialog.Builder(this)
            .setTitle("예산 시작일 (월급날 등)")
            .setView(f)
            .setPositiveButton("저장") { _, _ ->
                val d = e.text.toString().toIntOrNull()
                if (d == null || d !in 1..28) { toast("1~28 사이로 입력해 주세요"); return@setPositiveButton }
                Store.resetDay = d
                Store.save(this)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun pickBalanceApp() {
        val pkgs = listOf("") + Store.seenApps.keys
        val labels = pkgs.map { if (it.isEmpty()) "가장 최근 알림 (모든 앱)" else Store.seenApps[it] ?: it }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("통장 잔액을 어느 앱 알림으로 맞출까요?")
            .setSingleChoiceItems(labels, pkgs.indexOf(Store.balancePkg).coerceAtLeast(0)) { d, i ->
                Store.balancePkg = pkgs[i]
                Store.save(this)
                d.dismiss()
            }
            .setNegativeButton("취소", null).show()
        if (Store.seenApps.isEmpty()) toast("거래 알림을 한 번 받으면 앱 목록이 나타납니다")
    }

    private fun pickBlockedApps() {
        if (Store.seenApps.isEmpty()) { toast("거래 알림을 한 번 받으면 앱 목록이 나타납니다"); return }
        val pkgs = Store.seenApps.keys.toList()
        val checked = pkgs.map { it in Store.blockedApps }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("체크한 앱의 알림은 무시합니다")
            .setMultiChoiceItems(pkgs.map { Store.seenApps[it] ?: it }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("저장") { _, _ ->
                Store.blockedApps.clear()
                pkgs.forEachIndexed { i, p -> if (checked[i]) Store.blockedApps.add(p) }
                Store.save(this)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun testParse() {
        val f = form()
        val e = EditText(this).apply {
            hint = "은행·카드 알림(문자) 내용을 그대로 붙여넣으세요"
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        f.addView(e)
        AlertDialog.Builder(this)
            .setTitle("알림 문구 테스트")
            .setView(f)
            .setPositiveButton("확인") { _, _ ->
                val p = Parser.parse(null, e.text.toString())
                val msg = if (p == null) "거래 알림으로 인식하지 못했어요." else
                    "종류: ${if (p.type == Parser.INCOME) "입금" else if (p.amount < 0) "결제 취소" else "지출"}\n" +
                        "금액: ${won(kotlin.math.abs(p.amount))}\n" +
                        "가맹점: ${p.merchant.ifEmpty { "(인식 못함)" }}\n" +
                        "잔액: ${p.balance?.let { won(it) } ?: "(문구에 없음)"}\n" +
                        "예산: ${Store.budgetName(Store.autoBudget(p.merchant))}"
                AlertDialog.Builder(this).setTitle("인식 결과").setMessage(msg).setPositiveButton("닫기", null).show()
            }
            .setNegativeButton("취소", null).show()
    }
}
