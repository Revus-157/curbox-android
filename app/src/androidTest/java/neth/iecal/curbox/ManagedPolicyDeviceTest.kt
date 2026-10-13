package neth.iecal.curbox

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.api.ApiAuthStore
import neth.iecal.curbox.api.CurboxApiService
import neth.iecal.curbox.api.ICurboxApi
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic emulator only; never run on the owner's Curbox installation. */
@RunWith(AndroidJUnit4::class)
class ManagedPolicyDeviceTest {
    @Test fun prepareSyntheticLegacyMigrationFixtureForQaApp() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ApiAuthStore.setApiEnabled(context,true)
        ApiAuthStore.grant(context,"com.hermes.nudgeapp.qa")
        DataStoreManager(context).updateKeywordBlockerConfig { KeywordBlocker(true,listOf(
            KeywordGroup(id="synthetic-legacy-youtube",name="Youtube",selectedKeywords=listOf("youtube.com","youtube"),
                isActive=true,config=ScheduledUsageConfig(usage=AppUsageConfig(uniformLimit=0))))) }
    }
    @Test fun actualBinderPolicySurvivesEditableGroupDeletionAndExpires() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic managed-policy test must run only on an emulator"
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val connected=CountDownLatch(1)
        var api: ICurboxApi?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?, binder:IBinder?) { api=ICurboxApi.Stub.asInterface(binder);connected.countDown() }
            override fun onServiceDisconnected(name:ComponentName?) { api=null }
        }
        assertTrue(context.bindService(Intent(context,CurboxApiService::class.java),connection,Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(5,TimeUnit.SECONDS))
            val client=requireNotNull(api)
            ApiAuthStore.setApiEnabled(context,true)
            ApiAuthStore.revoke(context,context.packageName)
            assertEquals("DENIED",client.execute("REPLACE_MANAGED_POLICIES",Bundle().apply { putString("policies","[]") }))
            ApiAuthStore.grant(context,context.packageName)
            val deadline=System.currentTimeMillis()+5000
            val target=JSONObject().put("id","synthetic-youtube").put("kind","keyword_group").put("name","Synthetic video")
                .put("members",JSONArray(listOf("youtube"))).put("expiresAtMs",deadline)
                .put("mediaPackages",JSONArray(listOf("com.example.browser")))
            assertTrue(JSONObject(client.list("STATUS")).getBoolean("managedMediaPackagesSupported"))
            assertEquals("OK",client.execute("REPLACE_MANAGED_POLICIES",Bundle().apply { putString("policies",JSONArray().put(target).toString()) }))
            runBlocking { DataStoreManager(context).updateKeywordBlockerConfig { it.copy(isActive=false,keywordGroups=emptyList()) } }
            val groups=JSONArray(client.list("KEYWORD_GROUPS"))
            assertEquals(1,groups.length())
            assertTrue(groups.getJSONObject(0).getBoolean("isActive"))
            assertEquals(listOf("youtube"),groups.getJSONObject(0).getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) } })
            val receipt=JSONArray(client.list("MANAGED_POLICIES"))
            assertEquals(context.packageName,receipt.getJSONObject(0).getString("ownerPackage"))
            assertEquals("com.example.browser",receipt.getJSONObject(0).getJSONArray("mediaPackages").getString(0))
            assertEquals(0,JSONArray(client.list("APP_BLOCKER_GROUPS")).length())
            Thread.sleep((deadline-System.currentTimeMillis()+100).coerceAtLeast(0))
            assertEquals(0,JSONArray(client.list("KEYWORD_GROUPS")).length())
            assertEquals("OK",client.execute("REPLACE_MANAGED_POLICIES",Bundle().apply { putString("policies","[]") }))
        } finally { context.unbindService(connection) }
    }
}
