package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The week's Top 10s, from JustWatch's public GraphQL API (the one its own site uses; no key):
 * the most popular films and series in the chosen region right now, and the best reviewed of
 * those, each with its scores on IMDb, Rotten Tomatoes, TMDB and JustWatch, its cast and where it
 * streams. Opt-in ([Prefs.chartsEnabled]); nothing is requested while the widget is off.
 *
 * The API is not documented and its schema moves, so the request is built in levels, from the
 * richest to the barest, and steps down when JustWatch rejects a field; the level that worked is
 * remembered for a day. Answers are kept on disk for six hours.
 */
object Charts {
    data class Title(
        val id: String,
        val show: Boolean,
        val name: String,
        val year: Int,
        val runtime: Int,
        val age: String,
        val description: String,
        val genres: List<String>,
        val poster: String?,
        val backdrop: String?,
        val imdb: Double?,
        val imdbVotes: Int?,
        val tmdb: Double?,
        val jw: Double?,
        val tomato: Int?,
        val fresh: Boolean,
        val cast: List<String>,
        val director: String?,
        val provider: String?,
        val providerKind: String?,
        /** IMDb id ("tt0111161"): what Stremio and Nuvio know a title by. */
        val imdbId: String? = null,
        val tmdbId: String? = null,
        /** Every service offering it, best first. */
        val offers: List<Offer> = emptyList(),
    ) {
        /** One score from 0 to 100 that critics and audiences would broadly agree on, or null with too little to go on. */
        val criticScore: Double?
            get() {
                if (imdbVotes != null && imdbVotes < 1500 && tomato == null) return null
                val parts = listOfNotNull(tomato?.toDouble(), imdb?.times(10), tmdb?.times(10))
                return if (parts.isEmpty()) null else parts.average()
            }
    }

    /**
     * A service offering a title: its name, JustWatch's technical name for it, how it is offered
     * (FLATRATE, FREE, ADS, RENT, BUY) and the service's own page for the title, which TV apps
     * usually open straight at the title.
     */
    data class Offer(val name: String, val technical: String, val kind: String, val url: String?)

    /**
     * A chart: [label] heads the widget, [short] names its tab, [rank] places a title in it
     * ("No. 3 in Movies This Week"). String resources, resolved when shown, so a change of the
     * TV's language applies at once.
     */
    data class Chart(val key: String, val label: Int, val short: Int, val rank: Int, val items: List<Title>)

    private const val TTL = 6 * 60 * 60_000L
    private const val ENDPOINT = "https://apis.justwatch.com/graphql"
    private const val LEVELS = 6
    @Volatile private var memo: Pair<Long, List<Chart>>? = null
    /** When the last load failed: the next waits a while instead of trying again every minute. */
    @Volatile private var failedAt = 0L
    private const val BACKOFF = 20 * 60_000L

    /** The charts known without waiting: memory, then disk; null before the first successful load. */
    fun cached(ctx: Context): List<Chart>? = memo?.second ?: runCatching {
        val m = file(ctx, "movie"); val s = file(ctx, "show")
        if (!m.isFile || !s.isFile) null
        else build(parse(JSONObject(m.readText())), parse(JSONObject(s.readText()))).also { memo = minOf(m.lastModified(), s.lastModified()) to it }
    }.getOrNull()

    /** Blocking. Fresh charts when the last are older than six hours (or [force]); the last ones when offline. */
    fun load(ctx: Context, force: Boolean = false): List<Chart>? {
        val last = cached(ctx)
        val at = memo?.first ?: 0L
        val country = country(ctx)
        val sp = ctx.getSharedPreferences("charts", Context.MODE_PRIVATE)
        val sameCountry = sp.getString("country", country) == country
        if (!force && last != null && sameCountry && System.currentTimeMillis() - at < TTL) return last
        if (!force && System.currentTimeMillis() - failedAt < BACKOFF) return last
        return try {
            // JustWatch doesn't cover every country; the US charts beat none.
            val movies = runCatching { fetch(ctx, country, "MOVIE") }.getOrElse { if (country == "US") throw it else fetch(ctx, "US", "MOVIE") }
            val shows = runCatching { fetch(ctx, country, "SHOW") }.getOrElse { if (country == "US") throw it else fetch(ctx, "US", "SHOW") }
            file(ctx, "movie").writeText(movies.toString())
            file(ctx, "show").writeText(shows.toString())
            sp.edit().putString("country", country).apply()
            build(parse(movies), parse(shows)).also { memo = System.currentTimeMillis() to it; failedAt = 0L }
        } catch (e: Exception) {
            Log.w("Charts", "load failed: ${e.message}")
            failedAt = System.currentTimeMillis()
            last
        }
    }

