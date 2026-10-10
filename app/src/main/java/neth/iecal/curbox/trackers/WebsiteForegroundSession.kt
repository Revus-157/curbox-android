package neth.iecal.curbox.trackers

internal data class TrackedWebsite(
    val packageName: String,
    val domain: String,
    val urlIdentifier: String
)

internal data class WebsiteUsageCommit(
    val website: TrackedWebsite,
    val durationMs: Long
)

internal sealed interface WebsiteObservationChange {
    data object None : WebsiteObservationChange
    data object Cleared : WebsiteObservationChange
    data class Observed(val website: TrackedWebsite) : WebsiteObservationChange
}

internal data class WebsiteSessionTransition(
    val commits: List<WebsiteUsageCommit> = emptyList(),
    val observationChange: WebsiteObservationChange = WebsiteObservationChange.None
)

internal data class WebsiteWindowEvidence(
    val packageName: String?,
    val isApplication: Boolean,
    val isActive: Boolean = false,
    val isFocused: Boolean = false
)

internal fun selectForegroundApplication(
    windows: List<WebsiteWindowEvidence>,
    activeRootPackage: String?,
    activeRootIsApplication: Boolean,
    isSupportedBrowser: (String) -> Boolean
): String? {
    val applications = windows.filter { it.isApplication && !it.packageName.isNullOrEmpty() }
    applications.firstOrNull { it.isActive || it.isFocused }
        ?.packageName
        ?.let { return it }
    applications.firstOrNull()
        ?.packageName
        ?.let { return it }
    if (activeRootIsApplication) return activeRootPackage

    // Some devices omit window metadata. This fallback may confirm a browser,
    // but an unrelated event package can never be used to end its session.
    return activeRootPackage?.takeIf(isSupportedBrowser)
}

/**
 * Owns the website session clock. Callers must only pass foreground packages
 * proven by an application window; event packages are not foreground evidence.
 */
internal class WebsiteForegroundSession(
    trackingEnabled: Boolean = true,
    screenOn: Boolean = true
) {
    var trackingEnabled: Boolean = trackingEnabled
        private set

    private var screenOn = screenOn
    private var foregroundPackage: String? = null
    private var website: TrackedWebsite? = null
    private var runningSinceMs: Long? = null

    fun currentWebsite(): TrackedWebsite? = website

    fun observe(observed: TrackedWebsite, nowMs: Long): WebsiteSessionTransition {
        foregroundPackage = observed.packageName
        if (website == observed) {
            startIfEligible(nowMs)
            return WebsiteSessionTransition()
        }

        val commits = collectUsage(nowMs)
        website = observed
        runningSinceMs = null
        startIfEligible(nowMs)
        return WebsiteSessionTransition(
            commits = commits,
            observationChange = WebsiteObservationChange.Observed(observed)
        )
    }

    fun onForegroundApplication(packageName: String, nowMs: Long): WebsiteSessionTransition {
        foregroundPackage = packageName
        val current = website ?: return WebsiteSessionTransition()
        if (current.packageName == packageName) {
            startIfEligible(nowMs)
            return WebsiteSessionTransition()
        }

        val commits = collectUsage(nowMs)
        website = null
        runningSinceMs = null
        return WebsiteSessionTransition(commits, WebsiteObservationChange.Cleared)
    }

    fun onScreenOff(nowMs: Long): WebsiteSessionTransition {
        val commits = collectUsage(nowMs)
        screenOn = false
        foregroundPackage = null
        runningSinceMs = null
        return WebsiteSessionTransition(commits)
    }

    fun onScreenOn() {
        screenOn = true
        // Wait for fresh application-window evidence before restarting the clock.
    }

    fun setTrackingEnabled(enabled: Boolean, nowMs: Long): WebsiteSessionTransition {
        if (enabled == trackingEnabled) return WebsiteSessionTransition()
        val commits = if (trackingEnabled) collectUsage(nowMs) else emptyList()
        trackingEnabled = enabled
        runningSinceMs = null
        startIfEligible(nowMs)
        return WebsiteSessionTransition(commits)
    }

    fun checkpoint(nowMs: Long): WebsiteSessionTransition =
        WebsiteSessionTransition(commits = collectUsage(nowMs))

    fun destroy(nowMs: Long): WebsiteSessionTransition {
        val commits = collectUsage(nowMs)
        val hadWebsite = website != null
        website = null
        runningSinceMs = null
        foregroundPackage = null
        return WebsiteSessionTransition(
            commits,
            if (hadWebsite) WebsiteObservationChange.Cleared else WebsiteObservationChange.None
        )
    }

    private fun collectUsage(nowMs: Long): List<WebsiteUsageCommit> {
        val current = website ?: return emptyList()
        val start = runningSinceMs ?: return emptyList()
        runningSinceMs = nowMs
        val duration = nowMs - start
        return if (duration > 0) listOf(WebsiteUsageCommit(current, duration)) else emptyList()
    }

    private fun startIfEligible(nowMs: Long) {
        val current = website ?: return
        if (trackingEnabled && screenOn && foregroundPackage == current.packageName && runningSinceMs == null) {
            runningSinceMs = nowMs
        }
    }
}
