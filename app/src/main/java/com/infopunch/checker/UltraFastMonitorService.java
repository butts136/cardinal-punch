package com.infopunch.checker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UltraFastMonitorService extends Service {
    private static final String CHANNEL_ID = "ultra_fast_monitor";
    private static final int NOTIFICATION_ID = 41014;
    private static final String ACTION_START = "com.infopunch.checker.START_ULTRA_FAST";
    private static final String ACTION_STOP = "com.infopunch.checker.STOP_ULTRA_FAST";
    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CANADA_FRENCH);

    private final Map<String, Future<?>> accountWatchers = new ConcurrentHashMap<>();
    private ScheduledExecutorService maintenanceScheduler;
    private ExecutorService watchExecutor;
    private volatile boolean serviceRunning = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, UltraFastMonitorService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, UltraFastMonitorService.class);
        intent.setAction(ACTION_STOP);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        watchExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        if (!serviceRunning) {
            serviceRunning = true;
            syncWatchers();
            maintenanceScheduler.scheduleWithFixedDelay(this::syncWatchers, 10, 10, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void syncWatchers() {
        try {
            SessionManager sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.isUltraFastEnabled()) {
                stopSelf();
                return;
            }
            String bridgeUrl = sessionManager.getBridgeUrl();
            String bridgeToken = sessionManager.getBridgeToken();
            if (bridgeUrl.isEmpty() || bridgeToken.isEmpty()) {
                return;
            }

            Set<String> desiredAccounts = new HashSet<>();
            for (SessionManager.SessionData account : sessionManager.getAccounts()) {
                desiredAccounts.add(account.accountId);
                Future<?> existing = accountWatchers.get(account.accountId);
                if (existing == null || existing.isDone() || existing.isCancelled()) {
                    accountWatchers.put(account.accountId, watchExecutor.submit(() -> watchAccount(account.accountId)));
                }
            }

            for (Map.Entry<String, Future<?>> entry : new HashSet<>(accountWatchers.entrySet())) {
                if (!desiredAccounts.contains(entry.getKey())) {
                    entry.getValue().cancel(true);
                    accountWatchers.remove(entry.getKey());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void watchAccount(String accountId) {
        BridgeClient bridgeClient = new BridgeClient();
        try {
            while (serviceRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    SessionManager sessionManager = new SessionManager(getApplicationContext());
                    if (!sessionManager.isUltraFastEnabled()) {
                        return;
                    }

                    SessionManager.SessionData account = sessionManager.findAccount(accountId);
                    if (account == null) {
                        return;
                    }

                    String bridgeUrl = sessionManager.getBridgeUrl();
                    String bridgeToken = sessionManager.getBridgeToken();
                    if (bridgeUrl.isEmpty() || bridgeToken.isEmpty()) {
                        sleepQuietly(2000);
                        continue;
                    }

                    BridgeClient.BridgeState state = bridgeClient.waitForStateChange(
                            bridgeUrl,
                            bridgeToken,
                            accountId,
                            account.lastPunchSignature,
                            account.missingPunchAlerted,
                            45
                    );
                    handleState(sessionManager, accountId, state);
                } catch (BridgeClient.BridgeException exception) {
                    if (exception.statusCode == 404) {
                        registerAccountIfNeeded(bridgeClient, accountId);
                    } else {
                        if (!sleepQuietly(3000)) {
                            return;
                        }
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    if (!sleepQuietly(3000)) {
                        return;
                    }
                }
            }
        } finally {
            accountWatchers.remove(accountId);
        }
    }

    private void registerAccountIfNeeded(BridgeClient bridgeClient, String accountId) {
        try {
            SessionManager sessionManager = new SessionManager(getApplicationContext());
            SessionManager.SessionData account = sessionManager.findAccount(accountId);
            if (account == null) {
                return;
            }
            String bridgeUrl = sessionManager.getBridgeUrl();
            String bridgeToken = sessionManager.getBridgeToken();
            if (bridgeUrl.isEmpty() || bridgeToken.isEmpty()) {
                return;
            }
            bridgeClient.registerAccount(bridgeUrl, bridgeToken, account);
        } catch (Exception ignored) {
        }
    }

    private void handleState(SessionManager sessionManager, String accountId, BridgeClient.BridgeState state) {
        if (state == null || state.lastPunchSignature == null || state.lastPunchSignature.isEmpty()) {
            return;
        }

        SessionManager.SessionData refreshed = sessionManager.findAccount(accountId);
        if (refreshed == null) {
            return;
        }

        if (!state.lastPunchSignature.equals(refreshed.lastPunchSignature)) {
            boolean hadPrevious = refreshed.lastPunchSignature != null && !refreshed.lastPunchSignature.isEmpty();
            sessionManager.setLastPunchSignature(accountId, state.lastPunchSignature);
            sessionManager.setLastPunchTime(accountId, state.lastPunchTime);
            sessionManager.setPendingOutSince(accountId, state.pendingOutSinceEpochMs);
            sessionManager.setMissingPunchAlerted(accountId, false);

            if (hadPrevious
                    && sessionManager.areNotificationsEnabled(accountId)
                    && sessionManager.markPunchNotificationSentIfNeeded(accountId, state.lastPunchSignature)) {
                String kind = "entry".equalsIgnoreCase(state.lastPunchKind) ? "Entree"
                        : "exit".equalsIgnoreCase(state.lastPunchKind) ? "Sortie"
                        : "Nouveau poincon";
                String message = kind + " detectee a " + formatDisplayTime(state.lastPunchTime);
                NotificationHelper.showNewPunchNotification(
                        getApplicationContext(),
                        accountId,
                        displayAccountName(refreshed),
                        message,
                        sessionManager.getNotificationRingtone(accountId),
                        sessionManager.isNotificationSoundEnabled(accountId)
                );
            }
            PunchWidgetProvider.refreshAll(getApplicationContext());
        } else if (!state.lastPunchTime.isEmpty()
                && (refreshed.lastPunchTime == null || refreshed.lastPunchTime.isEmpty())) {
            sessionManager.setLastPunchTime(accountId, state.lastPunchTime);
            PunchWidgetProvider.refreshAll(getApplicationContext());
        }

        String missingAlertKey = buildMissingPunchAlertKey(state.lastPunchSignature, state.pendingOutSinceEpochMs);
        if (state.missingPunch
                && sessionManager.areNotificationsEnabled(accountId)
                && sessionManager.markMissingPunchNotificationSentIfNeeded(accountId, missingAlertKey)) {
            NotificationHelper.showMissingPunchNotification(
                    getApplicationContext(),
                    accountId,
                    displayAccountName(refreshed),
                    "Un poincon de sortie semble manquer pour ce compte.",
                    sessionManager.getNotificationRingtone(accountId),
                    sessionManager.isNotificationSoundEnabled(accountId)
            );
            sessionManager.setMissingPunchAlerted(accountId, true);
            PunchWidgetProvider.refreshAll(getApplicationContext());
        } else if (!state.missingPunch) {
            sessionManager.setMissingPunchAlerted(accountId, false);
        }
    }

    private boolean sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String buildMissingPunchAlertKey(String punchSignature, long pendingSinceEpochMs) {
        if (punchSignature == null || punchSignature.trim().isEmpty() || pendingSinceEpochMs <= 0) {
            return "";
        }
        return punchSignature + "|missing|" + pendingSinceEpochMs;
    }

    private String formatDisplayTime(String checkTime) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(checkTime, API_DATE_TIME);
            return dateTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.CANADA_FRENCH));
        } catch (Exception ignored) {
            return checkTime == null ? "" : checkTime;
        }
    }

    private String displayAccountName(SessionManager.SessionData account) {
        if (account.fullName != null && !account.fullName.trim().isEmpty()) {
            return account.fullName;
        }
        return account.companyCode + " / " + account.nip;
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Cardinal Punch")
                .setContentText("Surveillance ultra-rapide active")
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Surveillance ultra-rapide",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        for (Future<?> watcher : accountWatchers.values()) {
            watcher.cancel(true);
        }
        accountWatchers.clear();
        if (maintenanceScheduler != null) {
            maintenanceScheduler.shutdownNow();
        }
        if (watchExecutor != null) {
            watchExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
