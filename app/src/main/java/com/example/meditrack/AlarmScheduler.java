package com.example.meditrack;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";
    private static final String PREFS_NAME = "meditrack_prefs";
    private static final String KEY_MEDICINES = "medicines_json";
    private static final String KEY_TIMES = "slot_times_json";

    // Request codes for each time slot
    private static final int RC_MORNING   = 1001;
    private static final int RC_AFTERNOON = 1002;
    private static final int RC_EVENING   = 1003;
    private static final int RC_NIGHT     = 1004;


    /**
     * Save medicines JSON to SharedPreferences and reschedule all alarms.
     */
    public static void saveMedicinesAndReschedule(Context context, String medicinesJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MEDICINES, medicinesJson).apply();
        scheduleAllAlarms(context);
    }
    /**
     * Save custom slot times and reschedule alarms.
     * Expected JSON: {"morning":"08:00","afternoon":"13:00","evening":"18:00","night":"22:00"}
     */
    public static void saveTimesAndReschedule(Context context, String timesJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TIMES, timesJson).apply();
        scheduleAllAlarms(context);
    }
    /**
     * Read hour and minute for a given slot. Falls back to defaults.
     */
    private static int[] getSlotTime(Context context, String slotId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_TIMES, null);

        // Defaults
        int hour, minute;
        switch (slotId) {
            case "morning":   hour = 8;  minute = 0; break;
            case "afternoon": hour = 13; minute = 0; break;
            case "evening":   hour = 18; minute = 0; break;
            case "night":     hour = 22; minute = 0; break;
            default:          hour = 8;  minute = 0; break;
        }

        if (json != null) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                String timeStr = obj.optString(slotId, null);
                if (timeStr != null && timeStr.contains(":")) {
                    String[] parts = timeStr.split(":");
                    hour = Integer.parseInt(parts[0]);
                    minute = Integer.parseInt(parts[1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing slot times", e);
            }
        }
        return new int[]{hour, minute};
    }

    /**
     * Read stored medicines JSON.
     */
    public static String getMedicinesJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MEDICINES, "[]");
    }

    /**
     * Schedule alarms for all time slots that have at least one medicine.
     * Cancel alarms for slots with no medicines.
     */
    public static void scheduleAllAlarms(Context context) {
        String json = getMedicinesJson(context);
        Set<String> activeSlots = getActiveSlots(json);

        String[] slots = {"morning", "afternoon", "evening", "night"};
        int[] requestCodes = {RC_MORNING, RC_AFTERNOON, RC_EVENING, RC_NIGHT};

        for (int i = 0; i < slots.length; i++) {
            int[] time = getSlotTime(context, slots[i]);
            scheduleOrCancel(context, slots[i], requestCodes[i],
                    time[0], time[1], activeSlots.contains(slots[i]));
        }

        Log.d(TAG, "Alarms scheduled. Active slots: " + activeSlots);
    }

    /**
     * Find which time slots have at least one medicine assigned (any day).
     */
    private static Set<String> getActiveSlots(String json) {
        Set<String> slots = new HashSet<>();
        try {
            JSONArray meds = new JSONArray(json);
            for (int i = 0; i < meds.length(); i++) {
                JSONObject med = meds.getJSONObject(i);
                JSONArray doses = med.getJSONArray("doses");
                for (int j = 0; j < doses.length(); j++) {
                    slots.add(doses.getJSONObject(j).getString("time"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing medicines JSON", e);
        }
        return slots;
    }

    private static void scheduleOrCancel(Context context, String slotId, int requestCode,
                                         int hour, int minute, boolean shouldSchedule) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction("com.example.meditrack.REMINDER_" + slotId.toUpperCase());
        intent.putExtra("slot_id", slotId);

        PendingIntent pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (!shouldSchedule) {
            alarmManager.cancel(pi);
            Log.d(TAG, "Cancelled alarm for slot: " + slotId);
            return;
        }

        // Calculate next trigger time
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // If the time has already passed today, schedule for tomorrow
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAlarmClock(
                            new AlarmManager.AlarmClockInfo(cal.getTimeInMillis(), pi), pi
                    );
                } else {
                    // Fallback: inexact alarm (will still work, just less precise)
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(cal.getTimeInMillis(), pi), pi
                );
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
            Log.d(TAG, "Scheduled alarm for slot: " + slotId + " at " + cal.getTime());
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot schedule exact alarm — permission denied", e);
            // Fallback to inexact
            alarmManager.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    /**
     * Map Java Calendar day-of-week to the JS day key used in medicines.
     */
    public static String calendarDayToKey(int calDay) {
        switch (calDay) {
            case Calendar.MONDAY:    return "mon";
            case Calendar.TUESDAY:   return "tue";
            case Calendar.WEDNESDAY: return "wed";
            case Calendar.THURSDAY:  return "thu";
            case Calendar.FRIDAY:    return "fri";
            case Calendar.SATURDAY:  return "sat";
            case Calendar.SUNDAY:    return "sun";
            default: return "mon";
        }
    }
}
