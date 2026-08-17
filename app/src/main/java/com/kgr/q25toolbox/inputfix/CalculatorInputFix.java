package com.kgr.q25toolbox.inputfix;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;


import java.util.Arrays;
import java.util.List;

public final class CalculatorInputFix implements InputFix {
    static final String AOSP_CALCULATOR_PACKAGE = "com.android.calculator2";
    static final String LEGACY_CALCULATOR_PACKAGE = "com.android.calculator";
    static final String GOOGLE_CALCULATOR_PACKAGE = "com.google.android.calculator";
    static final String FORMULA_VIEW_NAME = "formula";
    private static final List<String> CALCULATOR_PACKAGES = Arrays.asList(
            AOSP_CALCULATOR_PACKAGE,
            LEGACY_CALCULATOR_PACKAGE,
            GOOGLE_CALCULATOR_PACKAGE
    );

    /**
     * Cheap "is the foreground app even a calculator?" check against the package the
     * accessibility service already tracks, so the caller can skip the
     * getRootInActiveWindow() binder call this otherwise makes on every digit/operator
     * keypress in every app. A null package means the caller doesn't know yet - answer
     * yes and let the root-based check below decide.
     */
    public static boolean isCalculatorPackage(String packageName) {
        return packageName == null || CALCULATOR_PACKAGES.contains(packageName);
    }

    @Override
    public boolean onKeyEvent(AccessibilityService service, KeyEvent event) {
        Q25KeyTranslator.Input input = Q25KeyTranslator.toCalculatorInput(event.getKeyCode());
        if (input == null) {
            return false;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return false;
        }

        try {
            String packageName = activeCalculatorPackage(root);
            if (packageName == null) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            String directText = Q25KeyTranslator.calculatorDirectText(input);
            if (directText != null) {
                boolean inserted = appendFormulaText(root, packageName, directText);
                if (inserted) {
                    return true;
                }
            }

            AccessibilityNodeInfo button = findCalculatorButton(root, packageName, input);
            if (button == null) {
                return false;
            }

            try {
                if (!button.isClickable()) {
                    return false;
                }
                if (event.getRepeatCount() > 0) {
                    return true;
                }
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            } finally {
                button.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private static boolean appendFormulaText(AccessibilityNodeInfo root, String packageName, String text) {
        AccessibilityNodeInfo formula = AccessibilityNodes.findSingleNodeInTree(
                root,
                packageName + ":id/" + FORMULA_VIEW_NAME
        );
        if (formula == null) {
            return false;
        }

        try {
            CharSequence currentText = formula.getText();
            String current = currentText == null ? "" : currentText.toString();
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    current + text
            );
            return formula.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        } finally {
            formula.recycle();
        }
    }

    private static AccessibilityNodeInfo findCalculatorButton(
            AccessibilityNodeInfo root,
            String packageName,
            Q25KeyTranslator.Input input
    ) {
        AccessibilityNodeInfo button = findFirstClickableNodeById(
                root,
                Q25KeyTranslator.calculatorButtonId(packageName, input)
        );
        if (button != null) {
            return button;
        }

        return AccessibilityNodes.findSingleNodeInTree(
                root,
                null,
                Q25KeyTranslator.calculatorButtonFallbackLabels(input)
        );
    }

    private static AccessibilityNodeInfo findFirstClickableNodeById(
            AccessibilityNodeInfo root,
            String viewId
    ) {
        if (root == null || viewId == null) {
            return null;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        AccessibilityNodeInfo fallback = null;
        try {
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) {
                    continue;
                }
                if (node.isClickable()) {
                    if (fallback != null) {
                        fallback.recycle();
                        fallback = null;
                    }
                    return AccessibilityNodeInfo.obtain(node);
                }
                if (fallback == null) {
                    fallback = AccessibilityNodeInfo.obtain(node);
                }
            }
            return fallback == null ? null : AccessibilityNodeInfo.obtain(fallback);
        } finally {
            if (fallback != null) {
                fallback.recycle();
            }
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null) {
                    node.recycle();
                }
            }
        }
    }

    private static String activeCalculatorPackage(AccessibilityNodeInfo root) {
        for (String packageName : CALCULATOR_PACKAGES) {
            if (AccessibilityNodes.hasPackage(root, packageName)) {
                return packageName;
            }
        }

        return null;
    }
}
