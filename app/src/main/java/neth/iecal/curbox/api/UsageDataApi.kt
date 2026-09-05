package neth.iecal.curbox.api

import neth.iecal.curbox.data.db.AppUsageEntity

/** Privacy-bounded projection of Curbox's own app-usage tracker for approved API clients. */
object UsageDataApi {
    private const val HOURS_PER_DAY = 24

    fun aggregateApps(
        rows: List<AppUsageEntity>,
        nativeToIsoDate: Map<String, String>,
        startDate: String,
        endDate: String,
        timeZone: String,
    ): Map<String, Any> {
        val projected = rows.mapNotNull { row ->
            val isoDate = nativeToIsoDate[row.date] ?: return@mapNotNull null
            val packageName = row.packageName.trim().takeIf(::validPackageName)
                ?: return@mapNotNull null
            val hourly = parseHourly(row.hourlyUsage)
            val bucketedMs = hourly.fold(0L, ::saturatedAdd)
            linkedMapOf<String, Any>(
                "date" to isoDate,
                "packageName" to packageName,
                "durationMs" to row.totalTime.coerceAtLeast(0L),
                "hourlyMs" to hourly.toList(),
                "unbucketedDurationMs" to
                    (row.totalTime.coerceAtLeast(0L) - bucketedMs).coerceAtLeast(0L),
                "launchCount" to row.launchCount.coerceAtLeast(0),
                "lastUsedMs" to row.lastUsed.coerceAtLeast(0L),
            )
        }.sortedWith(compareBy({ it["date"] as String }, { it["packageName"] as String }))

        return linkedMapOf(
            "schemaVersion" to 1,
            "privacyMode" to "package_only",
            "startDate" to startDate,
            "endDate" to endDate,
            "timeZone" to timeZone,
            "rows" to projected,
        )
    }

    private fun validPackageName(value: String): Boolean =
        value.length in 1..255 && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun parseHourly(serialized: String): LongArray {
        val output = LongArray(HOURS_PER_DAY)
        serialized.split(',').take(HOURS_PER_DAY).forEachIndexed { index, value ->
            output[index] = value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
        return output
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
