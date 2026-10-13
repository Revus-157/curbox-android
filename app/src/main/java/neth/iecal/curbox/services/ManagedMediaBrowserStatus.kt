package neth.iecal.curbox.services

import android.media.session.MediaController
import android.media.MediaMetadata
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import java.net.URI

/** Payload-free snapshots used to classify active supported-browser media sessions. */
internal data class BrowserMediaSessionSnapshot(
    val packageName: String,
    val hasValidMediaUriHost: Boolean
)

internal object ManagedMediaBrowserStatus {
    /**
     * Returns null when there is no active supported-browser controller. The result deliberately
     * exposes only URI-host presence, never the URI, title, token, or controller count.
     */
    fun browserMediaUriPresent(sessions: Iterable<BrowserMediaSessionSnapshot>): Boolean? {
        val supported = sessions.filter { it.packageName in URL_BAR_ID_LIST }
        if (supported.isEmpty()) return null
        return supported.any { it.hasValidMediaUriHost }
    }

    fun fromControllers(controllers: List<MediaController>): Boolean? =
        browserMediaUriPresent(
            controllers.map { controller ->
                BrowserMediaSessionSnapshot(
                    packageName = controller.packageName,
                    hasValidMediaUriHost = hasValidMediaUriHost(controller.metadata)
                )
            }
        )

    private fun hasValidMediaUriHost(metadata: MediaMetadata?): Boolean {
        val rawUri = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.takeIf(String::isNotBlank)
            ?: return false
        return runCatching { URI(rawUri).host?.isNotBlank() == true }.getOrDefault(false)
    }
}
