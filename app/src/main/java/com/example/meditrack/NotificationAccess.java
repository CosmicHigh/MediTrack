package com.example.meditrack;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

/**
 * Single source of truth for whether MediTrack can expose a safe, dismissible alarm.
 */
final class NotificationAccess {

    private static final String PREFS_NAME = "meditrack_notification_access";
    private static final String KEY_PERMISSION_REQUESTED = "permission_requested";
    private static final String KEY_HAS_KNOWN_STATE = "has_known_state";
    private static final String KEY_LAST_ENABLED_STATE = "last_enabled_state";
    private static final String KEY_BLOCKED_COUNT = "blocked_reminder_count";
    private static final String KEY_LAST_BLOCKED_AT = "last_blocked_at";
    private static final String KEY_LAST_BLOCKED_ALARM = "last_blocked_alarm";
    private static final String KEY_LAST_BLOCKED_DATE = "last_blocked_date";
    private static final String KEY_LAST_BLOCKED_MEDICINES = "last_blocked_medicines";

    private NotificationAccess() {
    }

    static boolean areNotificationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE
                    );
            NotificationChannel channel = manager == null
                    ? null
                    : manager.getNotificationChannel(ReminderService.CHANNEL_ID);
            if (channel != null
                    && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        return true;
    }

    static boolean wasRuntimePermissionRequested(Context context) {
        return preferences(context).getBoolean(KEY_PERMISSION_REQUESTED, false);
    }

    static void markRuntimePermissionRequested(Context context) {
        preferences(context).edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply();
    }

    /**
     * Persist the current state and report only a real disabled -> enabled transition.
     */
    static boolean rememberStateAndWasRestored(Context context, boolean enabled) {
        SharedPreferences prefs = preferences(context);
        boolean hadKnownState = prefs.getBoolean(KEY_HAS_KNOWN_STATE, false);
        boolean previousState = prefs.getBoolean(KEY_LAST_ENABLED_STATE, false);
        prefs.edit()
                .putBoolean(KEY_HAS_KNOWN_STATE, true)
                .putBoolean(KEY_LAST_ENABLED_STATE, enabled)
                .apply();
        return hadKnownState && !previousState && enabled;
    }

    static void recordBlockedReminder(
            Context context,
            String alarmKey,
            String scheduledDate,
            ArrayList<String> medicineNames
    ) {
        SharedPreferences prefs = preferences(context);
        int blockedCount = prefs.getInt(KEY_BLOCKED_COUNT, 0);
        prefs.edit()
                .putInt(KEY_BLOCKED_COUNT, blockedCount + 1)
                .putLong(KEY_LAST_BLOCKED_AT, System.currentTimeMillis())
                .putString(KEY_LAST_BLOCKED_ALARM, clean(alarmKey))
                .putString(KEY_LAST_BLOCKED_DATE, clean(scheduledDate))
                .putString(
                        KEY_LAST_BLOCKED_MEDICINES,
                        medicineNames == null ? "" : joinNames(medicineNames)
                )
                .apply();
    }

    static int getBlockedReminderCount(Context context) {
        return preferences(context).getInt(KEY_BLOCKED_COUNT, 0);
    }

    static long getLastBlockedAt(Context context) {
        return preferences(context).getLong(KEY_LAST_BLOCKED_AT, 0L);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

    private static String joinNames(ArrayList<String> medicineNames) {
        StringBuilder joined = new StringBuilder();
        for (String rawName : medicineNames) {
            String name = clean(rawName);
            if (name.isEmpty()) continue;
            if (joined.length() > 0) joined.append(" • ");
            joined.append(name);
        }
        return joined.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
