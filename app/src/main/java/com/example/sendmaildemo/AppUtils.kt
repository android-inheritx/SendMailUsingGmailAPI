package com.example.sendmaildemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat


class AppUtils {
    companion object {
        fun checkPermission(context: Context): Boolean {
            if (isMarshmallow()) {
//        val result = ContextCompat.checkSelfPermission(context, permission!!)
                val result = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) + ContextCompat
                    .checkSelfPermission(
                        context,
                        Manifest.permission.GET_ACCOUNTS
                    )

                return result == PackageManager.PERMISSION_GRANTED
            } else {
                return true
            }
        }

        fun isMarshmallow(): Boolean {
            return Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1
        }
    }
}
