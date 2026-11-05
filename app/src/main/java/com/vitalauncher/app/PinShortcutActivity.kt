package com.vitalauncher.app

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point for "add to home screen" requests (Chrome, Maps, messaging apps, ...).
 * Android routes [LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT] here when we're the default launcher;
 * we accept the request and bounce to the home screen, where [LauncherViewModel.syncInstalledApps]
 * picks the newly pinned shortcut up and drops it into the first free slot.
 */
class PinShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val launcherApps = getSystemService(LauncherApps::class.java)
            val request = launcherApps?.getPinItemRequest(intent)
            if (request != null &&
                request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
                request.isValid &&
                request.accept()
            ) {
                Toast.makeText(this, "Shortcut added to home screen", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't add shortcut", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
