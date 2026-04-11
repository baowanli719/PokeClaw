// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Shared structural heuristics for common accessibility action buttons.
 *
 * This keeps deterministic paths generic:
 * - prefer stable structure / ids / geometry first
 * - use visible text or descriptions only as a fallback signal
 * - avoid English-only exact-text assumptions for common actions like "send"
 */
public final class UiActionMatchUtils {
    private static final String[] SEND_ID_HINTS = {
            "send", "reply", "submit", "done", "fab"
    };

    private static final String[] SEND_TEXT_HINTS = {
            "send", "發送", "发送", "傳送", "전송", "送信", "enviar", "envoyer", "senden", "отправить"
    };

    private UiActionMatchUtils() {}

    public static AccessibilityNodeInfo findBestSendAction(AccessibilityNodeInfo root, Rect anchorBounds) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);

        Candidate best = new Candidate();
        collectSendCandidates(root, anchorBounds, screenBounds, best);
        return best.score >= 60 ? best.node : null;
    }

    private static void collectSendCandidates(
            AccessibilityNodeInfo node,
            Rect anchorBounds,
            Rect screenBounds,
            Candidate best
    ) {
        if (node == null) return;

        int score = scoreSendCandidate(node, anchorBounds, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSendCandidates(child, anchorBounds, screenBounds, best);
            }
        }
    }

    private static int scoreSendCandidate(
            AccessibilityNodeInfo node,
            Rect anchorBounds,
            Rect screenBounds
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return Integer.MIN_VALUE;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        int screenArea = Math.max(1, screenBounds.width() * screenBounds.height());
        int area = bounds.width() * bounds.height();
        if (area > screenArea / 3) {
            return Integer.MIN_VALUE;
        }

        boolean actionable = node.isClickable() || node.isLongClickable();
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();

        int score = 0;

        if (actionable) score += 20;

        if (className.contains("ImageButton") || className.contains("FloatingActionButton")) {
            score += 35;
        } else if (className.contains("Button")) {
            score += 25;
        } else if (className.contains("ImageView")) {
            score += 10;
        } else if (className.contains("TextView")) {
            score += 5;
        }

        if (containsIdHint(viewId)) {
            score += 120;
        }

        if (containsTextHint(desc) || containsTextHint(text)) {
            score += 80;
        }

        if (anchorBounds != null && !anchorBounds.isEmpty()) {
            int centerX = bounds.centerX();
            int centerY = bounds.centerY();
            int anchorHeight = Math.max(anchorBounds.height(), 1);

            if (centerY >= anchorBounds.top - anchorHeight && centerY <= anchorBounds.bottom + (anchorHeight * 2)) {
                score += 20;
            }
            if (centerX >= anchorBounds.centerX()) {
                score += 18;
            }
            if (bounds.left >= anchorBounds.left) {
                score += 10;
            }
            if (bounds.top <= anchorBounds.bottom + anchorHeight) {
                score += 10;
            }
        }

        if (!screenBounds.isEmpty()) {
            if (bounds.centerX() >= screenBounds.centerX()) score += 6;
            if (bounds.centerY() >= screenBounds.centerY()) score += 6;
        }

        if (!actionable && score < 80) {
            return Integer.MIN_VALUE;
        }

        return score;
    }

    private static boolean containsIdHint(String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase();
        for (String hint : SEND_ID_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTextHint(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        for (String hint : SEND_TEXT_HINTS) {
            if (UiTextMatchUtils.matchesRelaxed(value, hint)) {
                return true;
            }
        }
        return false;
    }

    private static final class Candidate {
        private AccessibilityNodeInfo node;
        private int score = Integer.MIN_VALUE;
    }
}
