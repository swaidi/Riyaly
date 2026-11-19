package app.riyaly

/**
 * Reads a bank SMS. Pure Kotlin (no Android classes) so it can be unit tested.
 *
 * Handles free-text messages ("شراء إنترنت بـSR 37.62 / عبر1234;…") and labeled-field messages
 * ("بطاقة:1234 / مبلغ:24 SAR / لدى:…"), transfers in and out, cash withdrawals, international
 * purchases (fees, total charged, country) and declined transactions.
 *
 * Card digits are searched in the WHOLE message, but only after amounts, balances, dates and
 * times are blanked out on a temporary copy. The original text is never changed.
 */
object Parser {

    object Kind {
        const val PURCHASE = "purchase"
        const val WITHDRAWAL = "withdrawal"
        const val TRANSFER_IN = "transfer_in"
        const val TRANSFER_OUT = "transfer_out"
        const val REFUND = "refund"
        const val CREDIT_IN = "credit_in"
        const val BILL = "bill"
        val ALL = listOf(PURCHASE, WITHDRAWAL, BILL, TRANSFER_OUT, TRANSFER_IN, REFUND, CREDIT_IN)
        val INFLOW = setOf(TRANSFER_IN, REFUND, CREDIT_IN)
        val SPEND = setOf(PURCHASE, WITHDRAWAL, BILL)
    }

    data class Profile(
        val balanceWords: List<String> = DEFAULT_BALANCE_WORDS,
        val merchantPrefixes: List<String> = DEFAULT_MERCHANT_PREFIXES,
    )

    data class Result(
        val isTransaction: Boolean,
        val kind: String,
        /** The amount actually charged or received (the total due when the message gives one). */
        val amount: Double?,
        val currency: String?,
        /** The purchase amount before fees / in the original currency, when it differs from [amount]. */
        val origAmount: Double?,
        val origCurrency: String?,
        val fees: Double?,
        val rate: Double?,
        val balance: Double?,
        val merchant: String?,
        val method: String?,
        val type: String?,
        val isCredit: Boolean,
        val isDeclined: Boolean,
        val declineReason: String?,
        val isForeign: Boolean,
        val country: String?,
        /** Registered cards/accounts on the user's side of the transaction (distinct, in order). */
        val matchedCards: List<String>,
        /** Four-digit numbers on the user's side that are NOT registered. */
        val unknownCards: List<String>,
        /** For transfers: the other party's account digits and name. */
        val counterpartyAcct: String?,
        val counterpartyName: String?,
        val cities: List<String>,
        val cleanedText: String,
    )

    val DEFAULT_BALANCE_WORDS = listOf("رصيد", "الرصيد", "المتاح", "Balance", "Bal", "Available")
    val DEFAULT_MERCHANT_PREFIXES = listOf("لـ", "لدى", "عند", "من", "At:", "at", "Merchant:", "To:")

    private val LOCAL_CURRENCIES = setOf("SR", "SAR", "ر.س", "رس", "ريال", "﷼")
    private val CURRENCIES = listOf(
        "SAR", "SR", "ر.س", "ريال", "﷼",
        "USD", "EUR", "GBP", "AED", "KWD", "BHD", "QAR", "OMR", "EGP", "JOD", "TRY", "INR", "PKR",
        "CNY", "JPY", "CHF", "CAD", "AUD", "MYR", "IDR", "THB", "MAD",
        "دولار", "يورو", "درهم", "دينار", "جنيه",
        "$", "€", "£",
    )
    private val SAUDI = setOf("SA", "SAU", "KSA", "SAUDI ARABIA", "SAUDI", "السعودية", "المملكة العربية السعودية", "المملكة")

    private val DECLINED_WORDS = listOf(
        "مرفوض", "مرفوضة", "رفض", "رفضت", "لم تتم", "غير ناجحة", "فشل",
        "Declined", "Rejected", "Failed", "Unsuccessful",
    )

