package neth.iecal.curbox.api

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking

/**
 * The exported bound service other apps talk to, like Shizuku's service. A client binds this and
 * calls the [ICurboxApi] methods. Every call is checked against the calling app's identity: only
 * apps the user has allowed in [ApiAuthStore] may run commands or read state.
 *
 * This lives in the main process so its DataStore writes and broadcasts behave exactly like an in
 * app change.
 */
class CurboxApiService : Service() {

    private val gson = Gson()

    private val binder = object : ICurboxApi.Stub() {

        override fun apiVersion(): Int = CurboxApiContract.API_VERSION

        override fun isGranted(): Boolean = callerAllowed()

        override fun execute(command: String?, args: Bundle?): String {
            if (!callerAllowed()) return CurboxApiContract.STATUS_DENIED
            if (ApiCommand.fromNameOrNull(command) == null) return CurboxApiContract.STATUS_UNKNOWN_COMMAND
            return try {
                val applied = runBlocking {
                    CurboxApiCommands.runCommand(applicationContext, command, args ?: Bundle())
                }
                if (applied) CurboxApiContract.STATUS_OK else CurboxApiContract.STATUS_FAILED
            } catch (e: Exception) {
                Log.e(TAG, "execute failed for $command", e)
                CurboxApiContract.STATUS_FAILED
            }
        }

        override fun query(state: String?): String? {
            if (!callerAllowed()) return null
            return try {
                val values = runBlocking {
                    CurboxApiCommands.queryState(applicationContext, state)
                } ?: return null
                gson.toJson(values)
            } catch (e: Exception) {
                Log.e(TAG, "query failed for $state", e)
                null
            }
        }

        override fun list(kind: String?): String? {
            if (!callerAllowed()) return null
            return try {
                val data = runBlocking {
                    CurboxApiCommands.list(applicationContext, kind)
                } ?: return null
                gson.toJson(data)
            } catch (e: Exception) {
                Log.e(TAG, "list failed for $kind", e)
                null
            }
        }

        override fun queryData(dataset: String?, args: Bundle?): String? {
            if (!callerAllowed()) return null
            return try {
                val data = runBlocking {
                    CurboxApiCommands.queryData(applicationContext, dataset, args ?: Bundle())
                } ?: return null
                gson.toJson(data)
            } catch (e: Exception) {
                Log.e(TAG, "queryData failed for $dataset", e)
                null
            }
        }
    }

    private fun callerAllowed(): Boolean {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
        return ApiAuthStore.isAnyGranted(applicationContext, packages)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "CurboxApiService"
    }
}
