package com.infopunch.checker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class NotificationHelper {
    private static final String CHANNEL_NEW_PUNCH_PREFIX = "info_punch_updates_";
    private static final String CHANNEL_MISSING_PUNCH_PREFIX = "info_punch_missing_";
    private static final String CHANNEL_APP_UPDATE = "cardinal_punch_app_updates";

    public static void ensureChannel(Context context) {
        ensureNotificationChannel(context, CHANNEL_NEW_PUNCH_PREFIX + "default", "Nouveaux poincons", null);
        ensureNotificationChannel(context, CHANNEL_MISSING_PUNCH_PREFIX + "default", "Poincons manquants", null);
        ensureNotificationChannel(context, CHANNEL_APP_UPDATE, "Mises a jour de l'application", null);
    }

    public static void showNewPunchNotification(Context context, String accountId, String accountName, String text, String ringtoneUri, boolean soundEnabled) {
        showNotification(
                context,
                accountId,
                accountName,
                "Nouveau poincon",
                text,
                soundEnabled ? ringtoneUri : "",
                soundEnabled,
                false
        );
    }

    public static void showMissingPunchNotification(Context context, String accountId, String accountName, String text, String ringtoneUri, boolean soundEnabled) {
        showNotification(
                context,
                accountId,
                accountName,
                "Poincon manquant",
                text,
                soundEnabled ? ringtoneUri : "",
                soundEnabled,
                true
        );
    }

    public static void showUpdateNotification(Context context, String title, String text, Intent actionIntent) {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                7001,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_APP_UPDATE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManagerCompat.from(context).notify(7001, builder.build());
    }

    private static void showNotification(
            Context context,
            String accountId,
            String accountName,
            String title,
            String text,
            String ringtoneUri,
            boolean soundEnabled,
            boolean highPriority
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String channelId = buildChannelId(highPriority, soundEnabled ? ringtoneUri : "silent");
        ensureNotificationChannel(
                context,
                channelId,
                highPriority ? "Alertes de poincon manquant" : "Nouveaux poincons",
                soundEnabled ? ringtoneUri : "silent"
        );

        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ACCOUNT_ID, accountId);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                Math.abs((accountId + channelId).hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String fullTitle = accountName == null || accountName.trim().isEmpty()
                ? title
                : title + " - " + accountName;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(fullTitle)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(highPriority ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(highPriority ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && soundEnabled && ringtoneUri != null && !ringtoneUri.isEmpty()) {
            builder.setSound(Uri.parse(ringtoneUri));
        }

        NotificationManagerCompat.from(context)
                .notify(Math.abs((accountId + title).hashCode()), builder.build());
    }

    private static String buildChannelId(boolean highPriority, String ringtoneUri) {
        String prefix = highPriority ? CHANNEL_MISSING_PUNCH_PREFIX : CHANNEL_NEW_PUNCH_PREFIX;
        String suffix = ringtoneUri == null || ringtoneUri.isEmpty()
                ? "default"
                : Integer.toHexString(ringtoneUri.hashCode());
        return prefix + suffix;
    }

    private static void ensureNotificationChannel(Context context, String channelId, String channelName, String ringtoneUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel existing = manager.getNotificationChannel(channelId);
        if (existing != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                channelId,
                channelName,
                channelId.startsWith(CHANNEL_MISSING_PUNCH_PREFIX)
                        ? NotificationManager.IMPORTANCE_HIGH
                        : NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(channelName);

        if ("silent".equals(ringtoneUri)) {
            channel.setSound(null, null);
        } else if (ringtoneUri != null && !ringtoneUri.isEmpty()) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            channel.setSound(Uri.parse(ringtoneUri), attributes);
        }

        manager.createNotificationChannel(channel);
    }
}
