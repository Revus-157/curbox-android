package neth.iecal.curbox.services

import neth.iecal.curbox.data.models.MANAGED_GROUP_PREFIX
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.effectiveManagedSettings
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.utils.KeywordMatcher

/** The package-only target passed to the notification listener process. */
data class ManagedMediaTarget(
    val appPackages: Set<String> = emptySet(),
    val browserPackages: Set<String> = emptySet(),
    val browserDomains: Set<String> = emptySet(),
    val retainBrowserSessions: Boolean = false,
    val browserAttributionExpiresAtMs: Long? = null
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

    /** Revalidates existing tokens only; neither a package nor a missing URI can acquire one. */
    fun <T> revalidateBrowserTokens(
        settings: Settings,
        nowMs: Long,
        attributedTokens: Set<T>,
        mediaUris: Map<T, String?>
    ): Set<T> = attributedTokens.filterTo(mutableSetOf()) { token ->
        val uri = mediaUris[token]
        !uri.isNullOrBlank() &&
            runCatching { java.net.URI(uri).host?.isNotBlank() == true }.getOrDefault(false) &&
            settings.managedGroupPolicies.any { policy ->
                policy.kind == "keyword_group" && policy.expiresAtMs > nowMs &&
                    KeywordMatcher.matchesPatterns(KeywordMatcher.compileKeywords(policy.members), uri)
            }
    }

    /** A browser session is targeted only while its current URI still proves the blocked site. */
    fun isBrowserSessionTargeted(
        packageName: String,
        browserPackages: Set<String>,
        tokenAttributed: Boolean,
        uriMatchesBlockedDomain: Boolean?
    ): Boolean = packageName in browserPackages && tokenAttributed && uriMatchesBlockedDomain == true

    /**
     * Keeps only the most recent blocked browser observation across a foreground clear.
     * The listener uses [ManagedMediaTarget.retainBrowserSessions] to bind that attribution
     * to the sessions already active at the clear; a new session in the same browser package
     * is not selected until a fresh blocked observation arrives.
     */
    class AttributionState {
        private var lastBlockedObservation: WebsiteObservation? = null

        fun targetFor(
            settings: Settings,
            nowMs: Long,
            observation: WebsiteObservation?
        ): ManagedMediaTarget {
            if (observation != null) {
                val direct = ManagedMediaEnforcement.targetFor(settings, nowMs, observation)
                lastBlockedObservation = observation.takeIf { direct.browserPackages.isNotEmpty() }
                return direct
            }

            val retained = lastBlockedObservation?.let {
                ManagedMediaEnforcement.targetFor(settings, nowMs, it)
            }
            if (retained?.browserPackages?.isNotEmpty() == true) {
                return retained.copy(retainBrowserSessions = true)
            }

            lastBlockedObservation = null
            return ManagedMediaEnforcement.targetFor(settings, nowMs, null)
        }

        fun clear() {
            lastBlockedObservation = null
        }
    }

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

        val matchingKeywordPolicies = if (observation == null) {
            emptyList()
        } else {
            settings.managedGroupPolicies.filter { policy ->
                policy.kind == "keyword_group" &&
                    policy.expiresAtMs > nowMs &&
                    policy.members.any { member ->
                        val patterns = KeywordMatcher.compileKeywords(listOf(member))
                        KeywordMatcher.matchesPatterns(patterns, observation.urlIdentifier)
                    }
            }
        }
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
            },
            browserDomains = if (browserBlocked) setOf(observation!!.domain) else emptySet(),
            browserAttributionExpiresAtMs = matchingKeywordPolicies.maxOfOrNull { it.expiresAtMs }
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
