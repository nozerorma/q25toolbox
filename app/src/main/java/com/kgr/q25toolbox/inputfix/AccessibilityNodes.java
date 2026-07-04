package com.kgr.q25toolbox.inputfix;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class AccessibilityNodes {
    private AccessibilityNodes() {
    }

    public static boolean hasExactlyOneNode(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        try {
            return nodes.size() == 1;
        } finally {
            recycleAll(nodes);
        }
    }

    public static AccessibilityNodeInfo findSingleNode(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        if (nodes.size() != 1) {
            recycleAll(nodes);
            return null;
        }

        AccessibilityNodeInfo result = nodes.get(0);
        for (int i = 1; i < nodes.size(); i++) {
            nodes.get(i).recycle();
        }

        return result;
    }

    public static AccessibilityNodeInfo findSingleNodeInTree(AccessibilityNodeInfo root, String viewId) {
        return findSingleNodeInTree(root, viewId, Collections.emptyList());
    }

    public static AccessibilityNodeInfo findSingleNodeInTree(AccessibilityNodeInfo root, String viewId, CharSequence fallbackText) {
        if (fallbackText == null) {
            return findSingleNodeInTree(root, viewId, Collections.emptyList());
        }
        return findSingleNodeInTree(root, viewId, Collections.singletonList(fallbackText));
    }

    public static AccessibilityNodeInfo findSingleNodeInTree(
            AccessibilityNodeInfo root,
            String viewId,
            Collection<? extends CharSequence> fallbackTexts
    ) {
        if (root == null) return null;

        AccessibilityNodeInfo match = null;
        CharSequence rootViewId = root.getViewIdResourceName();
        if (viewId != null && rootViewId != null && viewId.contentEquals(rootViewId)) {
            match = AccessibilityNodeInfo.obtain(root);
        } else if (isActionableMatch(root, fallbackTexts)) {
            match = AccessibilityNodeInfo.obtain(root);
        }

        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;

            try {
                AccessibilityNodeInfo childMatch = findSingleNodeInTree(child, viewId, fallbackTexts);
                if (childMatch == null) continue;
                if (match != null) {
                    match.recycle();
                    childMatch.recycle();
                    return null;
                }

                match = childMatch;
            } finally {
                child.recycle();
            }
        }

        return match;
    }

    private static boolean isActionableMatch(
            AccessibilityNodeInfo node,
            Collection<? extends CharSequence> expectedTexts
    ) {
        if (node == null || expectedTexts == null || expectedTexts.isEmpty()) return false;
        return node.isClickable() && hasTextInTree(node, expectedTexts);
    }

    private static boolean hasTextInTree(
            AccessibilityNodeInfo node,
            Collection<? extends CharSequence> expectedTexts
    ) {
        CharSequence contentDescription = node.getContentDescription();
        if (containsCharSequence(expectedTexts, contentDescription)) return true;

        CharSequence nodeText = node.getText();
        if (containsCharSequence(expectedTexts, nodeText)) return true;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (hasTextInTree(child, expectedTexts)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private static boolean containsCharSequence(Collection<? extends CharSequence> haystack, CharSequence needle) {
        if (needle == null) return false;
        for (CharSequence value : haystack) {
            if (equalsCharSequence(value, needle)) return true;
        }
        return false;
    }

    private static boolean equalsCharSequence(CharSequence left, CharSequence right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.toString().equals(right.toString());
    }

    public static boolean hasPackage(AccessibilityNodeInfo node, String packageName) {
        CharSequence nodePackage = node.getPackageName();
        return nodePackage != null && packageName.contentEquals(nodePackage);
    }

    public static boolean isDescendantOf(AccessibilityNodeInfo node, AccessibilityNodeInfo ancestor) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            boolean matches = parent.equals(ancestor);
            AccessibilityNodeInfo nextParent = matches ? null : parent.getParent();
            parent.recycle();
            if (matches) return true;
            parent = nextParent;
        }

        return false;
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            node.recycle();
        }
    }
}
