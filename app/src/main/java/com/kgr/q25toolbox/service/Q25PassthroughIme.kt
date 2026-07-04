package com.kgr.q25toolbox.service

import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * A do-nothing input method.
 *
 * It never inflates a soft keyboard and never consumes hardware key events, so
 * while it is the active IME physical key presses are delivered straight to the
 * focused app. The per-app keyboard block switches to this IME in selected apps
 * to bypass the normal keyboard (e.g. the BlackBerry IME, which otherwise
 * intercepts and translates the physical keys).
 */
class Q25PassthroughIme : InputMethodService() {
    override fun onCreateInputView(): View? = null
    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onEvaluateFullscreenMode(): Boolean = false
}
