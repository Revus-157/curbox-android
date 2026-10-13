package neth.iecal.curbox.data.models

import org.junit.Assert.*
import org.junit.Test

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
}
