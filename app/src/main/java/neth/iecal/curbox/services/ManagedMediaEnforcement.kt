package neth.iecal.curbox.services

import neth.iecal.curbox.data.models.MANAGED_GROUP_PREFIX
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.effectiveManagedSettings
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.utils.KeywordMatcher

/** The package-only target passed to the notification listener process. */
data class ManagedMediaTarget(
    val appPackages: Set<String> = emptySet(),
    val browserPackages: Set<String> = emptySet()
)

data class MediaSessionSnapshot(
    val packageName: String,
    val isPlaying: Boolean
)

/**
 * Pure target selection shared by the accessibility process and unit tests.
 *
 * Website observations arrive only from WebsiteUsageTracker, which already limits observations
 * to trusted browser URL-bar implementations. The listener receives only the resulting package
 * set, never notification content or the observed URL.
 */
object ManagedMediaEnforcement {

    fun targetFor(
        settings: Settings,
        nowMs: Long,
        observation: WebsiteObservation?
    ): ManagedMediaTarget {
        val effective = effectiveManagedSettings(settings, nowMs)
        val appPackages = effective.blockedAppGroups
            .asSequence()
            .filter { it.id.startsWith(MANAGED_GROUP_PREFIX) && it.isActive }
            .flatMap { it.selectedPackages.asSequence() }
            .filter(String::isNotBlank)
            .toSet()

        val browserBlocked = if (observation == null) {
            false
        } else {
            effective.keywordBlockerConfig.keywordGroups
                .asSequence()
                .filter { it.id.startsWith(MANAGED_GROUP_PREFIX) && it.isActive }
                .any { group ->
                    val patterns = KeywordMatcher.compileKeywords(group.selectedKeywords)
                    KeywordMatcher.matchesPatterns(patterns, observation.urlIdentifier)
                }
        }

        return ManagedMediaTarget(
            appPackages = appPackages,
            browserPackages = if (browserBlocked && !observation!!.packageName.isNullOrBlank()) {
                setOf(observation.packageName)
            } else {
                emptySet()
            }
        )
    }

    fun playablePackagesToPause(
        permissionAvailable: Boolean,
        target: ManagedMediaTarget,
        sessions: List<MediaSessionSnapshot>
    ): Set<String> {
        if (!permissionAvailable) return emptySet()
        val targeted = target.appPackages + target.browserPackages
        return sessions.asSequence()
            .filter { it.isPlaying && it.packageName in targeted }
            .map(MediaSessionSnapshot::packageName)
            .toSet()
    }
}
