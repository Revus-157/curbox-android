package neth.iecal.curbox.api

import neth.iecal.curbox.data.db.WebsiteHourlyUsageCodec
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebsiteUsageApiTest {

    @Test
    fun aggregatesPathsAndBrowsersWithoutExposingUrlIdentifiers() {
        val morning = IntArray(24).also { it[9] = 60_000 }
        val afternoon = IntArray(24).also { it[15] = 120_000 }
        val rows = listOf(
            website("05 September 2026", "com.brave.browser", "youtube.com/watch?v=secret", "www.YouTube.com", 60_000, 10, morning),
            website("05 September 2026", "com.android.chrome", "youtube.com/shorts/private", "youtube.com", 120_000, 20, afternoon),
            website("05 September 2026", "com.brave.browser", "private search words", "private search words", 999_000, 30, IntArray(24)),
        )

        val result = WebsiteUsageApi.aggregate(
            rows,
            mapOf("05 September 2026" to "2026-09-05"),
            "2026-09-05",
            "2026-09-05",
            "Europe/Berlin",
        )
        @Suppress("UNCHECKED_CAST")
        val projected = result["rows"] as List<Map<String, Any>>

        assertEquals("domain_only", result["privacyMode"])
        assertEquals(1, projected.size)
        assertEquals("youtube.com", projected.single()["domain"])
        assertEquals(180_000L, projected.single()["durationMs"])
        assertEquals(20L, projected.single()["lastVisitedMs"])
        assertFalse(projected.single().toString().contains("secret"))
        assertFalse(projected.single().toString().contains("private search"))
        @Suppress("UNCHECKED_CAST")
        val hourly = projected.single()["hourlyMs"] as List<Long>
        assertEquals(60_000L, hourly[9])
        assertEquals(120_000L, hourly[15])
    }

    @Test
    fun reportsLegacyTimeThatHasNoHourlyBuckets() {
        val rows = listOf(
            website("04 September 2026", "com.brave.browser", "example.org", "example.org", 90_000, 1, IntArray(24)),
        )

        val result = WebsiteUsageApi.aggregate(
            rows,
            mapOf("04 September 2026" to "2026-09-04"),
            "2026-09-04",
            "2026-09-04",
            "Europe/Berlin",
        )
        @Suppress("UNCHECKED_CAST")
        val projected = result["rows"] as List<Map<String, Any>>

        assertEquals(90_000L, projected.single()["unbucketedDurationMs"])
    }

    private fun website(
        date: String,
        packageName: String,
        identifier: String,
        domain: String,
        totalTime: Long,
        lastVisited: Long,
        hourly: IntArray,
    ) = WebsiteStatsEntity(
        date = date,
        packageName = packageName,
        urlIdentifier = identifier,
        domain = domain,
        totalTime = totalTime,
        lastVisited = lastVisited,
        hourlyUsage = WebsiteHourlyUsageCodec.encode(hourly),
    )
}
