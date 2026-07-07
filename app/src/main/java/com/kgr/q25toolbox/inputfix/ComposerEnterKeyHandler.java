package com.kgr.q25toolbox.inputfix;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ComposerEnterKeyHandler {
    private static final String GOOGLE_MESSAGES_SEND_BUTTON_VIEW_ID = "Compose:Draft:Send";
    private static final List<String> DEFAULT_SUPPORTED_PACKAGES = Arrays.asList(
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "org.telegram",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.wallapop",
            "ai.openai.chatgpt",
            "com.openai.chatgpt",
            "im.vector.app",
            "com.mattermost.rn",
            "chat.mattermost",
            "com.matter",
            "ai.perplexity",
            "chat.perplexity.android",
            "com.perplexity",
            "ai.perplexity.app"
    );
    private static final List<String> DEFAULT_SEND_BUTTON_MATCHERS = Arrays.asList(
            "send",
            "send message",
            "send button",
            "submit",
            "submit message",
            "voice send",
            "message send",
            "composer send",
            "send_message",
            "button_send",
            "message_send",
            "send_button",
            "compose_send",
            "chat_send",
            "ic_send",
            "send_icon",
            "arrow_upward",
            "arrow_up",
            "paper_plane",
            "paperplane"
    );

    private final List<String> supportedPackages;
    private final List<String> sendButtonMatchers;
    private boolean pendingPlainEnterSend;

    public ComposerEnterKeyHandler(String packageName, String sendButtonViewId) {
        this(
                packageName == null ? null : Arrays.asList(packageName),
                sendButtonViewId == null ? null : Arrays.asList(sendButtonViewId)
        );
    }

    public ComposerEnterKeyHandler(List<String> supportedPackages, List<String> sendButtonMatchers) {
        this.supportedPackages = supportedPackages;
        this.sendButtonMatchers = sendButtonMatchers;
    }

    public static List<String> defaultSupportedPackages() {
        return DEFAULT_SUPPORTED_PACKAGES;
    }

    public static List<String> defaultSendButtonMatchers() {
        return DEFAULT_SEND_BUTTON_MATCHERS;
    }

    public boolean onKeyEvent(AccessibilityService service, KeyEvent event) {
        if (!isHandledEnterEvent(event)) {
            return false;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return false;
        }

        try {
            AccessibilityNodeInfo composer = findFocusedComposer(root);
            if (composer == null) {
                pendingPlainEnterSend = false;
                return false;
            }

            try {
                if (hasNonPlainEnterModifier(event)) {
                    pendingPlainEnterSend = false;
                    return false;
                }

                boolean hasDraft = hasDraftText(composer);
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    pendingPlainEnterSend = shouldArmSendOnKeyDown(
                            event.getRepeatCount(),
                            hasDraft,
                            hasClickableSendButton(root)
                    );
                    return pendingPlainEnterSend;
                }

                if (!shouldClickSendButtonOnKeyUp(pendingPlainEnterSend, hasDraft)) {
                    pendingPlainEnterSend = false;
                    return false;
                }

                pendingPlainEnterSend = false;
                AccessibilityNodeInfo sendButton = findSendButton(root);
                if (sendButton == null) {
                    return true;
                }

                try {
                    if (sendButton.isClickable()) {
                        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    return true;
                } finally {
                    sendButton.recycle();
                }
            } finally {
                composer.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    static boolean isHandledEnterEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        return isHandledEnterKey(event.getAction(), event.getKeyCode());
    }

    static boolean isHandledEnterKey(int action, int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER
                && (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP);
    }

    static boolean shouldArmSendOnKeyDown(int repeatCount, boolean hasDraft, boolean hasSendButton) {
        return repeatCount == 0 && hasDraft && hasSendButton;
    }

    static boolean shouldClickSendButtonOnKeyUp(boolean pendingPlainEnterSend, boolean hasDraft) {
        return pendingPlainEnterSend && hasDraft;
    }

    static boolean isAltMetaState(int metaState) {
        return (metaState & (KeyEvent.META_ALT_ON
                | KeyEvent.META_ALT_LEFT_ON
                | KeyEvent.META_ALT_RIGHT_ON)) != 0;
    }

    static boolean isShiftMetaState(int metaState) {
        return (metaState & (KeyEvent.META_SHIFT_ON
                | KeyEvent.META_SHIFT_LEFT_ON
                | KeyEvent.META_SHIFT_RIGHT_ON)) != 0;
    }

    private static boolean isAltPressed(KeyEvent event) {
        return event.isAltPressed() || isAltMetaState(event.getMetaState());
    }

    private static boolean isShiftPressed(KeyEvent event) {
        return event.isShiftPressed() || isShiftMetaState(event.getMetaState());
    }

    private static boolean hasNonPlainEnterModifier(KeyEvent event) {
        return isAltPressed(event) || isShiftPressed(event);
    }

    static boolean hasDraftText(CharSequence text) {
        return text != null && text.toString().trim().length() > 0;
    }

    static CharSequence composerDraftText(CharSequence text, boolean showingHintText) {
        return showingHintText ? "" : text;
    }

    static boolean isSupportedPackage(List<String> supportedPackages, CharSequence packageName) {
        if (supportedPackages == null || packageName == null) {
            return false;
        }

        String actualPackage = packageName.toString();
        for (String supportedPackage : supportedPackages) {
            if (supportedPackage == null) {
                continue;
            }
            if (actualPackage.equals(supportedPackage) || actualPackage.startsWith(supportedPackage + ".")) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelySendButton(
            List<String> sendButtonMatchers,
            CharSequence viewId,
            CharSequence contentDescription,
            CharSequence text
    ) {
        if (GOOGLE_MESSAGES_SEND_BUTTON_VIEW_ID.contentEquals(emptyIfNull(viewId))) {
            return true;
        }
        if (sendButtonMatchers == null) {
            return false;
        }

        return matchesAny(sendButtonMatchers, viewId, true)
                || matchesAny(sendButtonMatchers, contentDescription, false)
                || matchesAny(sendButtonMatchers, text, false);
    }

    private static boolean hasDraftText(AccessibilityNodeInfo composer) {
        return composer != null && hasDraftText(composerDraftText(composer.getText(), isShowingHintText(composer)));
    }

    private static boolean isShowingHintText(AccessibilityNodeInfo node) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText();
    }

    private AccessibilityNodeInfo findFocusedComposer(AccessibilityNodeInfo root) {
        if (!isSupportedPackage(supportedPackages, root.getPackageName())) {
            return null;
        }

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            return null;
        }

        if (focused.isEditable() && isSupportedPackage(supportedPackages, focused.getPackageName())) {
            return focused;
        } else {
            focused.recycle();
            return null;
        }
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }

        if (isLikelySendButton(
                sendButtonMatchers,
                root.getViewIdResourceName(),
                root.getContentDescription(),
                root.getText()
        ) && root.isClickable()) {
            return AccessibilityNodeInfo.obtain(root);
        }

        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) {
                continue;
            }

            try {
                AccessibilityNodeInfo match = findSendButton(child);
                if (match != null) {
                    return match;
                }
            } finally {
                child.recycle();
            }
        }

        if (root.isClickable() && hasSendButtonLabelInChildren(root)) {
            return AccessibilityNodeInfo.obtain(root);
        }

        return null;
    }

    private boolean hasSendButtonLabelInChildren(AccessibilityNodeInfo root) {
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) {
                continue;
            }

            try {
                if (isLikelySendButton(
                        sendButtonMatchers,
                        child.getViewIdResourceName(),
                        child.getContentDescription(),
                        child.getText()
                ) || hasSendButtonLabelInChildren(child)) {
                    return true;
                }
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean hasClickableSendButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo sendButton = findSendButton(root);
        if (sendButton == null) {
            return false;
        }

        try {
            return sendButton.isClickable();
        } finally {
            sendButton.recycle();
        }
    }

    private static boolean matchesAny(
            List<String> matchers,
            CharSequence value,
            boolean allowContains
    ) {
        if (value == null) {
            return false;
        }

        String normalizedValue = normalize(value);
        if (normalizedValue.length() == 0) {
            return false;
        }

        for (String matcher : matchers) {
            if (matcher == null) {
                continue;
            }
            String normalizedMatcher = normalize(matcher);
            if (normalizedValue.equals(normalizedMatcher)) {
                return true;
            }
            if (allowContains && normalizedValue.contains(normalizedMatcher)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(CharSequence value) {
        return value.toString()
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static CharSequence emptyIfNull(CharSequence value) {
        return value == null ? "" : value;
    }
}
