package ai.matca.edge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A classifier wired to the phone: files under the app's no-backup directory, the edge over HTTP,
 * LiteRT for the model, `BitmapFactory` for JPEGs. It takes the bundled release and asks the edge once
 * as soon as it exists. Its work lives in a child of `scope`, and `close` ends that child, never `scope`.
 */
public fun Classifier.Companion.create(
    context: Context,
    model: String,
    configuration: EdgeConfiguration,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Classifier {
    requireValidModelName(model)
    val classifier = Classifier(
        model = model,
        configuration = configuration,
        store = ModelStore(File(context.noBackupFilesDir, "matca-edge/$model")),
        delivery = HttpDelivery(configuration.baseUrl, configuration.deliveryKey, BuildConfig.VERSION),
        loader = LiteRtModelLoader(),
        decoder = BitmapJpegDecoder(),
        networkPolicy = WifiOnlyPolicy(context.applicationContext),
        scope = scope,
        systemVersion = Build.VERSION.RELEASE.substringBefore('.'),
        processExits = ApplicationExitHistory(context.applicationContext),
    )
    classifier.startInBackground()
    return classifier
}

/** Downloads are allowed on Wi-Fi or Ethernet; the policy is only asked when the app chose `wifiOnly`. */
internal class WifiOnlyPolicy(context: Context) : NetworkPolicy {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun downloadsAllowed(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

/**
 * Why the app's earlier processes ended, from Android 11 (API 30): the system keeps the most recent records in a ring
 * buffer, newest first. Earlier systems keep none, so they report sessions and no crashes.
 */
internal class ApplicationExitHistory(private val context: Context) : ProcessExitHistory {
    override fun exits(): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0).map { exit ->
            val crashed = exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ProcessExit(crashed, exit.timestamp)
        }
    }
}
