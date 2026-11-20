package app.riyaly

/** Groups merchants into categories by keyword. The user can override any merchant. */
object Categories {
    const val FOOD_DELIVERY = "food_delivery"
    const val RESTAURANTS = "restaurants"
    const val CAFES = "cafes"
    const val GROCERIES = "groceries"
    const val FUEL = "fuel"
    const val SHOPPING = "shopping"
    const val ONLINE = "online"
    const val PHARMACY = "pharmacy"
    const val TELECOM = "telecom"
    const val TRANSPORT = "transport"
    const val BILLS = "bills"
    const val CASH = "cash"
    const val FEES = "fees"
    const val OTHER = "other"

    val ALL = listOf(FOOD_DELIVERY, RESTAURANTS, CAFES, GROCERIES, FUEL, SHOPPING, ONLINE, PHARMACY, TELECOM, TRANSPORT, BILLS, FEES, CASH, OTHER)

    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        FOOD_DELIVERY to listOf("HUNGERSTATION", "HUNGERSTA", "JAHEZ", "TOYOU", "MRSOOL", "KEETA", "NINJA", "CAREEM FOOD", "TALABAT", "THE CHEFZ", "CHEFZ", "جاهز", "هنقرستيشن", "مرسول"),
        CAFES to listOf("STARBUCKS", "CAFE", "COFFEE", "KAFE", "BARNS", "DUNKIN", "TIM HORTONS", "HALF MILLION", "HALF MILL", "DOSE", "CARIBOU", "COSTA", "قهوة", "كافيه"),
        RESTAURANTS to listOf("RESTAURANT", "MCDONALD", "KFC", "ALBAIK", "AL BAIK", "BURGER", "PIZZA", "SHAWARMA", "HARDEE", "SUBWAY", "KUDU", "HERFY", "MAESTRO", "GRILL", "KITCHEN", "مطعم", "البيك"),
        GROCERIES to listOf("PANDA", "TAMIMI", "OTHAIM", "DANUBE", "CARREFOUR", "LULU", "NESTO", "HYPER", "SUPERMARKET", "MARKET", "BINDAWOOD", "FARM", "NANA", "BAQALA", "بقالة", "أسواق", "اسواق", "هايبر"),
        FUEL to listOf("ALDREES", "SASCO", "PETROMIN", "NAFT", "FUEL", "PETROL", "GAS STATION", "STATION", "محطة", "وقود"),
        PHARMACY to listOf("PHARMACY", "NAHDI", "DAWAA", "WHITES", "صيدلية", "النهدي"),
        TELECOM to listOf("STC", "MOBILY", "ZAIN", "SALAM", "VIRGIN MOBILE", "LEBARA", "موبايلي", "زين"),
        TRANSPORT to listOf("UBER", "CAREEM", "BOLT", "JEENY", "SAPTCO", "RIYADH METRO", "PARKING", "SAR TRAIN", "FLYNAS", "SAUDIA", "FLYADEAL"),
        ONLINE to listOf("AMAZON", "NOON", "APPLE.COM", "APPLE", "GOOGLE", "NETFLIX", "SHAHID", "SPOTIFY", "ANGHAMI", "PLAYSTATION", "STEAM", "ALIEXPRESS", "TEMU", "SHEIN", "NAMSHI", "PAYPAL", "OSN"),
        SHOPPING to listOf("ZARA", "H&M", "CENTREPOINT", "CENTRE POINT", "MAX FASHION", "SPLASH", "IKEA", "EXTRA", "JARIR", "SACO", "MALL", "NIKE", "ADIDAS", "SEPHORA", "BATH", "مول", "جرير", "اكسترا"),
        BILLS to listOf("SADAD", "سداد", "ELECTRICITY", "SEC ", "WATER", "NWC", "ABSHER", "أبشر", "MOI"),
    )

    fun guess(merchant: String?): String {
        if (merchant.isNullOrBlank()) return OTHER
        val m = merchant.uppercase()
        for ((cat, words) in KEYWORDS) if (words.any { m.contains(it.uppercase()) }) return cat
        return OTHER
    }

    fun of(db: Db, merchant: String?): String {
        if (merchant.isNullOrBlank()) return OTHER
        return db.merchant(merchant)?.category ?: guess(merchant)
    }

    /** Category of a transaction: cash withdrawals and bill payments have their own. */
    fun forTxn(db: Db, t: Txn): String = when (t.kind) {
        Parser.Kind.WITHDRAWAL -> CASH
        Parser.Kind.BILL -> BILLS
        Parser.Kind.FEE -> FEES
        else -> of(db, t.merchant)
    }

    fun label(ctx: android.content.Context, cat: String): String {
        val id = when (cat) {
            FOOD_DELIVERY -> R.string.cat_food_delivery
            RESTAURANTS -> R.string.cat_restaurants
            CAFES -> R.string.cat_cafes
            GROCERIES -> R.string.cat_groceries
            FUEL -> R.string.cat_fuel
            SHOPPING -> R.string.cat_shopping
            ONLINE -> R.string.cat_online
            PHARMACY -> R.string.cat_pharmacy
            TELECOM -> R.string.cat_telecom
            TRANSPORT -> R.string.cat_transport
            BILLS -> R.string.cat_bills
            CASH -> R.string.cat_cash
            FEES -> R.string.cat_fees
            else -> R.string.cat_other
        }
        return ctx.getString(id)
    }
}
