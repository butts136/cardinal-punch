package com.infopunch.checker;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class PunchMonitorScheduler {
    private static final String UNIQUE_WORK_NAME = "info_punch_monitor";

    public static void schedule(Context context) {
        try {
            SessionManager sessionManager = new SessionManager(context);
            if (!sessionManager.hasSession() || !sessionManager.hasAnyBackgroundMonitorEnabled()) {
                cancel(context);
                return;
            }
        } catch (Exception ignored) {
            cancel(context);
            return;
        }

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PunchMonitorWorker.class,
                15,
                TimeUnit.MINUTES
        ).build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }
}
