package app.niix.ui

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import app.niix.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The builder for every dialog in the app: Material3's, so dialogs get its rounded layout, with
 * the niixa glass background and an edge in the theme colour. One place to change, so no dialog
 * can come out in a different style from the rest.
 *
 * MaterialAlertDialogBuilder extends AppCompat's AlertDialog.Builder, so it returns the same
 * dialogs and works with showDestructive below.
 */
fun niixDialog(context: Context): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(context).apply {
        // A method call, not `background = it`: Kotlin only allows property syntax for a Java
        // setter that returns nothing, and this builder's setters return the builder.
        ContextCompat.getDrawable(context, R.drawable.dialog_background)?.let { setBackground(it) }
    }

/**
 * Shows a dialog whose confirm button is red.
 *
 * The theme gives the positive button its own colour, which is right for "save" and wrong for
 * "delete everything". A destructive action that looks identical to a safe one is a trap, and the
 * colour is the only thing distinguishing them at a glance.
 *
 * Applied after show() because the button views do not exist until then.
 */
fun AlertDialog.Builder.showDestructive(): AlertDialog {
    val dialog = show()
    val red = dialog.context.themeColor(R.attr.niixDanger)
    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(red)
    return dialog
}
