package com.infopunch.checker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MonitorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.ULTRA_FAST_BRIDGE_ENABLED) {
            return;
        }
        try {
            SessionManager sessionManager = new SessionManager(context);
            if (sessionManager.hasSession() && sessionManager.isUltraFastEnabled()) {
                UltraFastMonitorService.start(context);
            }
        } catch (Exception ignored) {
            PunchMonitorScheduler.schedule(context);
        }
    }
}