    /**
     * The region whose charts to show: chosen in Settings, else the TV's own region setting (never
     * located automatically). JustWatch has no catalogue for mainland China, so the US stands in.
     */
    fun country(ctx: Context): String {
        Prefs(ctx).chartsCountry.takeIf { it.length == 2 }?.let { return it.uppercase(Locale.US) }
        val tv = Locale.getDefault().country.uppercase(Locale.US)
        return if (tv.length == 2 && tv !in UNSUPPORTED) tv else "US"
    }

    /** Regions JustWatch publishes no charts for. */
    private val UNSUPPORTED = setOf("CN")

    private fun file(ctx: Context, type: String) = File(ctx.filesDir, "charts-$type.json")

    /** One type's 40 most popular titles, stepping down through the request levels until one is accepted. */
    private fun fetch(ctx: Context, country: String, type: String): JSONObject {
        val sp = ctx.getSharedPreferences("charts", Context.MODE_PRIVATE)
        val day = System.currentTimeMillis() / 86_400_000L
        var level = if (sp.getLong("level_day", -1) == day) sp.getInt("level", 0) else 0
        var lastError: Exception? = null
        while (level < LEVELS) {
            try {
                val body = JSONObject()
                    .put("operationName", "TigrisCharts")
                    .put("query", query(level))
                    .put("variables", JSONObject()
                        .put("country", country)
                        .put("language", "en")
                        .put("first", 40)
                        .put("filter", JSONObject().put("objectTypes", JSONArray().put(type))))
                val text = Net.postJson(ENDPOINT, body.toString(), mapOf("Origin" to "https://www.justwatch.com", "Referer" to "https://www.justwatch.com/")).decodeToString()
                val o = JSONObject(text)
                val edges = o.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")
                if (edges != null && edges.length() > 0) {
                    sp.edit().putInt("level", level).putLong("level_day", day).apply()
                    return o
                }
                lastError = java.io.IOException(o.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "no titles")
            } catch (e: java.io.IOException) {
                // A rejected query answers 400 (or 422) with the GraphQL errors. Anything else, a 403
                // or 429 included, is the network or a refusal, and a simpler query won't help.
                if (e.message?.startsWith("HTTP 400") != true && e.message?.startsWith("HTTP 422") != true) throw e
                lastError = e
            }
            Log.w("Charts", "level $level rejected: ${lastError?.message}")
            level++
        }
        throw lastError ?: java.io.IOException("charts unavailable")
    }

    /** The GraphQL request, richest at level 0; each level leaves out what is likeliest to have moved. */
    private fun query(level: Int): String {
        val d = '$'
        val poster = if (level < 4) "posterUrl(profile: S332, format: JPG)" else "posterUrl"
        val backdrops = if (level < 4) "backdrops(profile: S1440, format: JPG) { backdropUrl }" else "backdrops { backdropUrl }"
        val scoring = when (level) {
            0, 1, 2 -> "scoring { imdbScore imdbVotes tmdbScore jwRating tomatoMeter certifiedFresh }"
            3, 4 -> "scoring { imdbScore imdbVotes tmdbScore }"
            else -> ""
        }
        val credits = if (level <= 2) "credits { role name }" else ""
        val ids = if (level <= 3) "externalIds { imdbId tmdbId }" else ""
        val offers = when (level) {
            0 -> "offers(country: ${d}country, platform: WEB, filter: { bestOnly: true }) { monetizationType standardWebURL package { clearName technicalName } }"
            1 -> "offers(country: ${d}country, platform: WEB, filter: { bestOnly: true }) { monetizationType package { clearName technicalName } }"
            else -> ""
        }
        val extra = if (level < 5) "runtime ageCertification shortDescription genres { shortName }" else ""
        return """
            query TigrisCharts(${d}country: Country!, ${d}language: Language!, ${d}first: Int!, ${d}filter: TitleFilter) {
              popularTitles(country: ${d}country, first: ${d}first, filter: ${d}filter, sortBy: POPULAR) {
                edges { node { id objectType
                  content(country: ${d}country, language: ${d}language) { title originalReleaseYear $extra $poster $backdrops $scoring $credits $ids }
                  $offers
                } }
              }
            }
        """.trimIndent()
    }

