package com.yunx.app.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xingchen.assistant.MainActivity

/** Keep a working launcher entry on partial failure; never persist a failed selection. */
internal object LauncherIconSwitcher {
    fun switch(context: Context, variant: Int) {
        require(variant in 0..1)
        val pm = context.packageManager
        val main = ComponentName(context, MainActivity::class.java)
        val alias = ComponentName(context.packageName, "${context.packageName}.MainActivityIcon2")
        @Suppress("DEPRECATION")
        fun check(component: ComponentName) = pm.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        check(main)
        check(alias)
        val target = if (variant == 1) alias else main
        val previous = if (variant == 1) main else alias
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(listOf(
                PackageManager.ComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP),
                PackageManager.ComponentEnabledSetting(previous, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            ))
        } else {
            val oldTarget = pm.getComponentEnabledSetting(target)
            pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            try {
                pm.setComponentEnabledSetting(previous, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            } catch (error: Exception) {
                // The previous entry is still available. Restore the newly enabled one if possible.
                runCatching { pm.setComponentEnabledSetting(target, oldTarget, PackageManager.DONT_KILL_APP) }
                throw error
            }
        }
    }
}
