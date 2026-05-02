package com.infopunch.checker;

import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
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
import java.util.ArrayList;
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
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(26, 22, 22, 22);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = 20;
        card.setLayoutParams(cardParams);
        card.setBackgroundResource(R.drawable.bg_day_card);

        TextView title = new TextView(this);
        title.setText(day.date != null ? capitalize(day.date.format(dateFormatter)) : day.weekday + " " + day.dayLabel);
        title.setTextSize(19f);
        title.setTextColor(getColor(R.color.text_primary));
        card.addView(title);

        TextView totals = new TextView(this);
        totals.setText(
                "Regulier: " + safe(day.regularHours)
                        + "\nSurtemps: " + safe(day.overtimeHours)
                        + "\nTotal: " + HoursModels.formatMinutes(day.getTotalMinutes())
        );
        totals.setTextColor(getColor(R.color.text_secondary));
        totals.setTextSize(13f);
        totals.setPadding(0, 10, 0, 16);
        card.addView(totals);

        if (day.shifts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.hours_no_punches_day));
            empty.setTextColor(getColor(R.color.text_secondary));
            card.addView(empty);
        } else {
            card.addView(createPunchTable(day.shifts));
        }

        Button noteButton = new Button(this);
        noteButton.setText(R.string.hours_note_button);
        noteButton.setAllCaps(false);
        noteButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        noteButton.setTextColor(getColor(R.color.text_secondary));
        noteButton.setBackgroundResource(R.drawable.bg_button_secondary);
        noteButton.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_edit, 0, 0, 0);
        noteButton.setCompoundDrawablePadding(12);
        noteButton.setOnClickListener(v -> openNoteDialog(day.date));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        noteParams.topMargin = 8;
        noteButton.setLayoutParams(noteParams);
        card.addView(noteButton);

        return card;
    }

    private View createPunchTable(List<String> shifts) {
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackgroundResource(R.drawable.bg_card_secondary);
        table.setPadding(18, 14, 18, 14);

        LinearLayout header = createPunchRow(getString(R.string.hours_entry), getString(R.string.hours_exit), true);
        table.addView(header);
        for (PunchDisplay.Pair pair : PunchDisplay.parsePairs(shifts)) {
            table.addView(createPunchRow(pair.entry, pair.exit, false));
        }
        return table;
    }

    private LinearLayout createPunchRow(String entry, String exit, boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, header ? 0 : 8, 0, header ? 8 : 6);
        row.addView(createPunchCell(entry, header));
        row.addView(createPunchCell(exit, header));
        return row;
    }

    private TextView createPunchCell(String text, boolean header) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(header ? 13f : 18f);
        cell.setTextColor(getColor(header ? R.color.text_secondary : R.color.text_primary));
        if (header) {
            cell.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
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
