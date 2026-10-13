package neth.iecal.curbox.data.models

import org.junit.Assert.*
import org.junit.Test
import neth.iecal.curbox.ui.activity.resolveWarningMessage

class ManagedWarningMessageTest {
    @Test fun generatedManagedWarningsReplaceSetupPlaceholderAndPreserveOrdinaryCustomCopy() {
        val ordinary = AppBlockerWarningScreenConfig(message = "My personal reminder")
        val base = Settings(
            blockedAppGroups = listOf(AppGroup(id = "ordinary-app", warningScreenConfig = ordinary)),
            keywordBlockerConfig = KeywordBlocker(keywordGroups = listOf(KeywordGroup(id = "ordinary-site", warningScreenConfig = ordinary))),
            managedGroupPolicies = listOf(
                ManagedGroupPolicy("app", "app_group", "Synthetic app", listOf("com.example.app"), 2000),
                ManagedGroupPolicy("site", "keyword_group", "Synthetic site", listOf("example.test"), 2000)
            )
        )
        val effective = effectiveManagedSettings(base, 1000)
        val app = effective.blockedAppGroups.single { it.id.startsWith(MANAGED_GROUP_PREFIX) }.warningScreenConfig
        val site = effective.keywordBlockerConfig.keywordGroups.single { it.id.startsWith(MANAGED_GROUP_PREFIX) }.warningScreenConfig
        assertNotEquals(AppBlockerWarningScreenConfig().message, app.message)
        assertEquals(app.message, site.message)
        assertEquals("Localized protection explanation", resolveWarningMessage(app.message) { "Localized protection explanation" })
        assertEquals("My personal reminder", resolveWarningMessage(ordinary.message) { throw AssertionError("ordinary message must not request managed resource") })
        assertTrue(app.isProceedDisabled && site.isProceedDisabled)
        assertEquals(ordinary, effective.blockedAppGroups.single { it.id == "ordinary-app" }.warningScreenConfig)
        assertEquals(ordinary, effective.keywordBlockerConfig.keywordGroups.single { it.id == "ordinary-site" }.warningScreenConfig)
    }
}
