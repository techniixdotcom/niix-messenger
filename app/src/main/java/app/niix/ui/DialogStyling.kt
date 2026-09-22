package app.niix.ui

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import app.niix.R

/**
 * Shows a dialog whose confirm button is red.
 *
 * The theme colours the positive button green, which is right for "save" and wrong for "delete
 * everything". A destructive action that looks identical to a safe one is a trap, and the colour
 * is the only thing distinguishing them at a glance.
 *
 * Applied after show() because the button views do not exist until then.
 */
fun AlertDialog.Builder.showDestructive(): AlertDialog {
    val dialog = show()
    val red = ContextCompat.getColor(dialog.context, R.color.niix_danger)
    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(red)
    return dialog
}