    internal fun parse(o: JSONObject): List<Title> {
        val edges = o.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<Title>()
        for (k in 0 until edges.length()) {
            val node = edges.optJSONObject(k)?.optJSONObject("node") ?: continue
            val c = node.optJSONObject("content") ?: continue
            val name = c.optString("title").takeIf { it.isNotBlank() } ?: continue
            val score = c.optJSONObject("scoring")
            val credits = c.optJSONArray("credits")
            val cast = ArrayList<String>()
            var director: String? = null
            if (credits != null) for (i in 0 until credits.length()) {
                val cr = credits.optJSONObject(i) ?: continue
                when (cr.optString("role")) {
                    "ACTOR" -> if (cast.size < 4) cr.optString("name").takeIf { it.isNotBlank() }?.let(cast::add)
                    "DIRECTOR" -> if (director == null) director = cr.optString("name").takeIf { it.isNotBlank() }
                }
            }
            val offerList = parseOffers(node.optJSONArray("offers"))
            val provider = offerList.firstOrNull()?.name
            val kind = offerList.firstOrNull()?.kind
            val ext = c.optJSONObject("externalIds")
            val genres = c.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("shortName")?.takeIf { s -> s in GENRES } } }.orEmpty()
            out += Title(
                id = node.optString("id", name),
                show = node.optString("objectType") == "SHOW",
                name = name,
                year = c.optInt("originalReleaseYear"),
                runtime = c.optInt("runtime"),
                age = c.optString("ageCertification").takeUnless { it == "null" }.orEmpty(),
                description = c.optString("shortDescription").takeUnless { it == "null" }.orEmpty(),
                genres = genres.take(2),
                poster = image(c.optString("posterUrl"), "s332"),
                backdrop = c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")?.let { image(it, "s1440") },
                imdb = score?.optDouble("imdbScore")?.takeIf { !it.isNaN() && it > 0 },
                imdbVotes = score?.optInt("imdbVotes")?.takeIf { it > 0 },
                tmdb = score?.optDouble("tmdbScore")?.takeIf { !it.isNaN() && it > 0 },
                jw = score?.optDouble("jwRating")?.takeIf { !it.isNaN() && it > 0 },
                tomato = score?.optInt("tomatoMeter", -1)?.takeIf { it >= 0 },
                fresh = score?.optBoolean("certifiedFresh") == true,
                cast = cast,
                director = director,
                provider = provider,
                providerKind = kind,
                imdbId = ext?.optString("imdbId")?.takeIf { it.startsWith("tt") },
                tmdbId = ext?.optString("tmdbId")?.takeIf { it.isNotBlank() && it != "null" && it != "0" },
                offers = offerList,
            )
        }
        return out
    }

    /** Offers ordered best first, one per service: a subscription beats free with ads beats renting or buying. */
    internal fun parseOffers(offers: JSONArray?): List<Offer> {
        if (offers == null) return emptyList()
        val rank = mapOf("FLATRATE" to 0, "FREE" to 1, "ADS" to 2, "RENT" to 3, "BUY" to 4)
        val all = ArrayList<Pair<Int, Offer>>()
        for (i in 0 until offers.length()) {
            val of = offers.optJSONObject(i) ?: continue
            val kind = of.optString("monetizationType")
            val r = rank[kind] ?: continue
            val pkg = of.optJSONObject("package") ?: continue
            val name = pkg.optString("clearName").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val url = of.optString("standardWebURL").takeIf { it.startsWith("http") }
            all += r to Offer(name, pkg.optString("technicalName"), kind, url)
        }
        return all.sortedBy { it.first }.map { it.second }.distinctBy { it.name }
    }

    /** JustWatch picture paths are relative and sometimes still carry their {profile} and {format} slots. */
    private fun image(u: String?, profile: String): String? {
        if (u.isNullOrBlank() || u == "null") return null
        var s = u.replace("{profile}", profile).replace("{format}", "jpg")
        if (s.startsWith("//")) s = "https:$s"
        if (s.startsWith("/")) s = "https://images.justwatch.com$s"
        return s
    }

    internal fun build(movies: List<Title>, shows: List<Title>): List<Chart> {
        val critics = (movies.take(30) + shows.take(30)).distinctBy { it.id }
            .filter { it.criticScore != null }
            .sortedByDescending { it.criticScore }
            .take(10)
        return listOf(
            Chart("movies", R.string.i18n_charts_movies, R.string.i18n_charts_movies_short, R.string.i18n_charts_movies_rank, movies.take(10)),
            Chart("shows", R.string.i18n_charts_shows, R.string.i18n_charts_shows_short, R.string.i18n_charts_shows_rank, shows.take(10)),
            Chart("critics", R.string.i18n_charts_critics, R.string.i18n_charts_critics_short, R.string.i18n_charts_critics_rank, critics),
        ).filter { it.items.isNotEmpty() }
    }

    /** JustWatch's genre codes, kept as codes and named in the TV's language when shown ([genre]). */
    private val GENRES = mapOf(
        "act" to R.string.i18n_charts_genre_act, "ani" to R.string.i18n_charts_genre_ani, "cmy" to R.string.i18n_charts_genre_cmy,
        "crm" to R.string.i18n_charts_genre_crm, "doc" to R.string.i18n_charts_genre_doc, "drm" to R.string.i18n_charts_genre_drm,
        "eur" to R.string.i18n_charts_genre_eur, "fml" to R.string.i18n_charts_genre_fml, "fnt" to R.string.i18n_charts_genre_fnt,
        "hst" to R.string.i18n_charts_genre_hst, "hrr" to R.string.i18n_charts_genre_hrr, "msc" to R.string.i18n_charts_genre_msc,
        "rma" to R.string.i18n_charts_genre_rma, "scf" to R.string.i18n_charts_genre_scf, "spt" to R.string.i18n_charts_genre_spt,
        "trl" to R.string.i18n_charts_genre_trl, "war" to R.string.i18n_charts_genre_war, "wsn" to R.string.i18n_charts_genre_wsn,
        "rly" to R.string.i18n_charts_genre_rly,
    )

    /** A genre code's name in the TV's language. */
    fun genre(code: String): String = GENRES[code]?.let { I18n.text(it) } ?: code

    /** "2h 14m", or "52m" an episode, in the TV's language. */
    fun runtime(min: Int): String = when {
        min <= 0 -> ""
        min < 60 -> I18n.text(R.string.i18n_charts_minutes, min)
        min % 60 == 0 -> I18n.text(R.string.i18n_charts_hours, min / 60)
        else -> I18n.text(R.string.i18n_charts_hours_minutes, min / 60, min % 60)
    }

    /**
     * The installed app that plays [provider] ("Netflix", "Disney Plus"…): matched by its name
     * against the apps' labels, then by a few well-known TV packages.
     */
    fun providerApp(apps: List<AppEntry>, provider: String): AppEntry? {
        val p = provider.lowercase(Locale.US).replace("+", " plus").replace(Regex("[^a-z0-9 ]"), "").trim()
        val first = p.substringBefore(' ')
        apps.firstOrNull { it.label.lowercase(Locale.US).replace("+", " plus").contains(p) }?.let { return it }
        val known = KNOWN.entries.firstOrNull { p.startsWith(it.key) }?.value.orEmpty()
        apps.firstOrNull { it.pkg in known }?.let { return it }
        return apps.firstOrNull { first.length >= 4 && it.label.lowercase(Locale.US).startsWith(first) }
    }

    private val KNOWN = mapOf(
        "netflix" to setOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "amazon" to setOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "prime" to setOf("com.amazon.amazonvideo.livingroom"),
        "disney" to setOf("com.disney.disneyplus"),
        "max" to setOf("com.wbd.stream", "com.hbo.hbonow"),
        "hbo" to setOf("com.wbd.stream", "com.hbo.hbonow"),
        "apple" to setOf("com.apple.atve.androidtv.appletv"),
        "hulu" to setOf("com.hulu.livingroomplus"),
        "paramount" to setOf("com.cbs.ott"),
        "peacock" to setOf("com.peacocktv.peacockandroid"),
        "youtube" to setOf("com.google.android.youtube.tv", "com.liskovsoft.smarttubetv.beta"),
        "crunchyroll" to setOf("com.crunchyroll.crunchyroid"),
        "movistar" to setOf("es.plus.yomvi"),
    )
}

/** Posters and backdrops for the charts, decoded off the main thread and kept in a small memory cache. */
object ChartImages {
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    /** Lets every picture go; they are on disk and decode again when next shown. */
    fun trim() = cache.evictAll()

    /** Calls [done] on the main thread with the picture (null if it can't be had). */
    fun load(ctx: Context, url: String?, maxWidth: Int, rgb565: Boolean = false, done: (Bitmap?) -> Unit) {
        if (url == null) { done(null); return }
        cache.get(url)?.let { done(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val b = Net.image(app, url, "charts", maxWidth, rgb565)
            if (b != null) cache.put(url, b)
            main.post { done(b) }
        }
    }
}
