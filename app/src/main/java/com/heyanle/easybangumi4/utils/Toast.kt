package com.heyanle.easybangumi4.utils

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.heyanle.easybangumi4.APP

private var toastLegacy: Toast? = null

fun <T> T.toast(len: Int = Toast.LENGTH_SHORT): T = apply {
    Toast.makeText(APP, toString(), len).show()
}

fun Context.toastOnUi(message: Int, duration: Int = Toast.LENGTH_SHORT) {
    toastOnUi(getString(message), duration)
}

fun Context.toastOnUi(message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null) {
                toastLegacy = Toast.makeText(this, message, duration)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = duration
            }
            toastLegacy?.show()
        }
    }
}

fun Context.longToastOnUi(message: Int) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUi(message: CharSequence?) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Fragment.toastOnUi(message: Int) = requireActivity().toastOnUi(message)

fun Fragment.toastOnUi(message: CharSequence) = requireActivity().toastOnUi(message)

fun Fragment.longToast(message: Int) = requireContext().longToastOnUi(message)

fun Fragment.longToast(message: CharSequence) = requireContext().longToastOnUi(message)
