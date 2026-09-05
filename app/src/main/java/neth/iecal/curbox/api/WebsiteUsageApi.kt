package neth.iecal.curbox.api

import neth.iecal.curbox.data.db.WebsiteHourlyUsageCodec
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import java.util.Locale

/**
 * Privacy preserving projection of Curbox's website statistics for approved API clients.
 *
 * Curbox stores URL path identifiers so its own UI can show a detailed breakdown. The public API
 * intentionally aggregates those rows to domains before they leave the Curbox process. Search
 * text accidentally observed in an address bar is rejected rather than exposed.
 */
object WebsiteUsageApi {
    private const val HOURS_PER_DAY = 24
    private val domainPattern = Regex(
        "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
        RegexOption.IGNORE_CASE,
    )

    fun aggregate(
        rows: List<WebsiteStatsEntity>,
        nativeToIsoDate: Map<String, String>,
        startDate: String,
        endDate: String,
        timeZone: String,
    ): Map<String, Any> {
        val totals = linkedMapOf<Pair<String, String>, Aggregate>()

        rows.forEach { row ->
            val isoDate = nativeToIsoDate[row.date] ?: return@forEach
            val domain = normalizeDomain(row.domain) ?: return@forEach
            val aggregate = totals.getOrPut(isoDate to domain) { Aggregate() }
            aggregate.durationMs = saturatedAdd(aggregate.durationMs, row.totalTime.coerceAtLeast(0L))
            aggregate.lastVisitedMs = maxOf(aggregate.lastVisitedMs, row.lastVisited.coerceAtLeast(0L))
            WebsiteHourlyUsageCodec.decode(row.hourlyUsage).forEachIndexed { hour, value ->
                aggregate.hourlyMs[hour] = saturatedAdd(
                    aggregate.hourlyMs[hour],
                    value.coerceAtLeast(0).toLong(),
                )
            }
        }

        val projected = totals.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, aggregate) ->
                val bucketedMs = aggregate.hourlyMs.fold(0L, ::saturatedAdd)
                linkedMapOf<String, Any>(
                    "date" to key.first,
                    "domain" to key.second,
                    "durationMs" to aggregate.durationMs,
                    "hourlyMs" to aggregate.hourlyMs.toList(),
                    "unbucketedDurationMs" to (aggregate.durationMs - bucketedMs).coerceAtLeast(0L),
                    "lastVisitedMs" to aggregate.lastVisitedMs,
                )
            }

        return linkedMapOf(
            "schemaVersion" to 1,
            "privacyMode" to "domain_only",
            "startDate" to startDate,
            "endDate" to endDate,
            "timeZone" to timeZone,
            "rows" to projected,
        )
    }

    private fun normalizeDomain(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT).removePrefix("www.")
        return normalized.takeIf(domainPattern::matches)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private class Aggregate {
        var durationMs: Long = 0L
        var lastVisitedMs: Long = 0L
        val hourlyMs = LongArray(HOURS_PER_DAY)
    }
}
