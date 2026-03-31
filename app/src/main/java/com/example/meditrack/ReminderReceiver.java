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
        if (slotId == null) return;

        Log.d(TAG, "Alarm fired for slot: " + slotId);

        int calDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        String todayKey = AlarmScheduler.calendarDayToKey(calDay);

        String json = AlarmScheduler.getMedicinesJson(context);
        ArrayList<String> medNames = new ArrayList<>();
        ArrayList<String> medFoods = new ArrayList<>();

        try {
            JSONArray meds = new JSONArray(json);
            for (int i = 0; i < meds.length(); i++) {
                JSONObject med = meds.getJSONObject(i);
                String name = med.getString("name");

                JSONArray days = med.optJSONArray("days");
                boolean scheduledToday = false;
                if (days == null || days.length() == 0) {
                    scheduledToday = true;
                } else {
                    for (int d = 0; d < days.length(); d++) {
                        if (days.getString(d).equals(todayKey)) {
                            scheduledToday = true;
                            break;
                        }
                    }
                }
                if (!scheduledToday) continue;

                JSONArray doses = med.getJSONArray("doses");
                for (int j = 0; j < doses.length(); j++) {
                    JSONObject dose = doses.getJSONObject(j);
                    if (dose.getString("time").equals(slotId)) {
                        medNames.add(name);
                        medFoods.add(dose.getString("food"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing medicines", e);
        }

        if (medNames.isEmpty()) {
            Log.d(TAG, "No medicines due for slot " + slotId + " on " + todayKey);
            AlarmScheduler.scheduleAllAlarms(context);
            return;
        }

        // Start the foreground service — this plays the sound immediately
        Intent serviceIntent = new Intent(context, ReminderService.class);
        serviceIntent.putExtra("slot_id", slotId);
        serviceIntent.putStringArrayListExtra("med_names", medNames);
        serviceIntent.putStringArrayListExtra("med_foods", medFoods);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // Reschedule for next day
        AlarmScheduler.scheduleAllAlarms(context);
    }
}