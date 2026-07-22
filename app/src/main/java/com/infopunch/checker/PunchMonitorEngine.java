package com.infopunch.checker;

import android.content.Context;

import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class PunchMonitorEngine {
    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CANADA_FRENCH);
    private static final Duration MAX_PENDING_OUT = Duration.ofHours(16);
    private static final InfoPunchClient CLIENT = new InfoPunchClient();

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

    public static void checkAccount(Context context, String accountId, Callback callback) throws Exception {
        SessionManager sessionManager = new SessionManager(context);
        SessionManager.SessionData account = sessionManager.findAccount(accountId);
        if (account != null) {
            inspectAccount(context, sessionManager, account, callback);
        }
    }

    private static void inspectAccount(
            Context context,
            SessionManager sessionManager,
            SessionManager.SessionData account,
            Callback callback
    ) throws Exception {
        JSONObject user = CLIENT.fetchUserFast(account.apiUrl, account.companyCode, account.nip);
        JSONObject lastPunch = user.optJSONObject("LastPunch");
        String signature = buildPunchSignature(lastPunch);
        String checkTime = lastPunch != null ? lastPunch.optString("CheckTime", "") : "";

        SessionManager.SessionData refreshed = sessionManager.findAccount(account.accountId);
        if (refreshed == null) {
            return;
        }

        if (!signature.isEmpty() && !signature.equals(refreshed.lastPunchSignature)) {
            boolean hadPrevious = refreshed.lastPunchSignature != null && !refreshed.lastPunchSignature.isEmpty();
            boolean isEntry = inferEntryPunch(refreshed, lastPunch);
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
                        sessionManager.getNotificationRingtone(account.accountId),
                        sessionManager.isNotificationSoundEnabled(account.accountId)
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
                    sessionManager.getNotificationRingtone(account.accountId),
                    sessionManager.isNotificationSoundEnabled(account.accountId)
            );
            sessionManager.setMissingPunchAlerted(account.accountId, true);
            if (callback != null) {
                callback.onNotification(message);
            }
            PunchWidgetProvider.refreshAll(context);
        }
    }

    private static boolean inferEntryPunch(SessionManager.SessionData account, JSONObject lastPunch) {
        String checkType = lastPunch != null ? lastPunch.optString("CheckType", "") : "";
        if ("1".equals(checkType) || "3".equals(checkType)) {
            return true;
        }
        if ("2".equals(checkType) || "4".equals(checkType)) {
            return false;
        }
        return account.pendingOutSinceEpochMs == 0L;
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
