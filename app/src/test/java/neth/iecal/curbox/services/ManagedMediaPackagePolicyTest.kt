package neth.iecal.curbox.services

import com.google.gson.Gson
import neth.iecal.curbox.data.models.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.utils.GsonSerializer

class ManagedMediaPackagePolicyTest {
    @Test fun oldSerializedSettingsLoadEmptyMediaOptInAndKeepStrictUriMode() {
        val raw = """{"managedGroupPolicies":[{"id":"site","kind":"keyword_group","name":"Site","members":["blocked.example"],"expiresAtMs":2000,"ownerPackage":"example.client"}]}"""
        val old = runBlocking { GsonSerializer(Gson(), Settings::class.java, Settings()).readFrom(raw.byteInputStream()) }
        val target = ManagedMediaEnforcement.targetFor(old, 1000, null)
        assertTrue(old.managedGroupPolicies.single().mediaPackages.isEmpty())
        assertTrue(target.mediaPackages.isEmpty())
        assertFalse(ManagedMediaEnforcement.browserPolicyMatchesUri(old, 1000, null))
        assertTrue(ManagedMediaEnforcement.browserPolicyMatchesUri(old, 1000, "https://blocked.example/watch"))
    }

    @Test fun malformedMediaPackageIsRejectedBeforeAnyReplacement() {
        for (packages in listOf(listOf(""), listOf("com.example.browser", "com.example.browser"), listOf("http://example.test"))) {
            val invalid = settings().managedGroupPolicies.single().copy(mediaPackages = packages)
            try { replaceManagedPolicies(Settings(), listOf(invalid), "example.client", 1000); fail("invalid media target accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    private fun settings(expiry: Long = 2000): Settings = Settings(managedGroupPolicies = listOf(
        Gson().fromJson("""{"id":"site","kind":"keyword_group","name":"Site","members":["blocked.example"],"expiresAtMs":$expiry,"mediaPackages":["com.example.browser"]}""", ManagedGroupPolicy::class.java)
    ))

    @Test fun explicitMediaPackageNeedsNoObservationAndDoesNotBlockBrowsing() {
        val settings = settings()
        val target = ManagedMediaEnforcement.targetFor(settings, 1000, null)
        assertEquals(setOf("com.example.browser"), ManagedMediaEnforcement.playablePackagesToPause(true, target,
            listOf(MediaSessionSnapshot("com.example.browser", true), MediaSessionSnapshot("com.example.other", true))))
        assertTrue(effectiveManagedSettings(settings, 1000).blockedAppGroups.isEmpty())
    }

    @Test fun expiryAndReplacementReleaseMediaTargets() {
        assertTrue(ManagedMediaEnforcement.playablePackagesToPause(true,
            ManagedMediaEnforcement.targetFor(settings(), 2000, null), listOf(MediaSessionSnapshot("com.example.browser", true))).isEmpty())
        val released = replaceManagedPolicies(settings().copy(managedGroupPolicies = settings().managedGroupPolicies.map { it.copy(ownerPackage = "example.client") }), emptyList(), "example.client", 1000)
        assertTrue(ManagedMediaEnforcement.playablePackagesToPause(true,
            ManagedMediaEnforcement.targetFor(released, 1000, null), listOf(MediaSessionSnapshot("com.example.browser", true))).isEmpty())
    }
}
