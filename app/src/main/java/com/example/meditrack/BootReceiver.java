package com.example.meditrack;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                .equals(action)
                && !AlarmScheduler.hasExactAlarmPermission(context)) {
            Log.w(TAG, "Exact-alarm grant broadcast received without active access");
            return;
        }

        boolean shouldReschedule =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                        || Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                        || Intent.ACTION_DATE_CHANGED.equals(action)
                        || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                        .equals(action);

        if (shouldReschedule) {
            Log.d(TAG, "System event " + action + " — rebuilding medicine alarms");
            AlarmScheduler.scheduleAllAlarms(context);
        }
    }
}

