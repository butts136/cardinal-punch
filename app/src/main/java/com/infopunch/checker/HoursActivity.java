package com.infopunch.checker;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.infopunch.checker.hours.HoursModels;
import com.infopunch.checker.hours.HoursRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HoursActivity extends AppCompatActivity {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final HoursRepository hoursRepository = new HoursRepository();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy", Locale.CANADA_FRENCH);

    private SessionManager sessionManager;
    private SessionManager.SessionData session;
    private ProgressBar progressBar;
    private TextView rangeView;
    private TextView bankView;
    private TextView currentWeekView;
    private TextView lastWeekView;
    private TextView monthView;
    private LinearLayout daysContainer;
    private Button previousWeekButton;
    private Button nextWeekButton;

    private HoursModels.WeekData currentWeek;
    private PunchRealtimeMonitor realtimeMonitor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hours);
        ThemeManager.apply(this);

        progressBar = findViewById(R.id.progressBar);
        rangeView = findViewById(R.id.rangeView);
        bankView = findViewById(R.id.bankView);
        currentWeekView = findViewById(R.id.currentWeekView);
        lastWeekView = findViewById(R.id.lastWeekView);
        monthView = findViewById(R.id.monthView);
        daysContainer = findViewById(R.id.daysContainer);
        previousWeekButton = findViewById(R.id.previousWeekButton);
        nextWeekButton = findViewById(R.id.nextWeekButton);

        try {
            sessionManager = new SessionManager(this);
            session = sessionManager.getSession();
        } catch (Exception exception) {
            showMessage("Impossible d'ouvrir la session.");
            finish();
            return;
        }

        if (session == null) {
            showMessage("Aucune session active.");
            finish();
            return;
        }

        realtimeMonitor = new PunchRealtimeMonitor(this, this::showMessage);

        previousWeekButton.setOnClickListener(v -> loadRelativeWeek(currentWeek != null ? currentWeek.previousWeekRelativeUrl : ""));
        nextWeekButton.setOnClickListener(v -> loadRelativeWeek(currentWeek != null ? currentWeek.nextWeekRelativeUrl : ""));

        loadInitialWeek();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (realtimeMonitor != null) {
            realtimeMonitor.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.apply(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (realtimeMonitor != null) {
            realtimeMonitor.stop();
            realtimeMonitor = new PunchRealtimeMonitor(this, this::showMessage);
        }
    }

    private void loadInitialWeek() {
        setLoading(true);
        executorService.execute(() -> {
            try {
                HoursModels.WeekData weekData = hoursRepository.loadCurrentWeek(session);
                HoursModels.WeekData previousWeek = loadPreviousWeekData(weekData);
                HoursModels.MonthTotals monthTotals = hoursRepository.computeMonthTotals(session, weekData);
                runOnUiThread(() -> {
                    setLoading(false);
                    currentWeek = weekData;
                    bindWeek(weekData, previousWeek, monthTotals);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage(exception.getMessage() != null ? exception.getMessage() : "Chargement impossible.");
                });
            }
        });
    }

    private void loadRelativeWeek(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.trim().isEmpty()) {
            return;
        }
        setLoading(true);
        executorService.execute(() -> {
            try {
                HoursModels.WeekData weekData = hoursRepository.loadRelativeWeek(session, relativeUrl);
                HoursModels.WeekData previousWeek = loadPreviousWeekData(weekData);
                HoursModels.MonthTotals monthTotals = hoursRepository.computeMonthTotals(session, weekData);
                runOnUiThread(() -> {
                    setLoading(false);
                    currentWeek = weekData;
                    bindWeek(weekData, previousWeek, monthTotals);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage(exception.getMessage() != null ? exception.getMessage() : "Navigation impossible.");
                });
            }
        });
    }

    private HoursModels.WeekData loadPreviousWeekData(HoursModels.WeekData weekData) throws Exception {
        if (weekData == null || weekData.previousWeekRelativeUrl == null || weekData.previousWeekRelativeUrl.trim().isEmpty()) {
            return null;
        }
        return hoursRepository.loadRelativeWeek(session, weekData.previousWeekRelativeUrl);
    }

    private void bindWeek(HoursModels.WeekData weekData, HoursModels.WeekData previousWeek, HoursModels.MonthTotals monthTotals) {
        rangeView.setText(weekData.currentRangeLabel);
        bankView.setText(getString(R.string.hours_bank_balance_prefix) + " " + safe(weekData.currentBankHours));
        currentWeekView.setText(
                getString(R.string.hours_current_week_prefix)
                        + "\nRegulier: " + safe(weekData.currentWeekRegular)
                        + "\nSurtemps: " + safe(weekData.currentWeekOvertime)
                        + "\nTotal: " + safe(weekData.currentWeekTotal)
        );
        lastWeekView.setText(
                getString(R.string.hours_last_week_prefix)
                        + "\nRegulier: " + safe(previousWeek != null ? previousWeek.currentWeekRegular : weekData.lastWeekRegular)
                        + "\nSurtemps: " + safe(previousWeek != null ? previousWeek.currentWeekOvertime : weekData.lastWeekOvertime)
                        + "\nTotal: " + safe(previousWeek != null ? previousWeek.currentWeekTotal : weekData.lastWeekTotal)
        );
        monthView.setText(
                getString(R.string.hours_month_prefix)
                        + "\nRegulier: " + HoursModels.formatMinutes(monthTotals.regularMinutes)
                        + "\nSurtemps: " + HoursModels.formatMinutes(monthTotals.overtimeMinutes)
                        + "\nTotal: " + HoursModels.formatMinutes(monthTotals.getTotalMinutes())
        );

        previousWeekButton.setEnabled(weekData.previousWeekRelativeUrl != null && !weekData.previousWeekRelativeUrl.isEmpty());
        nextWeekButton.setEnabled(weekData.nextWeekRelativeUrl != null && !weekData.nextWeekRelativeUrl.isEmpty());

        daysContainer.removeAllViews();
        for (HoursModels.DayEntry day : weekData.days) {
            daysContainer.addView(createDayView(day));
        }
    }

    private View createDayView(HoursModels.DayEntry day) {
        ThemeManager.Palette palette = ThemeManager.palette(this);
        LinearLayout card = new LinearLayout(this);
        card.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(20);
        card.setLayoutParams(cardParams);
        card.setBackground(rounded(palette.surface, palette.border, 28, 1));

        TextView title = new TextView(this);
        title.setText(day.date != null ? capitalize(day.date.format(dateFormatter)) : day.weekday + " " + day.dayLabel);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(palette.text);
        card.addView(title);

        card.addView(createSummary(day, palette));

        if (day.shifts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
            empty.setText(getString(R.string.hours_no_punches_day));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            empty.setBackground(rounded(palette.surfaceSecondary, palette.border, 20, 1));
            card.addView(empty);
        } else {
            card.addView(createPunchTable(day.shifts, palette));
        }

        Button noteButton = new Button(this);
        noteButton.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        noteButton.setText(R.string.hours_note_button);
        noteButton.setAllCaps(false);
        noteButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        noteButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        noteButton.setTextColor(Color.WHITE);
        noteButton.setBackground(rounded(palette.accent, palette.accent, 20, 0));
        noteButton.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_edit, 0, 0, 0);
        noteButton.setCompoundDrawablePadding(dp(10));
        noteButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        noteButton.setOnClickListener(v -> openNoteDialog(day.date));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        noteParams.topMargin = dp(16);
        noteButton.setLayoutParams(noteParams);
        card.addView(noteButton);

        return card;
    }

    private View createSummary(HoursModels.DayEntry day, ThemeManager.Palette palette) {
        LinearLayout summary = new LinearLayout(this);
        summary.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(14), 0, dp(14));
        summary.setLayoutParams(params);
        summary.addView(createSummaryCard("Temps regulier :", safe(day.regularHours), palette, 0));
        summary.addView(createSummaryCard("Surtemps :", safe(day.overtimeHours), palette, 1));
        summary.addView(createSummaryCard("Total :", HoursModels.formatMinutes(day.getTotalMinutes()), palette, 2));
        return summary;
    }

    private View createSummaryCard(String label, String value, ThemeManager.Palette palette, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(6), dp(10), dp(6), dp(10));
        card.setBackground(rounded(palette.surfaceSecondary, palette.border, 16, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (index > 0) {
            params.leftMargin = dp(8);
        }
        card.setLayoutParams(params);

        TextView labelView = new TextView(this);
        labelView.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        labelView.setText(label);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextColor(palette.textSecondary);
        labelView.setTextSize(11f);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        valueView.setText(value);
        valueView.setGravity(Gravity.CENTER);
        valueView.setTextColor(palette.text);
        valueView.setTextSize(17f);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(valueView);
        return card;
    }

    private View createPunchTable(List<String> shifts, ThemeManager.Palette palette) {
        LinearLayout table = new LinearLayout(this);
        table.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackground(rounded(palette.surfaceSecondary, palette.border, 22, 1));
        table.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout header = createPunchRow("Entrant", "Sortant", true, palette);
        table.addView(header);
        for (PunchDisplay.Pair pair : PunchDisplay.parsePairs(shifts)) {
            table.addView(createPunchRow(pair.entry, pair.exit, false, palette));
        }
        return table;
    }

    private LinearLayout createPunchRow(String entry, String exit, boolean header, ThemeManager.Palette palette) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, header ? 0 : dp(8), 0, header ? dp(8) : dp(4));
        row.addView(createPunchCell(entry, header, true, palette));
        row.addView(createPunchCell(exit, header, false, palette));
        return row;
    }

    private TextView createPunchCell(String text, boolean header, boolean entryCell, ThemeManager.Palette palette) {
        TextView cell = new TextView(this);
        cell.setTag(ThemeManager.TAG_KEEP_CUSTOM_THEME);
        cell.setText(text);
        cell.setGravity(Gravity.CENTER);
        cell.setMinHeight(header ? dp(42) : dp(58));
        cell.setTextSize(header ? 15f : 22f);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (header) {
            cell.setTextColor(Color.WHITE);
            cell.setBackground(rounded(entryCell ? 0xFF16A34A : 0xFFDC2626, entryCell ? 0xFF22C55E : 0xFFF97316, 18, 0));
        } else if ("--:--".equals(text)) {
            cell.setTextColor(palette.textSecondary);
            cell.setBackground(rounded(palette.surface, palette.border, 18, 1));
        } else {
            cell.setTextColor(palette.text);
            cell.setBackground(rounded(palette.surface, palette.border, 18, 1));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (!entryCell) {
            params.leftMargin = dp(10);
        }
        cell.setLayoutParams(params);
        return cell;
    }

    private void openNoteDialog(LocalDate date) {
        if (date == null) {
            showMessage("Date invalide.");
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setHint(R.string.hours_note_hint);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.hours_note_dialog_title) + " " + date)
                .setView(input)
                .setPositiveButton(R.string.hours_note_send, (dialog, which) -> sendNote(date, input.getText().toString().trim()))
                .setNeutralButton(R.string.hours_note_send_tests, (dialog, which) -> sendNoteTests(date, input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendNoteTests(LocalDate date, String note) {
        if (note.isEmpty()) {
            showMessage("La note est vide.");
            return;
        }
        setLoading(true);
        executorService.execute(() -> {
            try {
                List<String> responses = hoursRepository.sendNoteTestVariants(session, date, note);
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage("Tests envoyes: " + responses.size());
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage(exception.getMessage() != null ? exception.getMessage() : "Tests impossibles.");
                });
            }
        });
    }

    private void sendNote(LocalDate date, String note) {
        if (note.isEmpty()) {
            showMessage("La note est vide.");
            return;
        }
        setLoading(true);
        executorService.execute(() -> {
            try {
                String response = hoursRepository.sendNote(session, date, note);
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage(response.isEmpty() ? "Note envoyee." : response);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showMessage(exception.getMessage() != null ? exception.getMessage() : "Envoi impossible.");
                });
            }
        });
    }

    private void setLoading(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        previousWeekButton.setEnabled(!isLoading);
        nextWeekButton.setEnabled(!isLoading);
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private GradientDrawable rounded(int color, int borderColor, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), borderColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.CANADA_FRENCH) + value.substring(1);
    }

    private void showMessage(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (realtimeMonitor != null) {
            realtimeMonitor.stop();
        }
        executorService.shutdownNow();
    }
}
