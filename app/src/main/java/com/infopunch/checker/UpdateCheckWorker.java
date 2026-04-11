package com.infopunch.checker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class UpdateCheckWorker extends Worker {
    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return Result.success();
        }
        try {
            AppUpdateManager updateManager = new AppUpdateManager(getApplicationContext());
            updateManager.checkForUpdates(true);
            PunchWidgetProvider.refreshAll(getApplicationContext());
            return Result.success();
        } catch (Exception exception) {
            return Result.retry();
        }
    }
}
