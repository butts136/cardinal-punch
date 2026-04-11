package com.infopunch.checker;

import android.content.Context;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class PunchRealtimeMonitor {
    public interface Callback {
        void onNewPunch(String message);
    }

    private final Context appContext;
    private final Callback callback;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean started = false;

    public PunchRealtimeMonitor(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        scheduler.scheduleAtFixedRate(this::checkNow, 0, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        started = false;
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    private void checkNow() {
        worker.execute(() -> {
            try {
                SessionManager sessionManager = new SessionManager(appContext);
                if (!sessionManager.hasSession()) {
                    return;
                }
                if (!sessionManager.hasAnyRealtimeMonitorEnabled()) {
                    return;
                }
                NotificationHelper.ensureChannel(appContext);
                PunchMonitorEngine.checkAccounts(appContext, false, message -> {
                    if (callback != null) {
                        callback.onNewPunch(message);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }
}
