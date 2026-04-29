package com.ultiq.app.ui.lockout

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

object LockoutAdmin {
    private const val TAG = "LockoutAdmin"

    fun component(context: Context): ComponentName =
        ComponentName(context, LockoutDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(component(context))
    }

    /**
     * Build the system intent that takes the user to the device-admin grant prompt.
     * Caller (Activity) starts this; the OS shows a confirmation screen with our explanation.
     */
    fun buildEnableIntent(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }

    /**
     * Locks the device immediately. Requires the admin to already be active and the
     * `force-lock` policy declared in the admin XML.
     */
    fun lockNow(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return try {
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow failed — admin not active?", e)
            false
        }
    }

    fun disableAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            dpm.removeActiveAdmin(component(context))
        } catch (e: Exception) {
            Log.w(TAG, "removeActiveAdmin failed", e)
        }
    }
}
