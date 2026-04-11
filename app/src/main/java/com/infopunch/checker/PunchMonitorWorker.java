package com.infopunch.checker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class PunchMonitorWorker extends Worker {
    public PunchMonitorWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            SessionManager sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.hasSession()) {
                return Result.success();
            }
            if (!sessionManager.hasAnyBackgroundMonitorEnabled()) {
                return Result.success();
            }

            NotificationHelper.ensureChannel(getApplicationContext());
            PunchMonitorEngine.checkAccounts(getApplicationContext(), true, null);
            return Result.success();
        } catch (Exception exception) {
            return Result.retry();
        }
    }
}