    /** Saudi cities in English and Arabic, normalized to one display name. */
    val CITIES: Map<String, String> = linkedMapOf(
        "RIYADH" to "Riyadh", "الرياض" to "Riyadh",
        "JEDDAH" to "Jeddah", "JIDDAH" to "Jeddah", "جدة" to "Jeddah", "جده" to "Jeddah",
        "MAKKAH" to "Makkah", "MECCA" to "Makkah", "مكة" to "Makkah",
        "MADINAH" to "Madinah", "MEDINA" to "Madinah", "المدينة" to "Madinah",
        "DAMMAM" to "Dammam", "الدمام" to "Dammam",
        "KHOBAR" to "Khobar", "الخبر" to "Khobar",
        "DHAHRAN" to "Dhahran", "الظهران" to "Dhahran",
        "JUBAIL" to "Jubail", "الجبيل" to "Jubail",
        "QATIF" to "Qatif", "القطيف" to "Qatif",
        "AHSA" to "Al Ahsa", "HOFUF" to "Al Ahsa", "الأحساء" to "Al Ahsa", "الاحساء" to "Al Ahsa",
        "TAIF" to "Taif", "الطائف" to "Taif",
        "TABUK" to "Tabuk", "تبوك" to "Tabuk",
        "ABHA" to "Abha", "أبها" to "Abha", "ابها" to "Abha",
        "KHAMIS" to "Khamis Mushait", "خميس مشيط" to "Khamis Mushait",
        "BURAIDAH" to "Buraidah", "BURAYDAH" to "Buraidah", "بريدة" to "Buraidah",
        "UNAIZAH" to "Unaizah", "عنيزة" to "Unaizah",
        "HAIL" to "Hail", "حائل" to "Hail",
        "JAZAN" to "Jazan", "JIZAN" to "Jazan", "جازان" to "Jazan",
        "NAJRAN" to "Najran", "نجران" to "Najran",
        "YANBU" to "Yanbu", "ينبع" to "Yanbu",
        "ALULA" to "AlUla", "العلا" to "AlUla",
        "KHARJ" to "Al Kharj", "الخرج" to "Al Kharj",
        "JOUF" to "Al Jouf", "SAKAKA" to "Al Jouf", "سكاكا" to "Al Jouf",
        "ARAR" to "Arar", "عرعر" to "Arar",
        "BAHA" to "Al Baha", "الباحة" to "Al Baha",
    )


    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")

    /** Arabic-Indic and Persian digits to ASCII; Arabic decimal/thousands marks to '.' and ','. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                '٫' -> sb.append('.')
                '٬' -> sb.append(',')
                else -> sb.append(ch)
            }
        }
        return INVISIBLE.replace(sb.toString(), "")
    }

    /** Arabic letter variants folded so "إجمالي" and "اجمالي" compare equal. */
    private fun fold(s: String): String = s.trim().lowercase()
        .replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        .replace("ـ", "").replace(Regex("\\s+"), " ")

    private fun currencyAlternation(): String =
        CURRENCIES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    // Numbers: "1,234.50", "1234", "37.62". The look-ahead stops a date or time being read as money.
    private val NUMBER = "(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(?![\\d/:\\-])"
    private const val SP = "[ \\t\\u00A0]*" // spaces on the same line only
    private val curBefore by lazy { Regex("(?<![A-Za-z])(${currencyAlternation()})$SP:?$SP($NUMBER)", RegexOption.IGNORE_CASE) }
    private val curAfter by lazy { Regex("(?<![\\d.,])($NUMBER)$SP(${currencyAlternation()})(?![A-Za-z])", RegexOption.IGNORE_CASE) }
    private val BARE_NUMBER = Regex(NUMBER)
    private val DATE = Regex("(?<!\\d)\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,4}(?!\\d)")
    private val TIME = Regex("(?<!\\d)\\d{1,2}:\\d{2}(?::\\d{2})?(?!\\d)")
    private val FOUR = Regex("(?<![\\d.,])(\\d{4})(?![\\d]|[.,]\\d)")
    private val FIELD = Regex("^\\s*([^:~\\d]{2,40}?)\\s*[:~]\\s*(.*)$")
    private val TO_LINE = Regex("^(?:لـ|ل|الى|إلى|to)\\s*:?\\s*(\\d{3,})(?:\\s*;\\s*(.*))?$", RegexOption.IGNORE_CASE)
    private val FROM_LINE = Regex("^(?:من|from)\\s*:?\\s*(\\d{3,})(?:\\s*;\\s*(.*))?$", RegexOption.IGNORE_CASE)

