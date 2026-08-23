package com.example.meditrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String slotId = intent.getStringExtra("slot_id");
        String alarmTime = intent.getStringExtra("alarm_time");
        String alarmKey = intent.getStringExtra("alarm_key");
        String scheduledDate = intent.getStringExtra("scheduled_date");

        if (slotId == null || slotId.isEmpty()) {
            Log.w(TAG, "Ignoring alarm without a slot");
            return;
        }

        Calendar dueDate = AlarmScheduler.calendarFromDateKey(scheduledDate);
        if (dueDate == null) dueDate = Calendar.getInstance();

        Log.d(TAG, "Alarm fired: " + alarmKey + " for "
                + AlarmScheduler.calendarDateKey(dueDate));

        String json = AlarmScheduler.getMedicinesJson(context);
        ArrayList<String> medNames = new ArrayList<>();
        ArrayList<String> medFoods = new ArrayList<>();
        ArrayList<String> medTimes = new ArrayList<>();
        ArrayList<String> medSlots = new ArrayList<>();

        try {
            JSONArray medicines = new JSONArray(json);
            for (int i = 0; i < medicines.length(); i++) {
                JSONObject medicine = medicines.optJSONObject(i);
                if (medicine == null
                        || !AlarmScheduler.isMedicineScheduledOnDate(medicine, dueDate)) {
                    continue;
                }

                JSONArray doses = medicine.optJSONArray("doses");
                if (doses == null) continue;

                String matchedFood = null;
                String matchedTime = null;
                for (int j = 0; j < doses.length(); j++) {
                    JSONObject dose = doses.optJSONObject(j);
                    if (dose == null) continue;
                    if (!slotId.equals(AlarmScheduler.getDoseSlot(dose))) continue;

                    String effectiveTime =
                            AlarmScheduler.getEffectiveDoseTime(context, dose);
                    if (alarmTime == null || alarmTime.equals(effectiveTime)) {
                        matchedFood = "after".equals(dose.optString("food"))
                                ? "after"
                                : "before";
                        matchedTime = effectiveTime;
                        break;
                    }
                }

                if (matchedFood != null) {
                    String name = medicine.optString("name", "").trim();
                    if (!name.isEmpty()) {
                        medNames.add(name);
                        medFoods.add(matchedFood);
                        medTimes.add(matchedTime == null ? "" : matchedTime);
                        medSlots.add(slotId);
                    }
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Cannot resolve medicines due for " + alarmKey, error);
        }

        if (medNames.isEmpty()) {
            Log.d(TAG, "No active medicines due for " + alarmKey);
            AlarmScheduler.scheduleAllAlarms(context);
            return;
        }

        if (!NotificationAccess.areNotificationsEnabled(context)) {
            NotificationAccess.recordBlockedReminder(
                    context,
                    alarmKey,
                    AlarmScheduler.calendarDateKey(dueDate),
                    medNames
            );
            Log.w(
                    TAG,
                    "Suppressed alarm " + alarmKey
                            + " because no visible notification or Dismiss action can be shown"
            );
            AlarmScheduler.scheduleAllAlarms(context);
            return;
        }

        Intent serviceIntent = new Intent(context, ReminderService.class);
        serviceIntent.putExtra("slot_id", slotId);
        serviceIntent.putExtra("alarm_time", alarmTime);
        serviceIntent.putExtra("alarm_key", alarmKey);
        serviceIntent.putExtra(
                "scheduled_date",
                AlarmScheduler.calendarDateKey(dueDate)
        );
        serviceIntent.putStringArrayListExtra("med_names", medNames);
        serviceIntent.putStringArrayListExtra("med_foods", medFoods);
        serviceIntent.putStringArrayListExtra("med_times", medTimes);
        serviceIntent.putStringArrayListExtra("med_slots", medSlots);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start reminder service for " + alarmKey, error);
        } finally {
            // AlarmManager entries are one-shot. Build the next eligible occurrence now.
            AlarmScheduler.scheduleAllAlarms(context);
        }
    }
}
