package neth.iecal.curbox.api

import neth.iecal.curbox.data.db.AppUsageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageDataApiTest {
    @Test
    fun exportsBoundedPackageAndHourlyUsage() {
        val result = UsageDataApi.aggregateApps(
            rows = listOf(
                AppUsageEntity(
                    date = "05 September 2026",
                    packageName = "com.google.android.youtube",
                    totalTime = 180_000,
                    hourlyUsage = List(24) { if (it == 9) "120000" else "0" }.joinToString(","),
                    launchCount = 2,
                    lastUsed = 1234,
                ),
                AppUsageEntity("05 September 2026", "not a package", 999),
            ),
            nativeToIsoDate = mapOf("05 September 2026" to "2026-09-05"),
            startDate = "2026-09-05",
            endDate = "2026-09-05",
            timeZone = "Europe/Berlin",
        )
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any>>

        assertEquals("package_only", result["privacyMode"])
        assertEquals(1, rows.size)
        assertEquals("com.google.android.youtube", rows.single()["packageName"])
        assertEquals(60_000L, rows.single()["unbucketedDurationMs"])
        @Suppress("UNCHECKED_CAST")
        val hourly = rows.single()["hourlyMs"] as List<Long>
        assertEquals(120_000L, hourly[9])
    }
}