    private data class Money(val start: Int, val end: Int, val value: Double, val currency: String)

    private fun moneyIn(text: String, offset: Int = 0): List<Money> {
        val out = mutableListOf<Money>()
        for (m in curBefore.findAll(text)) {
            val v = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
            out += Money(offset + m.range.first, offset + m.range.last, v, canonicalCurrency(m.groupValues[1]))
        }
        for (m in curAfter.findAll(text)) {
            if (out.any { overlaps(it.start, it.end, offset + m.range.first, offset + m.range.last) }) continue
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            out += Money(offset + m.range.first, offset + m.range.last, v, canonicalCurrency(m.groupValues[2]))
        }
        return out.sortedBy { it.start }
    }

    /** "key: value" lines, with keys folded (hamza, ta marbuta, tatweel). */
    private fun fields(body: String): List<Pair<String, String>> = body.lines().mapNotNull { line ->
        FIELD.find(line)?.let { fold(it.groupValues[1]) to it.groupValues[2].trim() }
    }

    private fun field(fs: List<Pair<String, String>>, vararg keys: String): String? {
        val ks = keys.map { fold(it) }
        return fs.firstOrNull { (k, _) -> k in ks }?.second
    }

    private fun fieldContaining(fs: List<Pair<String, String>>, vararg parts: String): String? {
        val ps = parts.map { fold(it) }
        return fs.firstOrNull { (k, _) -> ps.any { k.contains(it) } }?.second
    }

    fun kindOf(body: String): String {
        val l = fold(body)
        fun has(vararg w: String) = w.any { l.contains(fold(it)) }
        return when {
            has("حواله", "حوالة", "تحويل", "transfer") -> when {
                has("وارده", "واردة", "وارد", "incoming", "received", "credited") -> Kind.TRANSFER_IN
                else -> Kind.TRANSFER_OUT
            }
            has("سحب", "صراف", "atm", "withdraw", "cash") -> Kind.WITHDRAWAL
            has("استرداد", "مسترد", "عكس", "refund", "reversal") -> Kind.REFUND
            has("سداد", "فاتوره", "فاتورة", "sadad", "bill payment") -> Kind.BILL
            has("ايداع", "إيداع", "راتب", "deposit", "salary", "credited") -> Kind.CREDIT_IN
            else -> Kind.PURCHASE
        }
    }

