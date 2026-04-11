package com.infopunch.checker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SessionManager {
    private static final Object ACCOUNT_ACCESS_LOCK = new Object();
    private static final String PREFS = "info_punch_secure_prefs";
    private static final String KEY_ACCOUNTS_JSON = "accounts_json";
    private static final String KEY_ACTIVE_ACCOUNT_ID = "active_account_id";
    private static final String KEY_BRIDGE_URL = "bridge_url";
    private static final String KEY_BRIDGE_TOKEN = "bridge_token";
    private static final String KEY_ULTRA_FAST_ENABLED = "ultra_fast_enabled";
    private static final String KEY_DEVICE_ID = "device_id";

    // Legacy keys for one-time migration.
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_COMPANY = "company";
    private static final String KEY_NIP = "nip";
    private static final String KEY_PROTECTION = "protection_enabled";
    private static final String KEY_FULL_NAME = "full_name";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_SITE_PATH = "site_path";
    private static final String KEY_HOURS_LINK = "hours_link";
    private static final String KEY_LAST_PUNCH_SIGNATURE = "last_punch_signature";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_REALTIME_MONITOR_ENABLED = "realtime_monitor_enabled";
    private static final String KEY_BACKGROUND_MONITOR_ENABLED = "background_monitor_enabled";

    private final SharedPreferences preferences;

    public SessionManager(Context context) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        preferences = EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
        migrateLegacyIfNeeded();
    }

    public synchronized void saveSession(
            String apiUrl,
            String companyCode,
            String nip,
            boolean protectionEnabled,
            String fullName,
            String userId,
            String sitePath,
            String hoursLink
    ) {
        String accountId = buildAccountId(companyCode, nip);
        SessionData existing = findAccount(accountId);
        SessionData updated = new SessionData(
                accountId,
                apiUrl,
                companyCode,
                nip,
                protectionEnabled,
                fullName,
                userId,
                sitePath,
                hoursLink,
                existing != null ? existing.lastPunchSignature : "",
                existing != null ? existing.lastPunchTime : "",
                existing != null ? existing.notificationsEnabled : true,
                existing != null ? existing.realtimeMonitorEnabled : true,
                existing != null ? existing.backgroundMonitorEnabled : true,
                existing != null ? existing.notificationRingtone : "",
                existing != null ? existing.pendingOutSinceEpochMs : 0L,
                existing != null && existing.missingPunchAlerted,
                existing != null ? existing.lastNotifiedPunchSignature : "",
                existing != null ? existing.lastMissingPunchAlertKey : ""
        );
        upsertAccount(updated);
        setActiveAccount(accountId);
    }

    public synchronized List<SessionData> getAccounts() {
        return readAccounts();
    }

    public synchronized SessionData getSession() {
        List<SessionData> accounts = readAccounts();
        if (accounts.isEmpty()) {
            return null;
        }
        String activeAccountId = preferences.getString(KEY_ACTIVE_ACCOUNT_ID, "");
        if (!activeAccountId.isEmpty()) {
            for (SessionData session : accounts) {
                if (activeAccountId.equals(session.accountId)) {
                    return session;
                }
            }
        }
        SessionData first = accounts.get(0);
        preferences.edit().putString(KEY_ACTIVE_ACCOUNT_ID, first.accountId).apply();
        return first;
    }

    public synchronized SessionData findAccount(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return null;
        }
        for (SessionData session : readAccounts()) {
            if (accountId.equals(session.accountId)) {
                return session;
            }
        }
        return null;
    }

    public synchronized boolean hasSession() {
        return !readAccounts().isEmpty();
    }

    public synchronized void setActiveAccount(String accountId) {
        if (findAccount(accountId) == null) {
            return;
        }
        preferences.edit().putString(KEY_ACTIVE_ACCOUNT_ID, accountId).apply();
    }

    public synchronized String getActiveAccountId() {
        SessionData current = getSession();
        return current != null ? current.accountId : "";
    }

    public synchronized void removeCurrentSession() {
        SessionData current = getSession();
        if (current != null) {
            removeAccount(current.accountId);
        }
    }

    public synchronized void removeAccount(String accountId) {
        List<SessionData> accounts = readAccounts();
        List<SessionData> updated = new ArrayList<>();
        for (SessionData session : accounts) {
            if (!session.accountId.equals(accountId)) {
                updated.add(session);
            }
        }
        writeAccounts(updated);
        String active = preferences.getString(KEY_ACTIVE_ACCOUNT_ID, "");
        if (active.equals(accountId)) {
            preferences.edit().putString(KEY_ACTIVE_ACCOUNT_ID, updated.isEmpty() ? "" : updated.get(0).accountId).apply();
        }
    }

    public synchronized void clearSession() {
        preferences.edit().clear().apply();
    }

    public synchronized String getLastPunchSignature() {
        SessionData current = getSession();
        return current != null ? current.lastPunchSignature : "";
    }

    public synchronized String getLastPunchSignature(String accountId) {
        SessionData session = findAccount(accountId);
        return session != null ? session.lastPunchSignature : "";
    }

    public synchronized void setLastPunchSignature(String signature) {
        SessionData current = getSession();
        if (current != null) {
            setLastPunchSignature(current.accountId, signature);
        }
    }

    public synchronized void setLastPunchSignature(String accountId, String signature) {
        SessionData session = findAccount(accountId);
        if (session == null) {
            return;
        }
        upsertAccount(session.withLastPunch(signature, session.lastPunchTime));
    }

    public synchronized String getLastPunchTime(String accountId) {
        SessionData session = findAccount(accountId);
        return session != null ? session.lastPunchTime : "";
    }

    public synchronized void setLastPunchTime(String accountId, String lastPunchTime) {
        SessionData session = findAccount(accountId);
        if (session == null) {
            return;
        }
        upsertAccount(session.withLastPunch(session.lastPunchSignature, lastPunchTime));
    }

    public synchronized long getPendingOutSince(String accountId) {
        SessionData session = findAccount(accountId);
        return session != null ? session.pendingOutSinceEpochMs : 0L;
    }

    public synchronized void setPendingOutSince(String accountId, long epochMs) {
        SessionData session = findAccount(accountId);
        if (session == null) {
            return;
        }
        upsertAccount(session.withPendingOut(epochMs, epochMs > 0 && session.missingPunchAlerted));
    }

    public synchronized boolean isMissingPunchAlerted(String accountId) {
        SessionData session = findAccount(accountId);
        return session != null && session.missingPunchAlerted;
    }

    public synchronized void setMissingPunchAlerted(String accountId, boolean alerted) {
        SessionData session = findAccount(accountId);
        if (session == null) {
            return;
        }
        upsertAccount(session.withPendingOut(session.pendingOutSinceEpochMs, alerted));
    }

    public boolean markPunchNotificationSentIfNeeded(String accountId, String punchSignature) {
        if (punchSignature == null || punchSignature.trim().isEmpty()) {
            return false;
        }
        synchronized (ACCOUNT_ACCESS_LOCK) {
            SessionData session = findAccount(accountId);
            if (session == null || punchSignature.equals(session.lastNotifiedPunchSignature)) {
                return false;
            }
            upsertAccount(session.withNotificationMarkers(punchSignature, session.lastMissingPunchAlertKey));
            return true;
        }
    }

    public boolean markMissingPunchNotificationSentIfNeeded(String accountId, String alertKey) {
        if (alertKey == null || alertKey.trim().isEmpty()) {
            return false;
        }
        synchronized (ACCOUNT_ACCESS_LOCK) {
            SessionData session = findAccount(accountId);
            if (session == null || alertKey.equals(session.lastMissingPunchAlertKey)) {
                return false;
            }
            upsertAccount(session.withNotificationMarkers(session.lastNotifiedPunchSignature, alertKey));
            return true;
        }
    }

    public synchronized boolean isProtectionEnabled() {
        SessionData current = getSession();
        return current != null && current.protectionEnabled;
    }

    public synchronized void setProtectionEnabled(boolean enabled) {
        SessionData current = getSession();
        if (current != null) {
            upsertAccount(current.withProtection(enabled));
        }
    }

    public synchronized boolean isNotificationsEnabled() {
        SessionData current = getSession();
        return current == null || current.notificationsEnabled;
    }

    public synchronized boolean areNotificationsEnabled(String accountId) {
        SessionData session = findAccount(accountId);
        return session == null || session.notificationsEnabled;
    }

    public synchronized void setNotificationsEnabled(boolean enabled) {
        SessionData current = getSession();
        if (current != null) {
            upsertAccount(current.withNotifications(enabled));
        }
    }

    public synchronized boolean isRealtimeMonitorEnabled() {
        SessionData current = getSession();
        return current == null || current.realtimeMonitorEnabled;
    }

    public synchronized boolean hasAnyRealtimeMonitorEnabled() {
        for (SessionData account : readAccounts()) {
            if (account.realtimeMonitorEnabled) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isRealtimeMonitorEnabled(String accountId) {
        SessionData session = findAccount(accountId);
        return session == null || session.realtimeMonitorEnabled;
    }

    public synchronized void setRealtimeMonitorEnabled(boolean enabled) {
        SessionData current = getSession();
        if (current != null) {
            upsertAccount(current.withRealtimeMonitor(enabled));
        }
    }

    public synchronized boolean isBackgroundMonitorEnabled() {
        SessionData current = getSession();
        return current == null || current.backgroundMonitorEnabled;
    }

    public synchronized boolean hasAnyBackgroundMonitorEnabled() {
        for (SessionData account : readAccounts()) {
            if (account.backgroundMonitorEnabled) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isBackgroundMonitorEnabled(String accountId) {
        SessionData session = findAccount(accountId);
        return session == null || session.backgroundMonitorEnabled;
    }

    public synchronized void setBackgroundMonitorEnabled(boolean enabled) {
        SessionData current = getSession();
        if (current != null) {
            upsertAccount(current.withBackgroundMonitor(enabled));
        }
    }

    public synchronized String getNotificationRingtone() {
        SessionData current = getSession();
        return current != null ? current.notificationRingtone : "";
    }

    public synchronized String getNotificationRingtone(String accountId) {
        SessionData current = findAccount(accountId);
        return current != null ? current.notificationRingtone : "";
    }

    public synchronized void setNotificationRingtone(String ringtoneUri) {
        SessionData current = getSession();
        if (current != null) {
            upsertAccount(current.withNotificationRingtone(ringtoneUri));
        }
    }

    public synchronized String getBridgeUrl() {
        return preferences.getString(KEY_BRIDGE_URL, "");
    }

    public synchronized void setBridgeUrl(String bridgeUrl) {
        preferences.edit().putString(KEY_BRIDGE_URL, bridgeUrl == null ? "" : bridgeUrl.trim()).apply();
    }

    public synchronized String getBridgeToken() {
        return preferences.getString(KEY_BRIDGE_TOKEN, "");
    }

    public synchronized void setBridgeToken(String bridgeToken) {
        preferences.edit().putString(KEY_BRIDGE_TOKEN, bridgeToken == null ? "" : bridgeToken.trim()).apply();
    }

    public synchronized String getDeviceId() {
        String value = preferences.getString(KEY_DEVICE_ID, "");
        if (value == null || value.trim().isEmpty()) {
            value = "android-" + UUID.randomUUID();
            preferences.edit().putString(KEY_DEVICE_ID, value).apply();
        }
        return value;
    }

    public synchronized boolean isUltraFastEnabled() {
        return preferences.getBoolean(KEY_ULTRA_FAST_ENABLED, false);
    }

    public synchronized void setUltraFastEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ULTRA_FAST_ENABLED, enabled).apply();
    }

    private void migrateLegacyIfNeeded() {
        if (preferences.contains(KEY_ACCOUNTS_JSON)) {
            return;
        }
        String apiUrl = preferences.getString(KEY_API_URL, "");
        String companyCode = preferences.getString(KEY_COMPANY, "");
        String nip = preferences.getString(KEY_NIP, "");
        if (apiUrl.isEmpty() || companyCode.isEmpty() || nip.isEmpty()) {
            preferences.edit().putString(KEY_ACCOUNTS_JSON, "[]").apply();
            return;
        }

        SessionData migrated = new SessionData(
                buildAccountId(companyCode, nip),
                apiUrl,
                companyCode,
                nip,
                preferences.getBoolean(KEY_PROTECTION, false),
                preferences.getString(KEY_FULL_NAME, ""),
                preferences.getString(KEY_USER_ID, ""),
                preferences.getString(KEY_SITE_PATH, ""),
                preferences.getString(KEY_HOURS_LINK, ""),
                preferences.getString(KEY_LAST_PUNCH_SIGNATURE, ""),
                "",
                preferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
                preferences.getBoolean(KEY_REALTIME_MONITOR_ENABLED, true),
                preferences.getBoolean(KEY_BACKGROUND_MONITOR_ENABLED, true),
                "",
                0L,
                false,
                "",
                ""
        );
        List<SessionData> accounts = new ArrayList<>();
        accounts.add(migrated);
        writeAccounts(accounts);
        preferences.edit().putString(KEY_ACTIVE_ACCOUNT_ID, migrated.accountId).apply();
    }

    private List<SessionData> readAccounts() {
        List<SessionData> accounts = new ArrayList<>();
        String raw = preferences.getString(KEY_ACCOUNTS_JSON, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    accounts.add(SessionData.fromJson(item));
                }
            }
        } catch (JSONException ignored) {
        }
        return accounts;
    }

    private void writeAccounts(List<SessionData> accounts) {
        JSONArray array = new JSONArray();
        for (SessionData session : accounts) {
            array.put(session.toJson());
        }
        preferences.edit().putString(KEY_ACCOUNTS_JSON, array.toString()).apply();
    }

    private void upsertAccount(SessionData sessionData) {
        List<SessionData> accounts = readAccounts();
        List<SessionData> updated = new ArrayList<>();
        boolean replaced = false;
        for (SessionData account : accounts) {
            if (account.accountId.equals(sessionData.accountId)) {
                updated.add(sessionData);
                replaced = true;
            } else {
                updated.add(account);
            }
        }
        if (!replaced) {
            updated.add(sessionData);
        }
        writeAccounts(updated);
    }

    private String buildAccountId(String companyCode, String nip) {
        return String.format(Locale.CANADA_FRENCH, "%s:%s", companyCode, nip);
    }

    public static class SessionData {
        public final String accountId;
        public final String apiUrl;
        public final String companyCode;
        public final String nip;
        public final boolean protectionEnabled;
        public final String fullName;
        public final String userId;
        public final String sitePath;
        public final String hoursLink;
        public final String lastPunchSignature;
        public final String lastPunchTime;
        public final boolean notificationsEnabled;
        public final boolean realtimeMonitorEnabled;
        public final boolean backgroundMonitorEnabled;
        public final String notificationRingtone;
        public final long pendingOutSinceEpochMs;
        public final boolean missingPunchAlerted;
        public final String lastNotifiedPunchSignature;
        public final String lastMissingPunchAlertKey;

        public SessionData(
                String accountId,
                String apiUrl,
                String companyCode,
                String nip,
                boolean protectionEnabled,
                String fullName,
                String userId,
                String sitePath,
                String hoursLink,
                String lastPunchSignature,
                String lastPunchTime,
                boolean notificationsEnabled,
                boolean realtimeMonitorEnabled,
                boolean backgroundMonitorEnabled,
                String notificationRingtone,
                long pendingOutSinceEpochMs,
                boolean missingPunchAlerted,
                String lastNotifiedPunchSignature,
                String lastMissingPunchAlertKey
        ) {
            this.accountId = accountId;
            this.apiUrl = apiUrl;
            this.companyCode = companyCode;
            this.nip = nip;
            this.protectionEnabled = protectionEnabled;
            this.fullName = fullName;
            this.userId = userId;
            this.sitePath = sitePath;
            this.hoursLink = hoursLink;
            this.lastPunchSignature = lastPunchSignature;
            this.lastPunchTime = lastPunchTime;
            this.notificationsEnabled = notificationsEnabled;
            this.realtimeMonitorEnabled = realtimeMonitorEnabled;
            this.backgroundMonitorEnabled = backgroundMonitorEnabled;
            this.notificationRingtone = notificationRingtone;
            this.pendingOutSinceEpochMs = pendingOutSinceEpochMs;
            this.missingPunchAlerted = missingPunchAlerted;
            this.lastNotifiedPunchSignature = lastNotifiedPunchSignature == null ? "" : lastNotifiedPunchSignature;
            this.lastMissingPunchAlertKey = lastMissingPunchAlertKey == null ? "" : lastMissingPunchAlertKey;
        }

        public SessionData withLastPunch(String signature, String lastPunchTime) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    signature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withProtection(boolean enabled) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, enabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withNotifications(boolean enabled) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, enabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withRealtimeMonitor(boolean enabled) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, enabled, backgroundMonitorEnabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withBackgroundMonitor(boolean enabled) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, enabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withNotificationRingtone(String ringtoneUri) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    ringtoneUri == null ? "" : ringtoneUri, pendingOutSinceEpochMs, missingPunchAlerted,
                    lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withPendingOut(long epochMs, boolean alerted) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    notificationRingtone, epochMs, alerted, lastNotifiedPunchSignature, lastMissingPunchAlertKey
            );
        }

        public SessionData withNotificationMarkers(String notifiedPunchSignature, String missingPunchAlertKey) {
            return new SessionData(
                    accountId, apiUrl, companyCode, nip, protectionEnabled, fullName, userId, sitePath, hoursLink,
                    lastPunchSignature, lastPunchTime, notificationsEnabled, realtimeMonitorEnabled, backgroundMonitorEnabled,
                    notificationRingtone, pendingOutSinceEpochMs, missingPunchAlerted, notifiedPunchSignature, missingPunchAlertKey
            );
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("accountId", accountId);
                object.put("apiUrl", apiUrl);
                object.put("companyCode", companyCode);
                object.put("nip", nip);
                object.put("protectionEnabled", protectionEnabled);
                object.put("fullName", fullName);
                object.put("userId", userId);
                object.put("sitePath", sitePath);
                object.put("hoursLink", hoursLink);
                object.put("lastPunchSignature", lastPunchSignature);
                object.put("lastPunchTime", lastPunchTime);
                object.put("notificationsEnabled", notificationsEnabled);
                object.put("realtimeMonitorEnabled", realtimeMonitorEnabled);
                object.put("backgroundMonitorEnabled", backgroundMonitorEnabled);
                object.put("notificationRingtone", notificationRingtone);
                object.put("pendingOutSinceEpochMs", pendingOutSinceEpochMs);
                object.put("missingPunchAlerted", missingPunchAlerted);
                object.put("lastNotifiedPunchSignature", lastNotifiedPunchSignature);
                object.put("lastMissingPunchAlertKey", lastMissingPunchAlertKey);
            } catch (JSONException ignored) {
            }
            return object;
        }

        public static SessionData fromJson(JSONObject object) {
            return new SessionData(
                    object.optString("accountId"),
                    object.optString("apiUrl"),
                    object.optString("companyCode"),
                    object.optString("nip"),
                    object.optBoolean("protectionEnabled", false),
                    object.optString("fullName"),
                    object.optString("userId"),
                    object.optString("sitePath"),
                    object.optString("hoursLink"),
                    object.optString("lastPunchSignature"),
                    object.optString("lastPunchTime"),
                    object.optBoolean("notificationsEnabled", true),
                    object.optBoolean("realtimeMonitorEnabled", true),
                    object.optBoolean("backgroundMonitorEnabled", true),
                    object.optString("notificationRingtone"),
                    object.optLong("pendingOutSinceEpochMs", 0L),
                    object.optBoolean("missingPunchAlerted", false),
                    object.optString("lastNotifiedPunchSignature"),
                    object.optString("lastMissingPunchAlertKey")
            );
        }
    }
}
