package com.infopunch.checker;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class UpdateScheduler {
    private static final String UNIQUE_WORK_NAME = "cardinal_punch_update_check";

    public static void schedule(Context context) {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class,
                6,
                TimeUnit.HOURS
        ).build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