    fun parse(rawBody: String, registeredCards: Set<String>, profile: Profile = Profile()): Result {
        val body = normalize(rawBody)
        val work = CharArray(body.length) { body[it] }
        fun blank(range: IntRange) { for (i in range) if (i in work.indices) work[i] = ' ' }
        val fs = fields(body)
        val kind = kindOf(body)

        // 1. Money: numbers attached to a currency marker, with or without decimals.
        val money = moneyIn(body)

        // 2. Balance: money after a balance word ("رصيد:"); a bare number there counts too.
        var balance: Double? = null
        val balanceSpans = mutableListOf<IntRange>()
        for (w in profile.balanceWords.filter { it.isNotBlank() }) {
            val guard = if (w.first().code < 128) "(?<![A-Za-z])" else ""
            val r = Regex(guard + Regex.escape(w) + "$SP:?$SP(?:(${currencyAlternation()})$SP)?($NUMBER)", RegexOption.IGNORE_CASE)
            for (m in r.findAll(body)) {
                val v = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
                if (balance == null) balance = v
                balanceSpans += m.range
            }
        }
        val txMoney = money.filter { mo -> balanceSpans.none { overlaps(mo.start, mo.end, it.first, it.last) } }

        // 3. Amount: the total charged wins; then a riyal equivalent of a foreign amount; then the first amount.
        val amountLine = field(fs, "مبلغ", "المبلغ", "amount")
        val amountMoney = amountLine?.let { moneyIn(it) }.orEmpty()
        val totalLine = fieldContaining(fs, "اجمالي", "total")
        val total = totalLine?.let { t -> moneyIn(t).firstOrNull() ?: BARE_NUMBER.find(t)?.value?.replace(",", "")?.toDoubleOrNull()?.let { Money(0, 0, it, "SAR") } }
        val fees = fieldContaining(fs, "رسوم", "fees", "fee")?.let { BARE_NUMBER.find(it)?.value?.replace(",", "")?.toDoubleOrNull() }
        val rate = fieldContaining(fs, "سعر الصرف", "exchange rate", "rate")?.let { BARE_NUMBER.find(it)?.value?.toDoubleOrNull() }
        val first = amountMoney.firstOrNull() ?: txMoney.firstOrNull()
        val sarEquivalent = amountMoney.drop(1).firstOrNull { it.currency == "SAR" }
        val charged: Money? = total ?: sarEquivalent ?: first
        val orig = if (charged != null && first != null && (first.currency != charged.currency || first.value != charged.value)) first else null

        money.forEach { blank(it.start..it.end) }
        balanceSpans.forEach { blank(it) }
        DATE.findAll(body).forEach { blank(it.range) }
        TIME.findAll(body).forEach { blank(it.range) }
        val cleaned = String(work)

        // 4. Whose card/account? Transfers name both sides; take the user's side only.
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val to = lines.firstNotNullOfOrNull { TO_LINE.find(it) }
        val from = lines.firstNotNullOfOrNull { FROM_LINE.find(it) }
        var counterAcct: String? = null
        var counterName: String? = null
        val ownCandidates: List<String>
        if ((kind == Kind.TRANSFER_IN || kind == Kind.TRANSFER_OUT) && (to != null || from != null)) {
            val toD = to?.groupValues?.get(1)?.takeLast(4)
            val fromD = from?.groupValues?.get(1)?.takeLast(4)
            val incoming = when {
                kind == Kind.TRANSFER_IN -> true
                fromD != null && fromD in registeredCards -> false
                toD != null && toD in registeredCards && (fromD == null || fromD !in registeredCards) -> true
                else -> false
            }
            val own = if (incoming) toD else fromD
            val other = if (incoming) from else to
            counterAcct = other?.groupValues?.get(1)
            counterName = other?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            ownCandidates = listOfNotNull(own)
        } else {
            val cardField = field(fs, "بطاقة", "البطاقة", "card")
            val fromField = cardField?.let { FOUR.find(normalize(it))?.groupValues?.get(1) }
            ownCandidates = (listOfNotNull(fromField) + FOUR.findAll(cleaned).map { it.groupValues[1] }).distinct()
        }
        val matched = ownCandidates.filter { it in registeredCards }.distinct()
        val unknown = ownCandidates.filter { it !in registeredCards }.distinct()

        // 5. Status, country, place.
        val lower = body.lowercase()
        val isDeclined = DECLINED_WORDS.any { lower.contains(it.lowercase()) }
        val declineReason = if (isDeclined) lines.firstOrNull { l -> DECLINED_WORDS.any { l.lowercase().contains(it.lowercase()) } }
            ?.substringAfter(':', "")?.trim()?.trimEnd('.', '،')?.trim()?.takeIf { it.isNotEmpty() } else null
        val country = field(fs, "دولة", "الدولة", "country")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val foreignCountry = country != null && country !in SAUDI
        val currency = charged?.currency
        val isForeign = foreignCountry || (currency != null && currency !in LOCAL_CURRENCIES) ||
            (orig != null && orig.currency !in LOCAL_CURRENCIES && orig.currency != "SAR")

        val upper = body.uppercase()
        val cities = CITIES.entries.filter { (k, _) ->
            if (k.first() in 'A'..'Z') Regex("(?<![A-Z])${Regex.escape(k)}(?![A-Z])").containsMatchIn(upper)
            else body.contains(k)
        }.map { it.value }.distinct()

        val isTransfer = kind == Kind.TRANSFER_IN || kind == Kind.TRANSFER_OUT
        return Result(
            isTransaction = charged != null,
            kind = kind,
            amount = charged?.value,
            currency = currency,
            origAmount = orig?.value,
            origCurrency = orig?.currency,
            fees = fees,
            rate = rate,
            balance = balance,
            merchant = if (isTransfer) null else findMerchant(body, fs, profile),
            method = if (isTransfer) null else findMethod(body, fs, matched.firstOrNull() ?: unknown.firstOrNull()),
            type = findType(body, fs),
            isCredit = kind in Kind.INFLOW,
            isDeclined = isDeclined,
            declineReason = declineReason,
            isForeign = isForeign,
            country = country,
            matchedCards = matched,
            unknownCards = unknown,
            counterpartyAcct = counterAcct,
            counterpartyName = counterName,
            cities = cities,
            cleanedText = cleaned,
        )
    }

