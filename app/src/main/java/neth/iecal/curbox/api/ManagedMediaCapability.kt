package neth.iecal.curbox.api

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Public, payload-free status for the owner-controlled media capability. */
object ManagedMediaCapability {
    const val AVAILABLE = "available"
    const val PERMISSION_REQUIRED = "permission_required"

    fun status(context: Context): String = try {
        if (NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        ) AVAILABLE else PERMISSION_REQUIRED
    } catch (_: SecurityException) {
        PERMISSION_REQUIRED
    }

    fun isGranted(context: Context): Boolean = status(context) == AVAILABLE
}
