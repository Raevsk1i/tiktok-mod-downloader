package com.tiktok.regionpatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.tiktok.regionpatcher.core.InstallErrorMapper

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                sendResult(context, true, "TikTok установлен")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    sendResult(context, false, "Подтвердите установку в диалоге системы")
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Install failed ($status): $message")
                sendResult(context, false, InstallErrorMapper.translate(message))
            }
            else -> {
                Log.w(TAG, "Unknown install status $status: $message")
                sendResult(context, false, InstallErrorMapper.translate(message))
            }
        }
    }

    private fun sendResult(context: Context, success: Boolean, message: String) {
        val result = Intent(ACTION_INSTALL_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(result)
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.tiktok.regionpatcher.INSTALL_RESULT"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "InstallResultReceiver"
    }
}
