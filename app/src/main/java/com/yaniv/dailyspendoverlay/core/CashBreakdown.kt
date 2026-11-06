package com.yaniv.dailyspendoverlay.core

/**
 * "התשלום כשטרות": אילו שטרות ומטבעות היו יוצאים מהארנק אם היית משלם במזומן.
 *
 * שקל, יורו ודולר. מטבע אחר – null (הבועה פשוט לא מציגה את השורה).
 * מה שקטן מיחידה אחת (אגורות / סנטים) מוצג כחתיכה אחת ("40 אג׳"), כדי שהשורה לא תתמלא במטבעות קטנים.
 * פירוק "חמדני": השטר הגדול ביותר שנכנס, וכן הלאה – כמו שמשלמים בקופה.
 */
object CashBreakdown {

    enum class Kind { BILL, COIN, SMALL }

    /** שטר / מטבע (ערך ביחידות קטנות: 10000 = 100 ₪) וכמה פעמים. SMALL = כל מה שמתחת ליחידה אחת */
    data class Piece(val kind: Kind, val minor: Long, val count: Int)

    private class Denominations(val bills: List<Long>, val coins: List<Long>, val roundTo: Long)

    private val TABLE = mapOf(
        // שקל: שטרות 200/100/50/20, מטבעות 10/5/2/1; במזומן מעגלים ל-10 אגורות
        "ILS" to Denominations(listOf(20_000L, 10_000L, 5_000L, 2_000L), listOf(1_000L, 500L, 200L, 100L), 10L),
        // יורו: שטרות 200 עד 5, מטבעות 2 ו-1
        "EUR" to Denominations(listOf(20_000L, 10_000L, 5_000L, 2_000L, 1_000L, 500L), listOf(200L, 100L), 1L),
        // דולר: שטרות 100 עד 1
        "USD" to Denominations(listOf(10_000L, 5_000L, 2_000L, 1_000L, 500L, 100L), emptyList(), 1L),
    )

    fun supports(currency: String): Boolean = currency.uppercase() in TABLE

    /** עיגול של מזומן: בשקל ל-10 אגורות הקרובות (1–4 למטה, 5–9 למעלה); ביורו ובדולר בלי עיגול */
    fun roundForCash(minor: Long, currency: String): Long {
        val step = TABLE[currency.uppercase()]?.roundTo ?: 1L
        return ((minor + step / 2) / step) * step
    }

    fun of(amount: Amount): List<Piece>? = of(amount.minor, amount.currency)

    /**
     * טקסט לשורה "נשארו לך בארנק: 50 + 20 + 5": שטר שחוזר עד 3 פעמים נכתב כמה פעמים,
     * יותר – "200 ×4"; אגורות / סנטים – "40 אג׳" / "80 סנט".
     */
    fun describe(pieces: List<Piece>, currency: String): String =
        pieces.flatMap { piece ->
            val label = when (piece.kind) {
                Kind.SMALL -> if (currency.uppercase() == "ILS") "${piece.minor} אג׳" else "${piece.minor} סנט"
                else -> (piece.minor / 100).toString()
            }
            when {
                piece.count <= 3 -> List(piece.count) { label }
                else -> listOf("$label ×${piece.count}")
            }
        }.joinToString(" + ")

    /** null = מטבע שלא נתמך; רשימה ריקה = אין מה להציג (סכום 0 או פחות מ-5 אגורות) */
    fun of(minor: Long, currency: String): List<Piece>? {
        val table = TABLE[currency.uppercase()] ?: return null
        if (minor <= 0) return emptyList()
        var rest = roundForCash(minor, currency)
        val pieces = mutableListOf<Piece>()
        for (bill in table.bills) {
            val count = rest / bill
            if (count > 0) {
                pieces += Piece(Kind.BILL, bill, count.toInt())
                rest -= count * bill
            }
        }
        for (coin in table.coins) {
            val count = rest / coin
            if (count > 0) {
                pieces += Piece(Kind.COIN, coin, count.toInt())
                rest -= count * coin
            }
        }
        if (rest > 0) pieces += Piece(Kind.SMALL, rest, 1)
        return pieces
    }
}
