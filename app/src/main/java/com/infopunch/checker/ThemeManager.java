package com.infopunch.checker;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class ThemeManager {
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_RED = "red";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_GRAY = "gray";

    public static Palette palette(Context context) {
        try {
            return palette(new SessionManager(context).getThemeName());
        } catch (Exception ignored) {
            return palette(THEME_GREEN);
        }
    }

    public static Palette palette(String theme) {
        String value = theme == null ? "" : theme;
        switch (value) {
            case THEME_LIGHT:
                return new Palette(0xFFF4F7F5, 0xFFFFFFFF, 0xFFE5ECE8, 0xFF111917, 0xFF51615A, 0xFF256B50, 0xFFE7F4EE, 0xFFDAF5E8, 0xFF195D3D);
            case THEME_DARK:
                return new Palette(0xFF0C1110, 0xFF151C1A, 0xFF202A27, 0xFFF2F6F4, 0xFFA8B7B1, 0xFF5FD69C, 0xFF173529, 0xFF163126, 0xFF8BE0B4);
            case THEME_RED:
                return new Palette(0xFF170E10, 0xFF241619, 0xFF332024, 0xFFFFF5F4, 0xFFE0B8B4, 0xFFE45858, 0xFF3E1B1D, 0xFF3A221E, 0xFFFFB0A6);
            case THEME_BLUE:
                return new Palette(0xFF0B1220, 0xFF131D2E, 0xFF1B2A42, 0xFFF2F7FF, 0xFFB5C5DE, 0xFF4E8FE8, 0xFF172B4C, 0xFF142A3E, 0xFFAED3FF);
            case THEME_GRAY:
                return new Palette(0xFF121415, 0xFF1E2224, 0xFF2B3033, 0xFFF4F5F5, 0xFFC2C8CA, 0xFF8B979D, 0xFF30383C, 0xFF26332D, 0xFFBDE2C8);
            case THEME_GREEN:
            default:
                return new Palette(0xFF0C1411, 0xFF14201B, 0xFF1B2A24, 0xFFF1F6F3, 0xFFA9BBB1, 0xFF5FD69C, 0xFF173529, 0xFF173326, 0xFF8BE0B4);
        }
    }

    public static void apply(Activity activity) {
        Palette palette = palette(activity);
        activity.getWindow().setStatusBarColor(palette.screen);
        activity.getWindow().setNavigationBarColor(palette.screen);
        View root = activity.findViewById(android.R.id.content);
        if (root != null) {
            root.setBackgroundColor(palette.screen);
            applyRecursive(root, palette);
        }
    }

    private static void applyRecursive(View view, Palette palette) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            float textSp = textView.getTextSize() / textView.getResources().getDisplayMetrics().scaledDensity;
            textView.setTextColor(textView.getTypeface() != null && textView.getTypeface().isBold()
                    ? palette.text
                    : palette.textSecondary);
            if (textSp >= 18f) {
                textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                textView.setTextColor(palette.text);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRecursive(group.getChildAt(i), palette);
            }
        }
    }

    public static class Palette {
        public final int screen;
        public final int surface;
        public final int surfaceSecondary;
        public final int text;
        public final int textSecondary;
        public final int accent;
        public final int accentSoft;
        public final int successSoft;
        public final int successText;

        Palette(int screen, int surface, int surfaceSecondary, int text, int textSecondary, int accent, int accentSoft, int successSoft, int successText) {
            this.screen = screen;
            this.surface = surface;
            this.surfaceSecondary = surfaceSecondary;
            this.text = text;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.successSoft = successSoft;
            this.successText = successText;
        }
    }
}
