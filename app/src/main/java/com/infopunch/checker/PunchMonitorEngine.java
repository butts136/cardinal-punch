package com.infopunch.checker;

import android.content.Context;

import com.infopunch.checker.hours.HoursModels;
import com.infopunch.checker.hours.HoursRepository;

import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class PunchMonitorEngine {
    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CANADA_FRENCH);
    private static final Duration MAX_PENDING_OUT = Duration.ofHours(16);

    public interface Callback {
        void onNotification(String message);
    }

    public static void checkAccounts(Context context, boolean backgroundMode, Callback callback) throws Exception {
        SessionManager sessionManager = new SessionManager(context);
        List<SessionManager.SessionData> accounts = sessionManager.getAccounts();
        for (SessionManager.SessionData account : accounts) {
            boolean enabled = backgroundMode
                    ? sessionManager.isBackgroundMonitorEnabled(account.accountId)
                    : sessionManager.isRealtimeMonitorEnabled(account.accountId);
            if (!enabled) {
                continue;
            }
            inspectAccount(context, sessionManager, account, callback);
        }
    }

    private static void inspectAccount(
            Context context,
            SessionManager sessionManager,
            SessionManager.SessionData account,
            Callback callback
    ) throws Exception {
        InfoPunchClient client = new InfoPunchClient();
        InfoPunchClient.LoginResult result = client.loginAndFetchUser(account.apiUrl, account.companyCode, account.nip);
        JSONObject lastPunch = result.user.optJSONObject("LastPunch");
        String signature = buildPunchSignature(lastPunch);
        String checkTime = lastPunch != null ? lastPunch.optString("CheckTime", "") : "";

        SessionManager.SessionData refreshed = sessionManager.findAccount(account.accountId);
        if (refreshed == null) {
            return;
        }

        if (!signature.isEmpty() && !signature.equals(refreshed.lastPunchSignature)) {
            boolean hadPrevious = refreshed.lastPunchSignature != null && !refreshed.lastPunchSignature.isEmpty();
            boolean isEntry = inferEntryPunch(refreshed, checkTime);
            sessionManager.setLastPunchSignature(account.accountId, signature);
            sessionManager.setLastPunchTime(account.accountId, checkTime);
            if (isEntry) {
                sessionManager.setPendingOutSince(account.accountId, parseEpoch(checkTime));
                sessionManager.setMissingPunchAlerted(account.accountId, false);
            } else {
                sessionManager.setPendingOutSince(account.accountId, 0L);
                sessionManager.setMissingPunchAlerted(account.accountId, false);
            }

            if (hadPrevious
                    && sessionManager.areNotificationsEnabled(account.accountId)
                    && sessionManager.markPunchNotificationSentIfNeeded(account.accountId, signature)) {
                String typeLabel = isEntry ? "Entree" : "Sortie";
                String message = typeLabel + " detectee a " + formatDisplayTime(checkTime);
                NotificationHelper.showNewPunchNotification(
                        context,
                        account.accountId,
                        displayAccountName(account),
                        message,
                        sessionManager.getNotificationRingtone(account.accountId)
                );
                if (callback != null) {
                    callback.onNotification(message);
                }
            }
            PunchWidgetProvider.refreshAll(context);
        } else if (!signature.isEmpty() && (refreshed.lastPunchTime == null || refreshed.lastPunchTime.isEmpty())) {
            sessionManager.setLastPunchTime(account.accountId, checkTime);
            PunchWidgetProvider.refreshAll(context);
        }

        long pendingSince = sessionManager.getPendingOutSince(account.accountId);
        String missingAlertKey = buildMissingPunchAlertKey(refreshed.lastPunchSignature, pendingSince);
        if (pendingSince > 0
                && System.currentTimeMillis() - pendingSince >= MAX_PENDING_OUT.toMillis()
                && sessionManager.areNotificationsEnabled(account.accountId)
                && sessionManager.markMissingPunchNotificationSentIfNeeded(account.accountId, missingAlertKey)) {
            String message = "Aucune sortie detectee depuis " + formatDisplayTime(refreshed.lastPunchTime);
            NotificationHelper.showMissingPunchNotification(
                    context,
                    account.accountId,
                    displayAccountName(account),
                    message,
                    sessionManager.getNotificationRingtone(account.accountId)
            );
            sessionManager.setMissingPunchAlerted(account.accountId, true);
            if (callback != null) {
                callback.onNotification(message);
            }
            PunchWidgetProvider.refreshAll(context);
        }
    }

    private static boolean inferEntryPunch(SessionManager.SessionData account, String checkTime) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(checkTime, API_DATE_TIME);
            HoursRepository repository = new HoursRepository();
            HoursModels.WeekData weekData = repository.loadCurrentWeek(account);
            HoursModels.DayEntry targetDay = findDay(weekData.days, dateTime.toLocalDate());
            if (targetDay == null) {
                return account.pendingOutSinceEpochMs == 0L;
            }

            String punchTime = dateTime.toLocalTime().toString();
            String latestSeen = "";
            boolean latestIsExit = false;

            for (String shift : targetDay.shifts) {
                String[] parts = shift.split("-");
                String start = parts.length > 0 ? normalizeTime(parts[0]) : "";
                String end = parts.length > 1 ? normalizeTime(parts[1]) : "";

                if (!start.isEmpty()) {
                    latestSeen = start;
                    latestIsExit = false;
                    if (punchTime.startsWith(start)) {
                        if (end.isEmpty()) {
                            return true;
                        }
                    }
                }

                if (!end.isEmpty()) {
                    latestSeen = end;
                    latestIsExit = true;
                    if (punchTime.startsWith(end)) {
                        return false;
                    }
                }
            }

            if (!latestSeen.isEmpty()) {
                return !latestIsExit;
            }
        } catch (Exception ignored) {
        }
        return account.pendingOutSinceEpochMs == 0L;
    }

    private static HoursModels.DayEntry findDay(List<HoursModels.DayEntry> days, LocalDate target) {
        for (HoursModels.DayEntry day : days) {
            if (day.date != null && day.date.equals(target)) {
                return day;
            }
        }
        return null;
    }

    private static long parseEpoch(String checkTime) {
        try {
            return LocalDateTime.parse(checkTime, API_DATE_TIME)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String normalizeTime(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 5) {
            return normalized.substring(0, 5);
        }
        return normalized;
    }

    private static String formatDisplayTime(String checkTime) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(checkTime, API_DATE_TIME);
            return dateTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.CANADA_FRENCH));
        } catch (Exception ignored) {
            return checkTime == null ? "" : checkTime;
        }
    }

    private static String displayAccountName(SessionManager.SessionData account) {
        if (account.fullName != null && !account.fullName.trim().isEmpty()) {
            return account.fullName;
        }
        return account.companyCode + " / " + account.nip;
    }

    private static String buildMissingPunchAlertKey(String punchSignature, long pendingSince) {
        if (punchSignature == null || punchSignature.trim().isEmpty() || pendingSince <= 0) {
            return "";
        }
        return punchSignature + "|missing|" + pendingSince;
    }

    private static String buildPunchSignature(JSONObject lastPunch) {
        if (lastPunch == null) {
            return "";
        }
        return lastPunch.optString("CheckTime", "")
                + "|"
                + lastPunch.optString("CheckType", "")
                + "|"
                + lastPunch.optString("Note", "");
    }
}
