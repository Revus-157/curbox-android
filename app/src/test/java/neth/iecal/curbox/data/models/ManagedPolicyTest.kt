package neth.iecal.curbox.data.models

import org.junit.Assert.*
import org.junit.Test
import neth.iecal.curbox.services.ManagedMediaEnforcement
import neth.iecal.curbox.services.MediaSessionSnapshot

class ManagedPolicyTest {
    @Test fun replacementCannotRemoveAnotherAuthorizedClientsLease() {
        val other=ManagedGroupPolicy("other","keyword_group","Other",listOf("example.test"),2000,ownerPackage="example.other")
        val own=ManagedGroupPolicy("own","keyword_group","Own",listOf("youtube"),2000,ownerPackage="example.client")
        val result=replaceManagedPolicies(Settings(managedGroupPolicies=listOf(other,own)),emptyList(),"example.client",1000)
        assertEquals(listOf(other),result.managedGroupPolicies)
    }
    @Test fun policyTransportRejectsUnboundedOrMalformedTargets() {
        val valid=ManagedGroupPolicy("youtube","keyword_group","Youtube",listOf("youtube"),2000)
        for (bad in listOf(valid.copy(kind="all"),valid.copy(members=emptyList()),
            valid.copy(expiresAtMs=1000+27*60*60*1000L),valid.copy(members=listOf("")))) {
            try { replaceManagedPolicies(Settings(),listOf(bad),"example.client",1000);fail("malformed policy accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun editableGroupsCannotRemoveOrWeakenManagedWebsiteBlock() {
        val policy = ManagedGroupPolicy("youtube", "keyword_group", "Youtube", listOf("youtube.com", "youtube"), 2000)
        val edited = Settings(keywordBlockerConfig = KeywordBlocker(false, emptyList()), managedGroupPolicies = listOf(policy))
        val effective = effectiveManagedSettings(edited, 1000)
        assertTrue(effective.keywordBlockerConfig.isActive)
        val group = effective.keywordBlockerConfig.keywordGroups.single()
        assertEquals(listOf("youtube.com", "youtube"), group.selectedKeywords)
        assertTrue(group.isActive)
        assertEquals(0L, group.config!!.usage.uniformLimit)
        assertTrue(effective.isWebsiteUsageTrackingEnabled)
    }
    @Test fun expiryDoesNotChangeUserGroupsOrLeaveGlobalKeywordBlockEnabled() {
        val own = KeywordGroup(id="owner", name="Own", selectedKeywords=listOf("example.test"), isActive=true)
        val base = Settings(keywordBlockerConfig=KeywordBlocker(false,listOf(own)),
            managedGroupPolicies=listOf(ManagedGroupPolicy("youtube","keyword_group","Youtube",listOf("youtube"),2000)))
        assertEquals(base.keywordBlockerConfig,effectiveManagedSettings(base,2000).keywordBlockerConfig)
        assertEquals(base.blockedAppGroups,effectiveManagedSettings(base,2000).blockedAppGroups)
    }
    @Test fun independentTargetsExpireIndependentlyAndCannotOverwriteOwnerGroups() {
        val own=AppGroup(id="owner",name="Games",selectedPackages=listOf("example.own"))
        val base=Settings(blockedAppGroups=listOf(own),managedGroupPolicies=listOf(
            ManagedGroupPolicy("games","app_group","Games",listOf("example.game"),2000),
            ManagedGroupPolicy("video","app_group","Video",listOf("example.video"),4000)))
        val result=effectiveManagedSettings(base,3000).blockedAppGroups
        assertEquals(2,result.size)
        assertEquals(own,result.first())
        assertEquals(listOf("example.video"),result.last().selectedPackages)
        assertTrue(result.last().isActive)
    }

    @Test fun managedGroupsDisableProceedWithoutChangingOrdinaryWarningSettings() {
        val ordinaryWarning = AppBlockerWarningScreenConfig(isProceedDisabled = false)
        val base = Settings(
            blockedAppGroups = listOf(
                AppGroup(
                    id = "ordinary-app", name = "Ordinary app",
                    selectedPackages = listOf("com.example.allowed"),
                    warningScreenConfig = ordinaryWarning
                )
            ),
            keywordBlockerConfig = KeywordBlocker(
                isActive = true,
                keywordGroups = listOf(
                    KeywordGroup(
                        id = "ordinary-site", name = "Ordinary site",
                        selectedKeywords = listOf("allowed.example"),
                        warningScreenConfig = ordinaryWarning
                    )
                )
            ),
            managedGroupPolicies = listOf(
                ManagedGroupPolicy("managed-app", "app_group", "Managed app", listOf("com.example.blocked"), 2_000),
                ManagedGroupPolicy("managed-site", "keyword_group", "Managed site", listOf("blocked.example"), 2_000)
            )
        )

        val effective = effectiveManagedSettings(base, 1_000)
        assertTrue(effective.blockedAppGroups.single { it.id.startsWith(MANAGED_GROUP_PREFIX) }
            .warningScreenConfig.isProceedDisabled)
        assertTrue(effective.keywordBlockerConfig.keywordGroups.single { it.id.startsWith(MANAGED_GROUP_PREFIX) }
            .warningScreenConfig.isProceedDisabled)
        assertFalse(effective.blockedAppGroups.single { it.id == "ordinary-app" }
            .warningScreenConfig.isProceedDisabled)
        assertFalse(effective.keywordBlockerConfig.keywordGroups.single { it.id == "ordinary-site" }
            .warningScreenConfig.isProceedDisabled)
    }

    @Test fun managedMediaTargetsOnlyPlayingBlockedPackagesAndTrustedBlockedSite() {
        val settings = Settings(
            managedGroupPolicies = listOf(
                ManagedGroupPolicy("app", "app_group", "App", listOf("com.example.blocked"), 2_000),
                ManagedGroupPolicy("site", "keyword_group", "Site", listOf("blocked.example"), 2_000)
            )
        )
        val target = ManagedMediaEnforcement.targetFor(
            settings, 1_000,
            neth.iecal.curbox.trackers.WebsiteObservation(
                "com.example.browser", "blocked.example", "blocked.example/watch"
            )
        )

        assertEquals(
            setOf("com.example.blocked", "com.example.browser"),
            ManagedMediaEnforcement.playablePackagesToPause(
                true, target, listOf(
                    MediaSessionSnapshot("com.example.blocked", true),
                    MediaSessionSnapshot("com.example.browser", true),
                    MediaSessionSnapshot("com.spotify.music", true),
                    MediaSessionSnapshot("com.example.blocked", false)
                )
            )
        )
    }

    @Test fun managedMediaPermissionUnavailableAndAllowedSiteAreNoOp() {
        val settings = Settings(
            managedGroupPolicies = listOf(
                ManagedGroupPolicy("site", "keyword_group", "Site", listOf("blocked.example"), 2_000)
            )
        )
        val allowedObservation = neth.iecal.curbox.trackers.WebsiteObservation(
            "com.example.browser", "allowed.example", "allowed.example/watch"
        )
        val target = ManagedMediaEnforcement.targetFor(settings, 1_000, allowedObservation)

        assertTrue(target.browserPackages.isEmpty())
        assertTrue(
            ManagedMediaEnforcement.playablePackagesToPause(
                false, target.copy(appPackages = setOf("com.example.blocked")),
                listOf(MediaSessionSnapshot("com.example.blocked", true))
            ).isEmpty()
        )
    }

    @Test fun expiredPolicyReleasesWithoutAutoPlayingAndResumedPlaybackIsPausedAgain() {
        val settings = Settings(
            managedGroupPolicies = listOf(
                ManagedGroupPolicy("app", "app_group", "App", listOf("com.example.blocked"), 2_000)
            )
        )
        val active = ManagedMediaEnforcement.targetFor(settings, 1_000, null)
        val expired = ManagedMediaEnforcement.targetFor(settings, 2_000, null)

        assertEquals(setOf("com.example.blocked"), active.appPackages)
        assertTrue(expired.appPackages.isEmpty())
        assertEquals(
            setOf("com.example.blocked"),
            ManagedMediaEnforcement.playablePackagesToPause(
                true, active, listOf(MediaSessionSnapshot("com.example.blocked", true))
            )
        )
        assertTrue(
            ManagedMediaEnforcement.playablePackagesToPause(
                true, active, listOf(MediaSessionSnapshot("com.example.blocked", false))
            ).isEmpty()
        )
        assertEquals(
            setOf("com.example.blocked"),
            ManagedMediaEnforcement.playablePackagesToPause(
                true, active, listOf(MediaSessionSnapshot("com.example.blocked", true))
            )
        )
        assertTrue(
            ManagedMediaEnforcement.playablePackagesToPause(
                true, expired, listOf(MediaSessionSnapshot("com.example.blocked", true))
            ).isEmpty()
        )
    }
}
