package neth.iecal.curbox

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import neth.iecal.curbox.data.models.*
import neth.iecal.curbox.ui.activity.WarningActivity
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class ManagedWarningCopyDeviceTest {
    @Test fun realWarningRendersManagedExplanationAndPreservesOrdinaryCustomMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(android.os.Build.MODEL.contains("sdk") && context.packageName.endsWith(".debug"))
        val generated = effectiveManagedSettings(Settings(managedGroupPolicies = listOf(
            ManagedGroupPolicy("app", "app_group", "Synthetic app", listOf("com.example.app"), System.currentTimeMillis()+60000)
        )), System.currentTimeMillis()).blockedAppGroups.single().warningScreenConfig
        for (config in listOf(generated, AppBlockerWarningScreenConfig(message = "My personal reminder", proceedDelayInSecs = 0))) {
            val activity = instrumentation.startActivitySync(Intent(context, WarningActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                .putExtra("warning_config", Gson().toJson(config)))
            try {
                onView(withId(R.id.warning_msg)).check(matches(withText(
                    if (config == generated) "This content is blocked by an active protection rule." else "My personal reminder"
                )))
                onView(withId(R.id.btn_proceed)).check(matches(withEffectiveVisibility(
                    if (config.isProceedDisabled) Visibility.GONE else Visibility.VISIBLE
                )))
                if (config == generated) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    FileOutputStream(File(context.getExternalFilesDir(null), "managed-warning-copy.png")).use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
