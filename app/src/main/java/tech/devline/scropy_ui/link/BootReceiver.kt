package tech.devline.scropy_ui.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the background link back after this device reboots, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RemoteLinkService.restoreIfEnabled(context)
        }
    }
}
