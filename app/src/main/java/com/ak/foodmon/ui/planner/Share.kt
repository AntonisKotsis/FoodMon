package com.ak.foodmon.ui.planner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

private val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * Opens WhatsApp (or WhatsApp Business) with [text] ready to send; the contact is picked there.
 * Falls back to the system share sheet when neither app is installed.
 */
fun shareToWhatsApp(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    for (pkg in whatsAppPackages) {
        try {
            context.startActivity(Intent(send).setPackage(pkg))
            return
        } catch (_: ActivityNotFoundException) {
            // Not installed; try the next one.
        }
    }
    context.startActivity(Intent.createChooser(send, "Send shopping list"))
}
