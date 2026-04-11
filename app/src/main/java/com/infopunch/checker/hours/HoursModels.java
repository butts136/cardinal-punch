package com.infopunch.checker.hours;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class HoursModels {
    public static class WeekData {
        public String title;
        public String currentRangeLabel;
        public String currentBankHours;
        public String currentWeekRegular;
        public String currentWeekOvertime;
        public String currentWeekTotal;
        public String lastWeekRegular;
        public String lastWeekOvertime;
        public String lastWeekTotal;
        public String previousWeekRelativeUrl;
        public String nextWeekRelativeUrl;
        public LocalDate weekStart;
        public LocalDate weekEnd;
        public List<DayEntry> days = new ArrayList<>();
    }

    public static class DayEntry {
        public LocalDate date;
        public String weekday;
        public String dayLabel;
        public final List<String> shifts = new ArrayList<>();
        public String regularHours = "00:00";
        public String overtimeHours = "00:00";

        public int getRegularMinutes() {
            return parseMinutes(regularHours);
        }

        public int getOvertimeMinutes() {
            return parseMinutes(overtimeHours);
        }

        public int getTotalMinutes() {
            return getRegularMinutes() + getOvertimeMinutes();
        }
    }

    public static class MonthTotals {
        public YearMonth month;
        public int regularMinutes;
        public int overtimeMinutes;

        public int getTotalMinutes() {
            return regularMinutes + overtimeMinutes;
        }
    }

    public static int parseMinutes(String value) {
        if (value == null) {
            return 0;
        }
        String normalized = value.trim().replace(" hrs", "");
        if (normalized.contains(".")) {
            try {
                double hours = Double.parseDouble(normalized);
                return (int) Math.round(hours * 60.0);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        String[] parts = normalized.split(":");
        if (parts.length != 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String formatMinutes(int totalMinutes) {
        int hours = totalMinutes / 60;
        int minutes = Math.abs(totalMinutes % 60);
        return String.format("%02d:%02d", hours, minutes);
    }
}
