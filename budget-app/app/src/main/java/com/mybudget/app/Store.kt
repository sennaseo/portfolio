package com.mybudget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.abs

class Budget(val id: Long, var name: String, var amount: Long, var keywords: String)

class Tx(
    val id: Long,
    var time: Long,
    var amount: Long,
    val type: Int,
    var merchant: String,
    var budgetId: Long,
    val raw: String,
    val source: String,
)

/** 앱의 모든 데이터. filesDir/data.json 한 파일에 저장한다. */
object Store {
    const val UNASSIGNED = 0L
    const val EXCLUDED = -1L

    val budgets = ArrayList<Budget>()
    val txs = ArrayList<Tx>()
    var balance: Long? = null
    var balanceTime = 0L
    var balanceLabel = ""
    /** 잔액을 이 앱(패키지)의 알림에서만 갱신. 빈 값이면 가장 최근 알림 기준. */
    var balancePkg = ""
    var resetDay = 1
    val merchantMap = HashMap<String, Long>()
    val seenApps = LinkedHashMap<String, String>()
    val blockedApps = HashSet<String>()
    private var nextId = 1L
    private var loaded = false

    var onChange: (() -> Unit)? = null

    fun newId() = nextId++

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, "data.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            nextId = o.optLong("nextId", 1)
            resetDay = o.optInt("resetDay", 1)
            if (o.has("balance")) balance = o.getLong("balance")
            balanceTime = o.optLong("balanceTime")
            balanceLabel = o.optString("balanceLabel")
            balancePkg = o.optString("balancePkg")
            o.optJSONArray("budgets")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    budgets.add(Budget(it.getLong("id"), it.getString("name"), it.getLong("amount"), it.optString("keywords")))
                }
            }
            o.optJSONArray("txs")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    txs.add(Tx(it.getLong("id"), it.getLong("time"), it.getLong("amount"), it.getInt("type"),
                        it.optString("merchant"), it.optLong("budgetId"), it.optString("raw"), it.optString("source")))
                }
            }
            o.optJSONObject("merchantMap")?.let { m -> m.keys().forEach { merchantMap[it] = m.getLong(it) } }
            o.optJSONObject("seenApps")?.let { m -> m.keys().forEach { seenApps[it] = m.getString(it) } }
            o.optJSONArray("blockedApps")?.let { a -> for (i in 0 until a.length()) blockedApps.add(a.getString(i)) }
        } catch (e: Exception) {
            // 손상된 파일은 덮어쓰지 않도록 백업해 둔다
            f.copyTo(File(ctx.filesDir, "data.broken.json"), overwrite = true)
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 400L * 24 * 3600 * 1000
        txs.removeAll { it.time < cutoff }
        val o = JSONObject()
        o.put("nextId", nextId)
        o.put("resetDay", resetDay)
        balance?.let { o.put("balance", it) }
        o.put("balanceTime", balanceTime)
        o.put("balanceLabel", balanceLabel)
        o.put("balancePkg", balancePkg)
        o.put("budgets", JSONArray().apply {
            budgets.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount).put("keywords", it.keywords)) }
        })
        o.put("txs", JSONArray().apply {
            txs.forEach {
                put(JSONObject().put("id", it.id).put("time", it.time).put("amount", it.amount).put("type", it.type)
                    .put("merchant", it.merchant).put("budgetId", it.budgetId).put("raw", it.raw).put("source", it.source))
            }
        })
        o.put("merchantMap", JSONObject().apply { merchantMap.forEach { (k, v) -> put(k, v) } })
        o.put("seenApps", JSONObject().apply { seenApps.forEach { (k, v) -> put(k, v) } })
        o.put("blockedApps", JSONArray(blockedApps.toList()))
        val tmp = File(ctx.filesDir, "data.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(ctx.filesDir, "data.json"))
        BudgetWidget.refresh(ctx)
        onChange?.invoke()
    }

    /** 현재 예산 기간의 시작 시각 (매월 resetDay일 0시). */
    fun periodStart(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        if (c.get(Calendar.DAY_OF_MONTH) < resetDay) c.add(Calendar.MONTH, -1)
        c.set(Calendar.DAY_OF_MONTH, resetDay)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Synchronized
    fun spent(budgetId: Long): Long {
        val start = periodStart()
        return txs.filter { it.type == Parser.EXPENSE && it.budgetId == budgetId && it.time >= start }.sumOf { it.amount }
    }

    fun remaining(b: Budget) = b.amount - spent(b.id)

    @Synchronized
    fun unassigned(): List<Tx> {
        val start = periodStart()
        return txs.filter { it.type == Parser.EXPENSE && it.budgetId == UNASSIGNED && it.time >= start }.sortedByDescending { it.time }
    }

    fun budgetName(id: Long) = when (id) {
        UNASSIGNED -> "미분류"
        EXCLUDED -> "예산 제외"
        else -> budgets.firstOrNull { it.id == id }?.name ?: "삭제된 예산"
    }

    /** 가맹점 이름으로 예산을 자동 배정. 직접 지정해 둔 가맹점 → 예산 키워드 순. */
    fun autoBudget(merchant: String): Long {
        if (merchant.isEmpty()) return UNASSIGNED
        merchantMap[merchant]?.let { id -> if (id == EXCLUDED || budgets.any { it.id == id }) return id }
        val m = merchant.lowercase()
        for (b in budgets) {
            for (k in b.keywords.split(',', '，', '\n')) {
                val kw = k.trim().lowercase()
                if (kw.isNotEmpty() && m.contains(kw)) return b.id
            }
        }
        return UNASSIGNED
    }

    /**
     * 알림 한 건을 반영한다. 새 지출이 미분류로 들어오면 그 Tx를 돌려준다.
     * 같은 결제가 은행 앱·카드 앱·문자로 중복해서 오면 한 건으로 합친다.
     */
    @Synchronized
    fun ingest(ctx: Context, pkg: String, label: String, title: String?, text: String?, time: Long): Tx? {
        load(ctx)
        if (pkg in blockedApps) return null
        val p = Parser.parse(title, text) ?: return null
        val raw = listOfNotNull(title, text).joinToString("\n")
        if (pkg.isNotEmpty()) seenApps[pkg] = label

        if (p.balance != null && (balancePkg.isEmpty() || balancePkg == pkg)) {
            balance = p.balance; balanceTime = time; balanceLabel = label
        }

        // 같은 알림이 갱신되어 다시 올라온 경우
        if (txs.any { it.raw == raw && abs(it.time - time) < 10 * 60_000 }) { save(ctx); return null }
        // 다른 앱/문자에서 같은 결제를 또 알려준 경우
        val dup = txs.firstOrNull { it.type == p.type && it.amount == p.amount && it.source != pkg && abs(it.time - time) < 2 * 60_000 }
        if (dup != null) {
            if (dup.merchant.isEmpty() && p.merchant.isNotEmpty()) {
                dup.merchant = p.merchant
                if (dup.budgetId == UNASSIGNED) dup.budgetId = autoBudget(p.merchant)
            }
            save(ctx); return null
        }

        var budgetId = UNASSIGNED
        if (p.type == Parser.EXPENSE) {
            budgetId = autoBudget(p.merchant)
            if (p.amount < 0 && budgetId == UNASSIGNED) {
                // 결제 취소는 원래 결제가 들어간 예산으로 되돌린다
                txs.lastOrNull { it.type == Parser.EXPENSE && it.amount == -p.amount }?.let { budgetId = it.budgetId }
            }
        }
        val tx = Tx(newId(), time, p.amount, p.type, p.merchant.ifEmpty { label }, budgetId, raw, pkg)
        txs.add(tx)
        save(ctx)
        return if (tx.type == Parser.EXPENSE && tx.budgetId == UNASSIGNED && budgets.isNotEmpty()) tx else null
    }

    /**
     * 가계부 파일에서 읽은 내역을 넣는다. 이미 있는 내역은 건너뛴다.
     * 대분류와 같은 이름의 예산에 배정하고, 없으면 (createBudgets일 때) 예산 0원으로 새로 만든다.
     * 한 가지 대분류로만 쓰인 가맹점은 이후 알림도 그 예산으로 자동 분류되도록 기억한다.
     * @return 새로 추가된 건수
     */
    @Synchronized
    fun importRows(ctx: Context, rows: List<Importer.Row>, createBudgets: Boolean): Int {
        load(ctx)
        val expenses = rows.filter { it.type == Parser.EXPENSE }
        if (createBudgets) {
            for (cat in expenses.map { it.category }.distinct()) {
                if (cat.isNotEmpty() && cat != "미분류" && budgets.none { it.name == cat }) budgets.add(Budget(newId(), cat, 0, ""))
            }
        }
        fun budgetOf(cat: String) = budgets.firstOrNull { it.name == cat }?.id ?: UNASSIGNED
        for ((merchant, list) in expenses.groupBy { it.merchant }) {
            val cats = list.map { it.category }.distinct()
            if (merchant.isNotEmpty() && cats.size == 1 && budgetOf(cats[0]) != UNASSIGNED) merchantMap[merchant] = budgetOf(cats[0])
        }
        var added = 0
        for (r in rows) {
            val dup = txs.any {
                it.type == r.type && it.amount == r.amount &&
                    ((it.time == r.time && it.merchant == r.merchant) || (it.source != "import" && abs(it.time - r.time) < 2 * 60_000))
            }
            if (dup) continue
            val budgetId = if (r.type == Parser.EXPENSE) budgetOf(r.category) else UNASSIGNED
            txs.add(Tx(newId(), r.time, r.amount, r.type, r.merchant, budgetId, "", "import"))
            added++
        }
        save(ctx)
        return added
    }
}

fun won(v: Long) = "%,d원".format(v)
