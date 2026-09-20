package com.diamon.curso.utils;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps activity content clear of the system bars and the action bar.
 *
 * With targetSdk 35+ Android draws windows edge to edge, so the content view
 * starts at y=0 and anything at the top of a layout ends up underneath the
 * status bar. The decor action bar also paints over the content in this mode,
 * so its height is added on top of the status bar inset.
 *
 * If some future Android version goes back to reserving the action bar space
 * itself, content would sit too low; the fix then is to drop actionBarPad from
 * the setPadding() call below.
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    /**
     * @param activity  activity whose theme supplies actionBarSize
     * @param root      the layout root to pad
     * @param basePadDp padding the layout would otherwise declare in XML
     */
    public static void apply(final Activity activity, final View root, final int basePadDp) {
        if (activity == null || root == null) {
            return;
        }

        final int basePad = Math.round(
                basePadDp * activity.getResources().getDisplayMetrics().density);

        int measuredActionBar = 0;
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            measuredActionBar = TypedValue.complexToDimensionPixelSize(
                    tv.data, activity.getResources().getDisplayMetrics());
        }
        final int actionBarPad = measuredActionBar;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    bars.left + basePad,
                    bars.top + actionBarPad + basePad,
                    bars.right + basePad,
                    bars.bottom + basePad);
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.requestApplyInsets(root);
    }
}
