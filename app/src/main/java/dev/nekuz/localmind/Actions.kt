package dev.nekuz.localmind

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object Actions {
    private const val CLIP_LABEL = "LocalMind"

    fun copy(context: Context, value: String) {
        val done = try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, value))
            true
        } catch (e: RuntimeException) {
            false
        }
        toast(context, if (done) R.string.copied else R.string.copy_failed)
    }

    fun toast(context: Context, text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun show(dialog: AlertDialog): AlertDialog {
        dialog.show()
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        dialog.listView?.filterTouchesWhenObscured = true
        return dialog
    }
}
