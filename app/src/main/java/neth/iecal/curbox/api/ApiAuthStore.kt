package neth.iecal.curbox.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Stores the Curbox API state: whether the API is on at all, and which apps the user has allowed.
 *
 * Identity is the calling app's package, so the user can see exactly who has access and revoke any
 * of them, the same way the Shizuku manager lists its allowed apps. Kept in the shared
 * "AppPreferences" file the rest of the app already uses.
 */
object ApiAuthStore {
    private const val PREFS = "AppPreferences"
    private const val KEY_ENABLED = "apiEnabled"
    private const val KEY_GRANTS = "apiAuthorizedPackages"
    private const val KEY_GRANT_VERSIONS = "apiAuthorizedPackageVersions"

    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, Long>>() {}.type
    private val versionMapType = object : TypeToken<MutableMap<String, Int>>() {}.type

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isApiEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setApiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Package -> time it was allowed, in millis. */
    private fun storedGrants(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_GRANTS, null) ?: return emptyMap()
        return try {
            gson.fromJson(raw, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun grantVersions(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_GRANT_VERSIONS, null) ?: return emptyMap()
        return try {
            gson.fromJson(raw, versionMapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Only grants approved for this API's current capability set are effective. */
    fun grants(context: Context): Map<String, Long> {
        val versions = grantVersions(context)
        return storedGrants(context).filterKeys {
            (versions[it] ?: 0) >= CurboxApiContract.API_VERSION
        }
    }

    fun grantedPackages(context: Context): Set<String> = grants(context).keys

    /** An app is allowed only when the API is on and one of its packages has been granted. */
    fun isAnyGranted(context: Context, packageNames: Array<String>?): Boolean {
        if (packageNames == null || !isApiEnabled(context)) return false
        val granted = grants(context).keys
        return packageNames.any { it in granted }
    }

    fun grant(context: Context, packageName: String) {
        val updated = storedGrants(context).toMutableMap()
        updated[packageName] = System.currentTimeMillis()
        val versions = grantVersions(context).toMutableMap()
        versions[packageName] = CurboxApiContract.API_VERSION
        save(context, updated, versions)
    }

    fun revoke(context: Context, packageName: String) {
        val updated = storedGrants(context).toMutableMap()
        updated.remove(packageName)
        val versions = grantVersions(context).toMutableMap()
        versions.remove(packageName)
        save(context, updated, versions)
    }

    private fun save(context: Context, map: Map<String, Long>, versions: Map<String, Int>) {
        prefs(context).edit()
            .putString(KEY_GRANTS, gson.toJson(map))
            .putString(KEY_GRANT_VERSIONS, gson.toJson(versions))
            .apply()
    }
}
