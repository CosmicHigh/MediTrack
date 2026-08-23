package com.example.meditrack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Calendar;

public class ReminderService extends Service {

    private static final String TAG = "ReminderService";
    private static final String CHANNEL_ID = "meditrack_reminder_v2";
    private static final int RC_OPEN_REMINDER = 2101;
    private static final int RC_DISMISS_REMINDER = 2102;

    public static final int NOTIFICATION_ID = 2001;
    public static final String ACTION_STOP =
            "com.example.meditrack.STOP_REMINDER";
    public static final String ACTION_REMINDER_UPDATED =
            "com.example.meditrack.REMINDER_UPDATED";
    public static final String ACTION_REMINDER_DISMISSED =
            "com.example.meditrack.REMINDER_DISMISSED";

    private final ReminderPayload activePayload = new ReminderPayload();
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private boolean alarmActive;
    private String activeAlarmKey = "";
    private String activeScheduledDate = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!alarmActive) stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopAndRemoveAlarm();
            return START_NOT_STICKY;
        }

        ArrayList<String> incomingNames =
                intent.getStringArrayListExtra("med_names");
        if (incomingNames == null || incomingNames.isEmpty()) {
            Log.w(TAG, "Ignoring reminder without medicines");
            if (!alarmActive) stopSelf();
            return START_NOT_STICKY;
        }

        boolean wasActive = alarmActive;
        if (!wasActive) {
            activePayload.clear();
            activeAlarmKey = clean(intent.getStringExtra("alarm_key"));
            activeScheduledDate = clean(intent.getStringExtra("scheduled_date"));
        } else {
            activeAlarmKey = mergeMetadata(
                    activeAlarmKey,
                    clean(intent.getStringExtra("alarm_key"))
            );
            activeScheduledDate = mergeMetadata(
                    activeScheduledDate,
                    clean(intent.getStringExtra("scheduled_date"))
            );
        }

        activePayload.merge(
                incomingNames,
                intent.getStringArrayListExtra("med_foods"),
                intent.getStringArrayListExtra("med_times"),
                intent.getStringArrayListExtra("med_slots"),
                intent.getStringExtra("alarm_time"),
                intent.getStringExtra("slot_id")
        );

        if (activePayload.isEmpty()) {
            if (!wasActive) stopSelf();
            return START_NOT_STICKY;
        }

        alarmActive = true;
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        sendReminderUpdate();

        if (!wasActive) {
            startAlarmSound();
            startVibration();
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent fullScreenIntent = buildReminderIntent(
                new Intent(this, ReminderActivity.class)
        );
        fullScreenIntent.setAction("com.example.meditrack.OPEN_REMINDER");
        fullScreenIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                RC_OPEN_REMINDER,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, ReminderService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                RC_DISMISS_REMINDER,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String timeText = formatAlarmTime(activePayload.commonTime());
        String title = activePayload.size() == 1
                ? "Medicine alarm"
                : activePayload.size() + " medicines due";
        String text = activePayload.size() == 1
                ? activePayload.copyNames().get(0) + " · " + timeText
                : getSlotSummary(activePayload.commonSlot()) + " · " + timeText;

        StringBuilder expandedText = new StringBuilder();
        ArrayList<String> names = activePayload.copyNames();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) expandedText.append(" • ");
            expandedText.append(names.get(i));
        }
        expandedText.append("\n")
                .append(getSlotSummary(activePayload.commonSlot()))
                .append(" · ")
                .append(timeText);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(expandedText.toString()))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF138D84)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Dismiss",
                        stopPendingIntent
                )
                .setDefaults(0)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setLocalOnly(true)
                .build();
    }

    private Intent buildReminderIntent(Intent target) {
        target.putExtra("slot_id", activePayload.commonSlot());
        target.putExtra("alarm_time", activePayload.commonTime());
        target.putExtra("alarm_key", activeAlarmKey);
        target.putExtra("scheduled_date", activeScheduledDate);
        target.putStringArrayListExtra("med_names", activePayload.copyNames());
        target.putStringArrayListExtra("med_foods", activePayload.copyFoods());
        target.putStringArrayListExtra("med_times", activePayload.copyTimes());
        target.putStringArrayListExtra("med_slots", activePayload.copySlots());
        return target;
    }

    private void sendReminderUpdate() {
        Intent update = buildReminderIntent(new Intent(ACTION_REMINDER_UPDATED));
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    private void sendReminderDismissed() {
        Intent dismissed = new Intent(ACTION_REMINDER_DISMISSED);
        dismissed.setPackage(getPackageName());
        sendBroadcast(dismissed);
    }

    private void startAlarmSound() {
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(
                        RingtoneManager.TYPE_NOTIFICATION
                );
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(
                        RingtoneManager.TYPE_RINGTONE
                );
            }
            if (alarmUri == null) {
                Log.w(TAG, "No alarm, notification, or ringtone URI is available");
                return;
            }

            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setDataSource(this, alarmUri);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setLooping(true);
            player.setOnPreparedListener(prepared -> {
                if (mediaPlayer == prepared && alarmActive) {
                    prepared.start();
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                Log.e(TAG, "Alarm player error " + what + "/" + extra);
                if (mediaPlayer == failed) mediaPlayer = null;
                try {
                    failed.release();
                } catch (RuntimeException ignored) {
                    // The framework may already have released an errored player.
                }
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            Log.e(TAG, "Could not prepare alarm sound", error);
            releaseMediaPlayer();
        }
    }

    private void startVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) vibrator = manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0L, 500L, 300L, 500L, 300L, 500L, 1000L};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start vibration", error);
        }
    }

    private void stopAndRemoveAlarm() {
        alarmActive = false;
        activePayload.clear();
        activeAlarmKey = "";
        activeScheduledDate = "";
        stopAlarmOutput();
        stopForeground(STOP_FOREGROUND_REMOVE);

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);

        sendReminderDismissed();
        stopSelf();
    }

    private void stopAlarmOutput() {
        releaseMediaPlayer();
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private void releaseMediaPlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player == null) return;

        try {
            player.stop();
        } catch (RuntimeException ignored) {
            // A player that is still preparing cannot be stopped.
        }
        try {
            player.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not release alarm player cleanly", error);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Medicine alarms",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Full-screen alerts for scheduled medicines");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setBypassDnd(true);

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private String formatAlarmTime(String time24) {
        if (ReminderPayload.MULTIPLE.equals(time24)) return "Multiple times";
        int[] parsed = parseTime(time24);
        if (parsed == null) return "Now";

        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, parsed[0]);
        time.set(Calendar.MINUTE, parsed[1]);
        time.set(Calendar.SECOND, 0);
        time.set(Calendar.MILLISECOND, 0);
        return android.text.format.DateFormat.getTimeFormat(this)
                .format(time.getTime());
    }

    private static int[] parseTime(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return null;
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            if (hour > 23 || minute > 59) return null;
            return new int[]{hour, minute};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String getSlotSummary(String slotId) {
        if (ReminderPayload.MULTIPLE.equals(slotId)) return "Multiple schedules";
        if (slotId == null) return "Medicine schedule";
        switch (slotId) {
            case "morning":   return "Morning";
            case "afternoon": return "Afternoon";
            case "evening":   return "Evening";
            case "night":     return "Night";
            default:          return "Medicine schedule";
        }
    }

    private static String mergeMetadata(String current, String incoming) {
        if (current.isEmpty()) return incoming;
        if (incoming.isEmpty() || current.equals(incoming)) return current;
        return ReminderPayload.MULTIPLE;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void onDestroy() {
        boolean wasActive = alarmActive;
        alarmActive = false;
        activePayload.clear();
        stopAlarmOutput();
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        if (wasActive) sendReminderDismissed();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
