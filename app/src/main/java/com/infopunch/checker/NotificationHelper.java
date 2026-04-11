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

    public static void ensureChannel(Context context) {
        ensureNotificationChannel(context, CHANNEL_NEW_PUNCH_PREFIX + "default", "Nouveaux poincons", null);
        ensureNotificationChannel(context, CHANNEL_MISSING_PUNCH_PREFIX + "default", "Poincons manquants", null);
    }

    public static void showNewPunchNotification(Context context, String accountId, String accountName, String text, String ringtoneUri) {
        showNotification(
                context,
                accountId,
                accountName,
                "Nouveau poincon",
                text,
                ringtoneUri,
                false
        );
    }

    public static void showMissingPunchNotification(Context context, String accountId, String accountName, String text, String ringtoneUri) {
        showNotification(
                context,
                accountId,
                accountName,
                "Poincon manquant",
                text,
                ringtoneUri,
                true
        );
    }

    private static void showNotification(
            Context context,
            String accountId,
            String accountName,
            String title,
            String text,
            String ringtoneUri,
            boolean highPriority
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String channelId = buildChannelId(highPriority, ringtoneUri);
        ensureNotificationChannel(
                context,
                channelId,
                highPriority ? "Alertes de poincon manquant" : "Nouveaux poincons",
                ringtoneUri
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && ringtoneUri != null && !ringtoneUri.isEmpty()) {
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

        if (ringtoneUri != null && !ringtoneUri.isEmpty()) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            channel.setSound(Uri.parse(ringtoneUri), attributes);
        }

        manager.createNotificationChannel(channel);
    }
}
