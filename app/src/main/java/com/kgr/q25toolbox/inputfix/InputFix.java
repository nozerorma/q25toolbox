package com.kgr.q25toolbox.inputfix;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;

/**
 * A physical-key handler ported from nozerorma/q25-input-helper. Returns true
 * if it consumed the event (so the app/system shouldn't also see it).
 */
public interface InputFix {
    boolean onKeyEvent(AccessibilityService service, KeyEvent event);
}
