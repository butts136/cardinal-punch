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
        applyResponsiveLayout(manager, widgetId, views);
        views.setOnClickPendingIntent(R.id.prevButton, buildActionIntent(context, widgetId, ACTION_PREVIOUS));
        views.setOnClickPendingIntent(R.id.nextButton, buildActionIntent(context, widgetId, ACTION_NEXT));
        views.setOnClickPendingIntent(R.id.refreshButton, buildActionIntent(context, widgetId, ACTION_REFRESH));

        Intent launchIntent = new Intent(context, MainActivity.class);
        PendingIntent launchPendingIntent = PendingIntent.getActivity(
                context,
                widgetId + 5000,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, launchPendingIntent);

        try {
            SessionManager sessionManager = new SessionManager(context);
            SessionManager.SessionData session = sessionManager.getSession();
            if (session == null) {
                views.setTextViewText(R.id.dayTitleView, "Aucun compte actif");
                views.setTextViewText(R.id.bankView, "");
                views.setTextViewText(R.id.shiftsView, "Connecte un compte pour afficher les poincons.");
                manager.updateAppWidget(widgetId, views);
                return;
            }

            HoursRepository repository = new HoursRepository();
            HoursModels.WeekData weekData = repository.loadWeekForDate(session, selectedDate);
            HoursModels.DayEntry dayEntry = findDay(weekData.days, selectedDate);
            views.setTextViewText(R.id.accountView, session.fullName);
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
            views.setTextViewText(R.id.bankView, "Banque : " + (weekData.currentBankHours == null ? "-" : weekData.currentBankHours));
            views.setTextViewText(R.id.shiftsView, buildShiftText(dayEntry));
            manager.updateAppWidget(widgetId, views);
        } catch (Exception exception) {
            views.setViewVisibility(R.id.updateBannerView, android.view.View.GONE);
            views.setTextViewText(R.id.dayTitleView, capitalize(selectedDate.format(TITLE_FORMAT)));
            views.setTextViewText(R.id.bankView, "Banque : -");
            views.setTextViewText(R.id.shiftsView, "Chargement impossible.");
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

    private String buildShiftText(HoursModels.DayEntry dayEntry) {
        if (dayEntry == null || dayEntry.shifts.isEmpty()) {
            return "Aucun poincon visible pour cette journee.";
        }
        StringBuilder builder = new StringBuilder();
        for (String shift : dayEntry.shifts) {
            String[] parts = shift.split("-");
            String entry = parts.length > 0 ? parts[0].trim() : "--:--";
            String exit = parts.length > 1 ? parts[1].trim() : "--:--";
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("Entree : ").append(entry).append("\n");
            builder.append("Sortie : ").append(exit);
        }
        return builder.toString();
    }

    private LocalDate getSelectedDate(Context context, int widgetId) {
        String stored = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
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
        views.setViewPadding(R.id.bankView, sizing.cardPaddingDp, sizing.cardPaddingDp, sizing.cardPaddingDp, sizing.cardPaddingDp);
        views.setViewPadding(R.id.shiftsView, sizing.cardPaddingDp, sizing.cardPaddingDp, sizing.cardPaddingDp, sizing.cardPaddingDp);
        views.setViewPadding(R.id.refreshButton, sizing.refreshHorizontalPaddingDp, sizing.refreshVerticalPaddingDp, sizing.refreshHorizontalPaddingDp, sizing.refreshVerticalPaddingDp);

        views.setTextViewTextSize(R.id.accountView, TypedValue.COMPLEX_UNIT_SP, sizing.accountTextSp);
        views.setTextViewTextSize(R.id.prevButton, TypedValue.COMPLEX_UNIT_SP, sizing.navTextSp);
        views.setTextViewTextSize(R.id.dayTitleView, TypedValue.COMPLEX_UNIT_SP, sizing.titleTextSp);
        views.setTextViewTextSize(R.id.nextButton, TypedValue.COMPLEX_UNIT_SP, sizing.navTextSp);
        views.setTextViewTextSize(R.id.bankView, TypedValue.COMPLEX_UNIT_SP, sizing.bankTextSp);
        views.setTextViewTextSize(R.id.shiftsView, TypedValue.COMPLEX_UNIT_SP, sizing.shiftsTextSp);
        views.setTextViewTextSize(R.id.refreshButton, TypedValue.COMPLEX_UNIT_SP, sizing.refreshTextSp);
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
            return new WidgetSizing(20, 14, 10, 16, 18, 12, 16f, 26f, 28f, 18f, 19f, 17f);
        }
        if (usableWidthDp >= 230 || usableHeightDp >= 200 || footprint >= 170) {
            return new WidgetSizing(16, 12, 8, 13, 14, 10, 13f, 21f, 22f, 15f, 16f, 14f);
        }
        return new WidgetSizing(12, 10, 6, 10, 12, 8, 11f, 17f, 18f, 13f, 13f, 12f);
    }

    private static class WidgetSizing {
        final int rootPaddingDp;
        final int buttonHorizontalPaddingDp;
        final int buttonVerticalPaddingDp;
        final int cardPaddingDp;
        final int refreshHorizontalPaddingDp;
        final int refreshVerticalPaddingDp;
        final float accountTextSp;
        final float navTextSp;
        final float titleTextSp;
        final float bankTextSp;
        final float shiftsTextSp;
        final float refreshTextSp;

        WidgetSizing(
                int rootPaddingDp,
                int buttonHorizontalPaddingDp,
                int buttonVerticalPaddingDp,
                int cardPaddingDp,
                int refreshHorizontalPaddingDp,
                int refreshVerticalPaddingDp,
                float accountTextSp,
                float navTextSp,
                float titleTextSp,
                float bankTextSp,
                float shiftsTextSp,
                float refreshTextSp
        ) {
            this.rootPaddingDp = rootPaddingDp;
            this.buttonHorizontalPaddingDp = buttonHorizontalPaddingDp;
            this.buttonVerticalPaddingDp = buttonVerticalPaddingDp;
            this.cardPaddingDp = cardPaddingDp;
            this.refreshHorizontalPaddingDp = refreshHorizontalPaddingDp;
            this.refreshVerticalPaddingDp = refreshVerticalPaddingDp;
            this.accountTextSp = accountTextSp;
            this.navTextSp = navTextSp;
            this.titleTextSp = titleTextSp;
            this.bankTextSp = bankTextSp;
            this.shiftsTextSp = shiftsTextSp;
            this.refreshTextSp = refreshTextSp;
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