    private fun overlaps(a1: Int, a2: Int, b1: Int, b2: Int) = a1 <= b2 && b1 <= a2

    fun canonicalCurrency(c: String): String = when (c.uppercase()) {
        "SR", "SAR", "ر.س", "ريال", "﷼" -> "SAR"
        "$", "دولار" -> "USD"
        "€", "يورو" -> "EUR"
        "£" -> "GBP"
        "درهم" -> "AED"
        else -> c.uppercase()
    }

    private fun cleanName(raw: String): String {
        var name = raw.replace(Regex("\\s+(?:on|في|بتاريخ)\\s+.*$", RegexOption.IGNORE_CASE), "")
        name = TIME.replace(DATE.replace(name, ""), "")
        return name.trim().trim(':', ';', ',', '.', ' ', '-').trim()
    }

    private fun findMerchant(body: String, fs: List<Pair<String, String>>, profile: Profile): String? {
        // Labeled fields first: "لدى:Half Mill", "التاجر: …", or "الحساب: noon.com" (letters = merchant).
        field(fs, "لدى", "عند", "التاجر", "merchant", "at")?.let(::cleanName)?.takeIf { it.any(Char::isLetter) }?.let { return it }
        field(fs, "الحساب", "حساب")?.let(::cleanName)?.takeIf { it.any(Char::isLetter) }?.let { return it }
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            for (p in profile.merchantPrefixes.filter { it.isNotBlank() }) {
                val ascii = p.first().code < 128
                val after = if (p.endsWith(":")) "\\s*" else "\\s+"
                val m = if (ascii) {
                    Regex("(?:^|\\s)" + Regex.escape(p) + after + "(.+)$", RegexOption.IGNORE_CASE).find(line)
                } else {
                    Regex("^" + Regex.escape(p) + "\\s*(.+)$").find(line)
                } ?: continue
                val name = cleanName(m.groupValues[1])
                if (name.isNotEmpty() && name.any { it.isLetter() } && !looksLikeMoney(name)) return name
            }
        }
        return null
    }

    private fun looksLikeMoney(s: String) = curBefore.containsMatchIn(s) && s.length < 20

    private fun findMethod(body: String, fs: List<Pair<String, String>>, card: String?): String? {
        field(fs, "بطاقة", "البطاقة", "card")?.let { v ->
            v.substringAfter(';', "").trim().takeIf { it.any(Char::isLetter) }?.let { return it }
        }
        if (card == null) return null
        val line = body.lines().firstOrNull { it.contains(card) } ?: return null
        val rest = line.replace(Regex("[xX*#]*" + card), " ").replace("عبر", " ")
            .replace(Regex("البطاقة|بطاقة"), " ")
            .replace(Regex("(?i)(?<![A-Za-z])(via|card|ending|with)(?![A-Za-z])"), " ")
            .replace(Regex("[;:]+"), " ")
            .trim()
        return rest.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }?.replace(Regex("\\s+"), " ")
    }

    private fun findType(body: String, fs: List<Pair<String, String>>): String? {
        field(fs, "العملية", "نوع العملية", "type")?.let { v -> v.trim().takeIf { it.any(Char::isLetter) }?.let { return it } }
        val first = body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        var t = first
        moneyIn(first).sortedByDescending { it.start }.forEach { t = t.removeRange(it.start, it.end + 1) }
        t = t.trim().replace(Regex("\\s+بـ?$"), "").trim()
        t = t.replace(Regex("\\s*:\\s*"), ": ").trim().trimEnd(':').trim()
        return t.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
    }
}
