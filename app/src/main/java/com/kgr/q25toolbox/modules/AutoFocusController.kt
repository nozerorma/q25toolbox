package com.kgr.q25toolbox.modules

import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo

object AutoFocusController {

    const val KEY_AUTO_FOCUS = "auto_focus_enabled"
    const val KEY_AUTO_FOCUS_APPS = "auto_focus_apps"

    fun isEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_AUTO_FOCUS, false)
    }

    fun getSelectedApps(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_AUTO_FOCUS_APPS, emptySet()) ?: emptySet()
    }

    /**
     * Recursively traverses the node tree to find the first text input field.
     * Only matches actual text input widgets (EditText, AutoCompleteTextView)
     * rather than any editable view, to avoid grabbing non-text elements
     * like mail items or map views.
     *
     * If a WebView is present (i.e. we're in a browser), the search is scoped
     * to inside the WebView's own subtree so a page with no <input> fields
     * doesn't fall through to the browser chrome's own EditText (e.g. the
     * address bar) - if the page has nothing to focus, focus nothing.
     *
     * Recycles any non-matching nodes, returning the matching node (which the caller must recycle).
     */
    fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val webView = findWebView(node)
        if (webView != null) {
            try {
                return findEditableInSubtree(webView)
            } finally {
                webView.recycle()
            }
        }
        return findEditableInSubtree(node)
    }

    private fun findWebView(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.webkit.WebView") {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWebView(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findEditableInSubtree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Don't require isEditable(): some search boxes (e.g. Google Maps'
        // omnibox) report as a real EditText/focusable but only flip
        // isEditable() to true once actually tapped into. The classname
        // check alone is narrow enough to avoid the old false-positive
        // matches (mail list items, map view).
        if (node.isFocusable) {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("AutoCompleteTextView")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableInSubtree(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }
}
