package com.kgr.q25toolbox.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Whether Q25AccessibilityService is currently enabled in system Accessibility settings. */
fun isQ25AccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val pkg = context.packageName
    return services.any { it.resolveInfo?.serviceInfo?.packageName == pkg }
}
