package com.ghmanager.app;

import android.view.View;
import android.widget.Button;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

final class A11y {
    private A11y() {
    }

    static AccessibilityDelegateCompat button() {
        return new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        };
    }
}
