package neth.iecal.curbox.ui.fragments.main.reducers.api

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import neth.iecal.curbox.R
import neth.iecal.curbox.api.ApiAuthStore
import neth.iecal.curbox.api.ManagedMediaCapability
import neth.iecal.curbox.utils.ViewUtils

/**
 * In app screen for the Curbox API. Lets the user turn the API on or off and see every app they
 * have allowed, with a way to take access back from any of them.
 */
class ApiFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "api"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_api, container, false)
        val context = requireContext()

        val enableSwitch = view.findViewById<MaterialSwitch>(R.id.switch_enable_api)
        val appsContainer = view.findViewById<LinearLayout>(R.id.container_apps)
        val noAppsText = view.findViewById<TextView>(R.id.text_no_apps)
        val mediaStatus = view.findViewById<TextView>(R.id.text_media_access_status)

        enableSwitch.isChecked = ApiAuthStore.isApiEnabled(context)
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            ApiAuthStore.setApiEnabled(context, isChecked)
        }

        fun refreshMediaAccess() {
            mediaStatus.text = if (ManagedMediaCapability.isGranted(context)) {
                getString(R.string.media_access_ready)
            } else {
                getString(R.string.media_access_needed)
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_media_access).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        refreshMediaAccess()

        fun refreshApps() {
            appsContainer.removeAllViews()
            val packages = ApiAuthStore.grantedPackages(context).sorted()
            noAppsText.visibility = if (packages.isEmpty()) View.VISIBLE else View.GONE

            for (pkg in packages) {
                val row = inflater.inflate(R.layout.item_api_app, appsContainer, false)
                val icon = row.findViewById<ImageView>(R.id.app_icon)
                val label = row.findViewById<TextView>(R.id.app_label)
                val packageName = row.findViewById<TextView>(R.id.app_package)

                val pm = context.packageManager
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    label.text = pm.getApplicationLabel(info)
                    icon.setImageDrawable(pm.getApplicationIcon(info))
                } catch (e: Exception) {
                    label.text = pkg
                    icon.setImageResource(R.drawable.logo)
                }
                packageName.text = pkg

                row.findViewById<MaterialButton>(R.id.btn_revoke).setOnClickListener {
                    ApiAuthStore.revoke(context, pkg)
                    refreshApps()
                }
                appsContainer.addView(row)
            }
        }

        refreshApps()

        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(
                it,
                getString(R.string.api_help_popup),
                "https://curbox.app/docs/"
            )
        }

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView>(R.id.text_media_access_status)?.text =
            if (ManagedMediaCapability.isGranted(requireContext())) {
                getString(R.string.media_access_ready)
            } else {
                getString(R.string.media_access_needed)
            }
    }
}
