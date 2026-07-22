package com.infopunch.checker;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.WeakHashMap;

public class ThemeManager {
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_RED = "red";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_GRAY = "gray";
    private static final String PREFS = "cardinal_punch_ui_prefs";
    private static final String KEY_THEME_NAME = "theme_name";
    private static final WeakHashMap<View, int[]> BASE_PADDING = new WeakHashMap<>();
    public static final String TAG_KEEP_CUSTOM_THEME = "keep_custom_theme";

    public static Palette palette(Context context) {
        return palette(getThemeName(context));
    }

    public static String getThemeName(Context context) {
        return prefs(context).getString(KEY_THEME_NAME, THEME_GREEN);
    }

    public static void setThemeName(Context context, String themeName) {
        prefs(context).edit().putString(
                KEY_THEME_NAME,
                themeName == null || themeName.trim().isEmpty() ? THEME_GREEN : themeName.trim()
        ).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Palette palette(String theme) {
        String value = theme == null ? "" : theme;
        switch (value) {
            case THEME_LIGHT:
                return new Palette(0xFFF4F7F5, 0xFFFFFFFF, 0xFFE5ECE8, 0xFFC8DAD0, 0xFF111917, 0xFF51615A, 0xFF256B50, 0xFFE7F4EE, 0xFFDAF5E8, 0xFF195D3D, 0xFFFFF0CC, 0xFF7A5500, 0xFFFFE3E0, 0xFF8B2A2A);
            case THEME_DARK:
                return new Palette(0xFF030405, 0xFF0B0D10, 0xFF15191F, 0xFF2B313A, 0xFFF6F7F8, 0xFFB4BAC2, 0xFF78A8FF, 0xFF101B2E, 0xFF0E251A, 0xFF78E6A3, 0xFF2B2412, 0xFFFFD66B, 0xFF2A1215, 0xFFFF8E9A);
            case THEME_RED:
                return new Palette(0xFF170E10, 0xFF241619, 0xFF332024, 0xFF5A2A30, 0xFFFFF5F4, 0xFFE0B8B4, 0xFFE45858, 0xFF3E1B1D, 0xFF302117, 0xFFFFC08C, 0xFF3B2A12, 0xFFFFD77D, 0xFF3A1F1F, 0xFFFFAEA8);
            case THEME_BLUE:
                return new Palette(0xFF0B1220, 0xFF131D2E, 0xFF1B2A42, 0xFF31466B, 0xFFF2F7FF, 0xFFB5C5DE, 0xFF4E8FE8, 0xFF172B4C, 0xFF142A3E, 0xFFAED3FF, 0xFF322A13, 0xFFFFDA7A, 0xFF321B25, 0xFFFFA6BE);
            case THEME_GRAY:
                return new Palette(0xFF202225, 0xFF2B2E32, 0xFF383C41, 0xFF565D66, 0xFFF4F5F5, 0xFFD0D5DA, 0xFFA7B0BB, 0xFF454B52, 0xFF303D36, 0xFFC7E8D2, 0xFF433A22, 0xFFFFD985, 0xFF442D31, 0xFFFFB2BA);
            case THEME_GREEN:
            default:
                return new Palette(0xFF0C1411, 0xFF14201B, 0xFF1B2A24, 0xFF2F4A3E, 0xFFF1F6F3, 0xFFA9BBB1, 0xFF5FD69C, 0xFF173529, 0xFF173326, 0xFF8BE0B4, 0xFF3B2E12, 0xFFFFD77D, 0xFF3A1F1F, 0xFFFFAEA8);
        }
    }

    public static void apply(Activity activity) {
        Palette palette = palette(activity);
        activity.getWindow().setStatusBarColor(palette.screen);
        activity.getWindow().setNavigationBarColor(palette.screen);
        View root = activity.findViewById(android.R.id.content);
        if (root != null) {
            root.setBackgroundColor(palette.screen);
            applySystemBarPadding(root);
            applyRecursive(root, palette);
        }
    }

    private static void applyRecursive(View view, Palette palette) {
        if (TAG_KEEP_CUSTOM_THEME.equals(view.getTag())) {
            return;
        }
        if (view instanceof ScrollView) {
            view.setBackgroundColor(palette.screen);
        }
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
            if (!(view instanceof Button) && view.getBackground() != null) {
                setBackgroundPreservePadding(view, cardDrawable(palette.surfaceSecondary, palette.border, 16f));
            }
        }
        if (view instanceof Button) {
            Button button = (Button) view;
            boolean primary = isPrimaryButton(button);
            button.setTextColor(primary ? Color.WHITE : palette.text);
            setBackgroundPreservePadding(button, cardDrawable(primary ? palette.accent : palette.surfaceSecondary, primary ? palette.accent : palette.border, 16f));
        } else if (view instanceof EditText) {
            EditText editText = (EditText) view;
            editText.setTextColor(palette.text);
            editText.setHintTextColor(palette.textSecondary);
            setBackgroundPreservePadding(editText, cardDrawable(palette.surfaceSecondary, palette.border, 14f));
        } else if (view instanceof Spinner) {
            setBackgroundPreservePadding(view, cardDrawable(palette.surfaceSecondary, palette.border, 14f));
        } else if (view instanceof LinearLayout && view.getId() != android.R.id.content && view.getBackground() != null) {
            setBackgroundPreservePadding(view, cardDrawable(palette.surface, palette.border, 18f));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRecursive(group.getChildAt(i), palette);
            }
        }
    }

    private static boolean isPrimaryButton(Button button) {
        try {
            String name = button.getResources().getResourceEntryName(button.getId()).toLowerCase();
            return name.contains("connect")
                    || name.contains("hours")
                    || name.contains("previous")
                    || name.contains("install")
                    || name.contains("add")
                    || name.contains("register");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static GradientDrawable cardDrawable(int color, int strokeColor, float cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(cornerDp));
        drawable.setStroke((int) dp(1f), strokeColor);
        return drawable;
    }

    private static void setBackgroundPreservePadding(View view, GradientDrawable drawable) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setBackground(drawable);
        view.setPadding(left, top, right, bottom);
    }

    private static float dp(float value) {
        return value * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    private static void applySystemBarPadding(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        View target = ((ViewGroup) root).getChildCount() > 0 ? ((ViewGroup) root).getChildAt(0) : root;
        int[] base = BASE_PADDING.get(target);
        if (base == null) {
            base = new int[]{target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), target.getPaddingBottom()};
            BASE_PADDING.put(target, base);
        }
        int bottomInset = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                bottomInset = insets.getStableInsetBottom();
            }
        }
        target.setPadding(base[0], base[1], base[2], base[3] + Math.max(bottomInset, (int) dp(18f)));
    }

    public static class Palette {
        public final int screen;
        public final int surface;
        public final int surfaceSecondary;
        public final int border;
        public final int text;
        public final int textSecondary;
        public final int accent;
        public final int accentSoft;
        public final int successSoft;
        public final int successText;
        public final int warningSoft;
        public final int warningText;
        public final int dangerSoft;
        public final int dangerText;

        Palette(int screen, int surface, int surfaceSecondary, int border, int text, int textSecondary, int accent, int accentSoft, int successSoft, int successText, int warningSoft, int warningText, int dangerSoft, int dangerText) {
            this.screen = screen;
            this.surface = surface;
            this.surfaceSecondary = surfaceSecondary;
            this.border = border;
            this.text = text;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.successSoft = successSoft;
            this.successText = successText;
            this.warningSoft = warningSoft;
            this.warningText = warningText;
            this.dangerSoft = dangerSoft;
            this.dangerText = dangerText;
        }
    }
}
