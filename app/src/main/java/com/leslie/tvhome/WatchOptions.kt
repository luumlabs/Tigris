package com.leslie.tvhome

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast

/**
 * Where a title from the Top 10 can be watched on this TV:
 *
 * - the streaming services JustWatch lists for it whose apps are installed, opened at the title
 *   when the app accepts the service's own link for it, or else just opened;
 * - Nuvio, at the title, by its IMDb id (`nuvio://detail/movie/tt0111161`) or TMDB id
 *   (`nuvio://tmdb/movie/603`), as NuvioTV's DeepLinkParser reads them;
 * - Stremio, at the title (`stremio:///detail/movie/tt0111161/tt0111161`, Stremio's documented
 *   deep link), or searching for it by name when the IMDb id is unknown.
 *
 * Every link is resolved against the apps actually installed before it is offered, and each option
 * keeps the app's own launcher as a fallback, so whatever is listed opens.
 */
object WatchOptions {
    /** One way to watch, in the app called [name]: tried in order, the first [intents] that starts wins. */
    class Option(val label: String, val name: String, val pkg: String, val icon: Drawable?, val intents: List<Intent>, val atTitle: Boolean)

    private fun isNuvio(pkg: String) = pkg.contains("nuvio", ignoreCase = true)

    /** Blocking (package queries): call off the main thread. */
    fun forTitle(ctx: Context, t: Charts.Title, apps: List<AppEntry>): List<Option> {
        val pm = ctx.packageManager
        val type = if (t.show) "series" else "movie"
        val services = LinkedHashMap<String, Option>()
        val rentals = LinkedHashMap<String, Option>()
        for (offer in t.offers) {
            val app = Charts.providerApp(apps, offer.name) ?: continue
            val pkg = app.component.packageName
            if (pkg in services || pkg in rentals || pkg == ctx.packageName) continue
            val link = offer.url?.let { serviceLink(pm, it, pkg) }
            val intents = listOfNotNull(link, launcher(pm, pkg))
            if (intents.isEmpty()) continue
            val name = label(pm, pkg) ?: app.label
            val rent = offer.kind == "RENT" || offer.kind == "BUY"
            val verb = when (offer.kind) {
                "RENT", "BUY" -> R.string.i18n_charts_rent_on
                "FREE", "ADS" -> R.string.i18n_charts_free_on
                else -> R.string.i18n_charts_watch_on
            }
            (if (rent) rentals else services)[pkg] = Option(I18n.text(verb, name), name, pkg, icon(pm, pkg), intents, link != null)
        }

        val players = LinkedHashMap<String, Option>()
        // Nuvio: by IMDb id, or TMDB id; it has no search link.
        val nuvio = when {
            t.imdbId != null -> "nuvio://detail/$type/${t.imdbId}"
            t.tmdbId != null -> "nuvio://tmdb/$type/${t.tmdbId}"
            else -> null
        }
        if (nuvio != null) for (pkg in handlers(pm, nuvio)) {
            if (pkg in services || pkg in rentals) continue
            val name = label(pm, pkg) ?: "Nuvio"
            players[pkg] = Option(I18n.text(R.string.i18n_charts_open_in, name), name, pkg, icon(pm, pkg),
                listOfNotNull(view(nuvio, pkg), launcher(pm, pkg)), atTitle = true)
        }
        // Nuvio installed but the title's ids unknown: at least open the app.
        apps.map { it.component.packageName }.distinct().filter { isNuvio(it) && it !in players }.forEach { pkg ->
            val name = label(pm, pkg) ?: "Nuvio"
            launcher(pm, pkg)?.let { players[pkg] = Option(I18n.text(R.string.i18n_charts_open_app, name), name, pkg, icon(pm, pkg), listOf(it), atTitle = false) }
        }
        // Stremio: at the title by IMDb id, or a search by name. NuvioTV also answers stremio://
        // links, but only to install add-ons, so it is left out here.
        val stremio = when {
            t.imdbId != null && !t.show -> "stremio:///detail/movie/${t.imdbId}/${t.imdbId}"
            t.imdbId != null -> "stremio:///detail/series/${t.imdbId}"
            else -> "stremio:///search?search=" + Uri.encode(t.name)
        }
        for (pkg in handlers(pm, stremio)) {
            if (isNuvio(pkg) || pkg in players || pkg in services || pkg in rentals) continue
            val name = label(pm, pkg) ?: "Stremio"
            players[pkg] = Option(I18n.text(R.string.i18n_charts_open_in, name), name, pkg, icon(pm, pkg),
                listOfNotNull(view(stremio, pkg), launcher(pm, pkg)), atTitle = t.imdbId != null)
        }
        return services.values + players.values + rentals.values
    }

    /** The services that offer [t] but whose apps aren't on this TV, by name. */
    fun elsewhere(t: Charts.Title, options: List<Option>, apps: List<AppEntry>): List<String> {
        val here = options.map { it.pkg }.toSet()
        return t.offers.filter { o -> Charts.providerApp(apps, o.name)?.component?.packageName.let { it == null || it !in here } }
            .map { it.name }.distinct()
    }

    /** Starts [o]: at the title if the app takes the link, else the app itself. */
    fun open(ctx: Context, o: Option) {
        for (i in o.intents) {
            try {
                ctx.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(ctx, I18n.text(R.string.i18n_homeactivity_005, o.name), Toast.LENGTH_SHORT).show()
    }

    /**
     * The service's page for the title, sent to its app, if the app declares it takes such links.
     * Netflix's TV app takes /watch/ links where JustWatch gives /title/ ones, so both are tried.
     */
    private fun serviceLink(pm: PackageManager, url: String, pkg: String): Intent? {
        val tries = mutableListOf(url, url.replaceFirst("http://", "https://"))
        Regex("netflix\\.com/(?:[a-z-]+/)?title/(\\d+)").find(url)?.let { tries += "https://www.netflix.com/watch/${it.groupValues[1]}" }
        for (u in tries.distinct()) {
            val i = view(u, pkg)
            if (pkg.startsWith("com.netflix")) i.putExtra("source", "30")
            if (pm.queryIntentActivities(i, 0).isNotEmpty()) return i
        }
        return null
    }

    /** The packages with an activity that opens [uri]. */
    private fun handlers(pm: PackageManager, uri: String): List<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), 0)
            .mapNotNull { it.activityInfo?.packageName }.distinct()

    private fun view(uri: String, pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setPackage(pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun launcher(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun label(pm: PackageManager, pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim() }.getOrNull()?.ifEmpty { null }

    private fun icon(pm: PackageManager, pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
}
