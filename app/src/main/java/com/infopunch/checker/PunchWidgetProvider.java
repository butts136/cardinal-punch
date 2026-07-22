package com.infopunch.checker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.RemoteViews;

import com.infopunch.checker.hours.HoursModels;
import com.infopunch.checker.hours.HoursRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PunchWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_PREVIOUS = "com.infopunch.checker.widget.PREVIOUS";
    private static final String ACTION_NEXT = "com.infopunch.checker.widget.NEXT";
    private static final String ACTION_REFRESH = "com.infopunch.checker.widget.REFRESH";
    private static final String EXTRA_WIDGET_ID = "widget_id";
    private static final long SELECTED_DAY_RESET_MS = 5L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter TITLE_FORMAT = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.CANADA_FRENCH);

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgetsAsync(context, appWidgetIds, null);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateWidgetsAsync(context, new int[]{appWidgetId}, getSelectedDate(context, appWidgetId));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_PREVIOUS.equals(action) || ACTION_NEXT.equals(action) || ACTION_REFRESH.equals(action)) {
            int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return;
            }
            LocalDate selectedDate = getSelectedDate(context, widgetId);
            if (ACTION_PREVIOUS.equals(action)) {
                selectedDate = selectedDate.minusDays(1);
            } else if (ACTION_NEXT.equals(action)) {
                selectedDate = selectedDate.plusDays(1);
                if (selectedDate.isAfter(LocalDate.now())) {
                    selectedDate = LocalDate.now();
                }
            }
            setSelectedDate(context, widgetId, selectedDate);
            updateWidgetsAsync(context, new int[]{widgetId}, selectedDate);
        }
    }

    private void updateWidgetsAsync(Context context, int[] widgetIds, LocalDate forcedDate) {
        EXECUTOR.execute(() -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            for (int widgetId : widgetIds) {
                renderWidget(context, manager, widgetId, forcedDate != null ? forcedDate : getSelectedDate(context, widgetId));
            }
        });
    }

    private void renderWidget(Context context, AppWidgetManager manager, int widgetId, LocalDate selectedDate) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_punch);
        ThemeManager.Palette palette = ThemeManager.palette(context);
        applyTheme(views, palette);
        applyResponsiveLayout(manager, widgetId, views);
        views.setOnClickPendingIntent(R.id.prevButton, buildActionIntent(context, widgetId, ACTION_PREVIOUS));
        views.setOnClickPendingIntent(R.id.nextButton, buildActionIntent(context, widgetId, ACTION_NEXT));

        Intent launchIntent = new Intent(context, HoursActivity.class);
        PendingIntent launchPendingIntent = PendingIntent.getActivity(
                context,
                widgetId + 5000,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, launchPendingIntent);
        views.setOnClickPendingIntent(R.id.commentButton, launchPendingIntent);

        try {
            SessionManager sessionManager = new SessionManager(context);
            SessionManager.SessionData session = sessionManager.getSession();
            if (session == null) {
                views.setTextViewText(R.id.dayTitleView, "Aucun compte actif");
                bindSummary(views, "-", "-", "-");
                bindPunchRows(views, null);
                views.setViewVisibility(R.id.emptyView, android.view.View.VISIBLE);
                views.setTextViewText(R.id.emptyView, "Connecte un compte pour afficher les poincons.");
                manager.updateAppWidget(widgetId, views);
                return;
            }

            HoursRepository repository = new HoursRepository();
            HoursModels.WeekData weekData = repository.loadWeekForDate(session, selectedDate);
            HoursModels.DayEntry dayEntry = findDay(weekData.days, selectedDate);
            if (BuildConfig.EXTERNAL_UPDATES_ENABLED) {
                AppUpdateManager updateManager = new AppUpdateManager(context);
                AppUpdateManager.UpdateState updateState = updateManager.getState();
                if (updateState.updateAvailable) {
                    views.setViewVisibility(R.id.updateBannerView, android.view.View.VISIBLE);
                    views.setTextViewText(R.id.updateBannerView, "Mise a jour " + updateState.latestVersion + " disponible");
                } else {
                    views.setViewVisibility(R.id.updateBannerView, android.view.View.GONE);
                }
            } else {
                views.setViewVisibility(R.id.updateBannerView, android.view.View.GONE);
            }
            views.setTextViewText(R.id.dayTitleView, capitalize(selectedDate.format(TITLE_FORMAT)));
            bindSummary(
                    views,
                    dayEntry != null ? safe(dayEntry.regularHours) : "-",
                    dayEntry != null ? safe(dayEntry.overtimeHours) : "-",
                    dayEntry != null ? HoursModels.formatMinutes(dayEntry.getTotalMinutes()) : "-"
            );
            bindPunchRows(views, dayEntry);
            manager.updateAppWidget(widgetId, views);
        } catch (Exception exception) {
            views.setViewVisibility(R.id.updateBannerView, android.view.View.GONE);
            views.setTextViewText(R.id.dayTitleView, capitalize(selectedDate.format(TITLE_FORMAT)));
            bindSummary(views, "-", "-", "-");
            bindPunchRows(views, null);
            views.setViewVisibility(R.id.emptyView, android.view.View.VISIBLE);
            views.setTextViewText(R.id.emptyView, "Chargement impossible.");
            manager.updateAppWidget(widgetId, views);
        }
    }

    private PendingIntent buildActionIntent(Context context, int widgetId, String action) {
        Intent intent = new Intent(context, PunchWidgetProvider.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_WIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(
                context,
                (action + widgetId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private HoursModels.DayEntry findDay(List<HoursModels.DayEntry> days, LocalDate targetDate) {
        for (HoursModels.DayEntry day : days) {
            if (day.date != null && day.date.equals(targetDate)) {
                return day;
            }
        }
        return null;
    }

    private void bindSummary(RemoteViews views, String regular, String overtime, String total) {
        views.setTextViewText(R.id.regularValue, regular);
        views.setTextViewText(R.id.overtimeValue, overtime);
        views.setTextViewText(R.id.totalValue, total);
    }

    private void bindPunchRows(RemoteViews views, HoursModels.DayEntry dayEntry) {
        int[] rows = {R.id.punchRow1, R.id.punchRow2, R.id.punchRow3};
        int[] entries = {R.id.entry1, R.id.entry2, R.id.entry3};
        int[] exits = {R.id.exit1, R.id.exit2, R.id.exit3};
        List<PunchDisplay.Pair> pairs = dayEntry != null ? PunchDisplay.parsePairs(dayEntry.shifts) : java.util.Collections.emptyList();
        boolean hasPairs = !pairs.isEmpty();
        views.setViewVisibility(R.id.emptyView, hasPairs ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setTextViewText(R.id.emptyView, hasPairs ? "" : "Aucun poincon visible pour cette journee.");
        for (int i = 0; i < rows.length; i++) {
            if (i < pairs.size()) {
                PunchDisplay.Pair pair = pairs.get(i);
                views.setViewVisibility(rows[i], android.view.View.VISIBLE);
                views.setTextViewText(entries[i], pair.entry);
                views.setTextViewText(exits[i], pair.exit);
            } else {
                views.setViewVisibility(rows[i], android.view.View.GONE);
                views.setTextViewText(entries[i], "");
                views.setTextViewText(exits[i], "");
            }
        }
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private LocalDate getSelectedDate(Context context, int widgetId) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE);
        long changedAt = prefs.getLong("selected_date_changed_at_" + widgetId, 0L);
        if (changedAt > 0 && System.currentTimeMillis() - changedAt > SELECTED_DAY_RESET_MS) {
            setSelectedDate(context, widgetId, LocalDate.now());
            return LocalDate.now();
        }
        String stored = prefs
                .getString("selected_date_" + widgetId, "");
        if (stored == null || stored.isEmpty()) {
            return LocalDate.now();
        }
        return LocalDate.parse(stored);
    }

    private void setSelectedDate(Context context, int widgetId, LocalDate date) {
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("selected_date_" + widgetId, date.toString())
                .putLong("selected_date_changed_at_" + widgetId, date.equals(LocalDate.now()) ? 0L : System.currentTimeMillis())
                .apply();
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.CANADA_FRENCH) + value.substring(1);
    }

    private void applyResponsiveLayout(AppWidgetManager manager, int widgetId, RemoteViews views) {
        WidgetSizing sizing = resolveSizing(manager.getAppWidgetOptions(widgetId));

        views.setViewPadding(R.id.widgetRoot, sizing.rootPaddingDp, sizing.rootPaddingDp, sizing.rootPaddingDp, sizing.rootPaddingDp);
        views.setViewPadding(R.id.prevButton, sizing.buttonHorizontalPaddingDp, sizing.buttonVerticalPaddingDp, sizing.buttonHorizontalPaddingDp, sizing.buttonVerticalPaddingDp);
        views.setViewPadding(R.id.nextButton, sizing.buttonHorizontalPaddingDp, sizing.buttonVerticalPaddingDp, sizing.buttonHorizontalPaddingDp, sizing.buttonVerticalPaddingDp);
        views.setViewPadding(R.id.dateNavigation, sizing.navContainerPaddingDp, sizing.navContainerPaddingDp, sizing.navContainerPaddingDp, sizing.navContainerPaddingDp);
        views.setViewPadding(R.id.punchTable, sizing.tablePaddingDp, sizing.tablePaddingDp, sizing.tablePaddingDp, sizing.tablePaddingDp);
        views.setViewPadding(R.id.commentButton, sizing.commentHorizontalPaddingDp, sizing.commentVerticalPaddingDp, sizing.commentHorizontalPaddingDp, sizing.commentVerticalPaddingDp);

        views.setTextViewTextSize(R.id.prevButton, TypedValue.COMPLEX_UNIT_SP, sizing.navTextSp);
        views.setTextViewTextSize(R.id.dayTitleView, TypedValue.COMPLEX_UNIT_SP, sizing.titleTextSp);
        views.setTextViewTextSize(R.id.nextButton, TypedValue.COMPLEX_UNIT_SP, sizing.navTextSp);
        views.setTextViewTextSize(R.id.regularLabel, TypedValue.COMPLEX_UNIT_SP, sizing.summaryLabelTextSp);
        views.setTextViewTextSize(R.id.overtimeLabel, TypedValue.COMPLEX_UNIT_SP, sizing.summaryLabelTextSp);
        views.setTextViewTextSize(R.id.totalLabel, TypedValue.COMPLEX_UNIT_SP, sizing.summaryLabelTextSp);
        views.setTextViewTextSize(R.id.regularValue, TypedValue.COMPLEX_UNIT_SP, sizing.summaryValueTextSp);
        views.setTextViewTextSize(R.id.overtimeValue, TypedValue.COMPLEX_UNIT_SP, sizing.summaryValueTextSp);
        views.setTextViewTextSize(R.id.totalValue, TypedValue.COMPLEX_UNIT_SP, sizing.summaryValueTextSp);
        views.setTextViewTextSize(R.id.entryHead, TypedValue.COMPLEX_UNIT_SP, sizing.headerTextSp);
        views.setTextViewTextSize(R.id.exitHead, TypedValue.COMPLEX_UNIT_SP, sizing.headerTextSp);
        views.setTextViewTextSize(R.id.entry1, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.exit1, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.entry2, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.exit2, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.entry3, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.exit3, TypedValue.COMPLEX_UNIT_SP, sizing.punchTextSp);
        views.setTextViewTextSize(R.id.commentButton, TypedValue.COMPLEX_UNIT_SP, sizing.commentTextSp);
    }

    private void applyTheme(RemoteViews views, ThemeManager.Palette palette) {
        views.setInt(R.id.widgetRoot, "setBackgroundColor", palette.surface);
        views.setInt(R.id.dateNavigation, "setBackgroundColor", palette.surfaceSecondary);
        views.setInt(R.id.summaryRow, "setBackgroundColor", palette.surface);
        views.setInt(R.id.regularCard, "setBackgroundColor", palette.surfaceSecondary);
        views.setInt(R.id.overtimeCard, "setBackgroundColor", palette.surfaceSecondary);
        views.setInt(R.id.totalCard, "setBackgroundColor", palette.surfaceSecondary);
        views.setInt(R.id.punchTable, "setBackgroundColor", palette.surfaceSecondary);
        views.setInt(R.id.commentButton, "setBackgroundColor", palette.accent);
        views.setTextColor(R.id.dayTitleView, palette.text);
        views.setTextColor(R.id.prevButton, palette.accent);
        views.setTextColor(R.id.nextButton, palette.accent);
        setSummaryColors(views, palette);
        setPunchCellColors(views, palette);
        views.setTextColor(R.id.emptyView, palette.textSecondary);
        views.setTextColor(R.id.commentButton, android.graphics.Color.WHITE);
    }

    private void setSummaryColors(RemoteViews views, ThemeManager.Palette palette) {
        int[] labels = {R.id.regularLabel, R.id.overtimeLabel, R.id.totalLabel};
        int[] values = {R.id.regularValue, R.id.overtimeValue, R.id.totalValue};
        for (int label : labels) {
            views.setTextColor(label, palette.textSecondary);
        }
        for (int value : values) {
            views.setTextColor(value, palette.text);
        }
    }

    private void setPunchCellColors(RemoteViews views, ThemeManager.Palette palette) {
        int[] cells = {R.id.entry1, R.id.exit1, R.id.entry2, R.id.exit2, R.id.entry3, R.id.exit3};
        for (int cell : cells) {
            views.setTextColor(cell, palette.text);
            views.setInt(cell, "setBackgroundColor", palette.surface);
        }
    }

    private WidgetSizing resolveSizing(Bundle options) {
        int minWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) : 180;
        int minHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) : 140;
        int maxWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidthDp) : minWidthDp;
        int maxHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeightDp) : minHeightDp;
        int usableWidthDp = Math.max(minWidthDp, maxWidthDp);
        int usableHeightDp = Math.max(minHeightDp, maxHeightDp);
        int footprint = Math.min(usableWidthDp, usableHeightDp);

        if (usableWidthDp >= 300 || usableHeightDp >= 260 || footprint >= 220) {
            return new WidgetSizing(18, 9, 8, 14, 14, 16, 14, 34f, 15f, 11f, 17f, 14f, 22f, 15f);
        }
        if (usableWidthDp >= 230 || usableHeightDp >= 200 || footprint >= 170) {
            return new WidgetSizing(14, 8, 7, 12, 12, 13, 12, 32f, 14f, 10f, 16f, 13f, 20f, 14f);
        }
        return new WidgetSizing(10, 6, 6, 10, 10, 11, 10, 28f, 12f, 9f, 14f, 12f, 18f, 12f);
    }

    private static class WidgetSizing {
        final int rootPaddingDp;
        final int buttonHorizontalPaddingDp;
        final int buttonVerticalPaddingDp;
        final int navContainerPaddingDp;
        final int tablePaddingDp;
        final int commentHorizontalPaddingDp;
        final int commentVerticalPaddingDp;
        final float navTextSp;
        final float titleTextSp;
        final float summaryLabelTextSp;
        final float summaryValueTextSp;
        final float headerTextSp;
        final float punchTextSp;
        final float commentTextSp;

        WidgetSizing(
                int rootPaddingDp,
                int buttonHorizontalPaddingDp,
                int buttonVerticalPaddingDp,
                int navContainerPaddingDp,
                int tablePaddingDp,
                int commentHorizontalPaddingDp,
                int commentVerticalPaddingDp,
                float navTextSp,
                float titleTextSp,
                float summaryLabelTextSp,
                float summaryValueTextSp,
                float headerTextSp,
                float punchTextSp,
                float commentTextSp
        ) {
            this.rootPaddingDp = rootPaddingDp;
            this.buttonHorizontalPaddingDp = buttonHorizontalPaddingDp;
            this.buttonVerticalPaddingDp = buttonVerticalPaddingDp;
            this.navContainerPaddingDp = navContainerPaddingDp;
            this.tablePaddingDp = tablePaddingDp;
            this.commentHorizontalPaddingDp = commentHorizontalPaddingDp;
            this.commentVerticalPaddingDp = commentVerticalPaddingDp;
            this.navTextSp = navTextSp;
            this.titleTextSp = titleTextSp;
            this.summaryLabelTextSp = summaryLabelTextSp;
            this.summaryValueTextSp = summaryValueTextSp;
            this.headerTextSp = headerTextSp;
            this.punchTextSp = punchTextSp;
            this.commentTextSp = commentTextSp;
        }
    }

    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PunchWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            new PunchWidgetProvider().updateWidgetsAsync(context, ids, null);
        }
    }
}
