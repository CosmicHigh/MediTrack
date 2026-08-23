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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";
    private static final String PREFS_NAME = "meditrack_prefs";
    private static final String KEY_MEDICINES = "medicines_json";
    private static final String KEY_TIMES = "slot_times_json";
    private static final String KEY_SCHEDULED_ALARMS = "scheduled_alarm_keys";

    private static final String ACTION_PREFIX = "com.example.meditrack.REMINDER_";
    private static final int RC_DYNAMIC_BASE = 20000;
    private static final int RC_SHOW_APP = 19000;

    private static final String[] SLOT_IDS = {
            "morning", "afternoon", "evening", "night"
    };

    private static final int[] LEGACY_REQUEST_CODES = {
            1001, 1002, 1003, 1004
    };

    private static final String[] LEGACY_ACTIONS = {
            "com.example.meditrack.REMINDER_MORNING",
            "com.example.meditrack.REMINDER_AFTERNOON",
            "com.example.meditrack.REMINDER_EVENING",
            "com.example.meditrack.REMINDER_NIGHT"
    };

    /**
     * Persist the complete medicine plan received from the WebView and rebuild alarms.
     */
    public static void saveMedicinesAndReschedule(Context context, String medicinesJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MEDICINES, medicinesJson).apply();
        scheduleAllAlarms(context);
    }

    /**
     * Persist the four slot defaults and rebuild every default-linked medicine alarm.
     */
    public static void saveTimesAndReschedule(Context context, String timesJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TIMES, timesJson).apply();
        scheduleAllAlarms(context);
    }

    public static String getMedicinesJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MEDICINES, "[]");
    }

    public static boolean hasAnyMedicineDoses(Context context) {
        try {
            JSONArray medicines = new JSONArray(getMedicinesJson(context));
            for (int i = 0; i < medicines.length(); i++) {
                JSONObject medicine = medicines.optJSONObject(i);
                JSONArray doses = medicine == null
                        ? null
                        : medicine.optJSONArray("doses");
                if (doses != null && doses.length() > 0) return true;
            }
        } catch (Exception error) {
            Log.e(TAG, "Cannot inspect saved medicine data", error);
        }
        return false;
    }

    public static boolean hasExactAlarmPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    /**
     * Build one alarm for each unique slot + effective time. Multiple medicines due at
     * the same moment deliberately share an alarm and are grouped by ReminderReceiver.
     */
    public static synchronized void scheduleAllAlarms(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Map<String, AlarmSpec> nextAlarms = buildNextAlarmSpecs(appContext);
        Set<String> newKeys = new HashSet<>(nextAlarms.keySet());
        Set<String> oldKeys = new HashSet<>(
                prefs.getStringSet(KEY_SCHEDULED_ALARMS, new HashSet<>())
        );

        for (String oldKey : oldKeys) {
            if (!newKeys.contains(oldKey)) cancelDynamicAlarm(appContext, oldKey);
        }

        // Remove PendingIntents created by the original four-slot scheduler.
        cancelLegacySlotAlarms(appContext);

        for (AlarmSpec spec : nextAlarms.values()) {
            scheduleAlarm(appContext, spec);
        }

        prefs.edit().putStringSet(KEY_SCHEDULED_ALARMS, new HashSet<>(newKeys)).apply();
        Log.d(TAG, "Scheduled " + nextAlarms.size() + " dynamic medicine alarms: " + newKeys);
    }

    private static Map<String, AlarmSpec> buildNextAlarmSpecs(Context context) {
        try {
            JSONArray medicines = new JSONArray(getMedicinesJson(context));
            return buildNextAlarmSpecs(context, medicines, System.currentTimeMillis());
        } catch (Exception error) {
            Log.e(TAG, "Cannot build alarms from medicine JSON", error);
            return new HashMap<>();
        }
    }

    private static Map<String, AlarmSpec> buildNextAlarmSpecs(
            Context context,
            JSONArray medicines,
            long now
    ) {
        Map<String, AlarmSpec> specs = new HashMap<>();

        for (int i = 0; i < medicines.length(); i++) {
            JSONObject medicine = medicines.optJSONObject(i);
            if (medicine == null) continue;

            JSONArray doses = medicine.optJSONArray("doses");
            if (doses == null) continue;

            for (int j = 0; j < doses.length(); j++) {
                JSONObject dose = doses.optJSONObject(j);
                if (dose == null) continue;

                String slotId = getDoseSlot(dose);
                if (!isKnownSlot(slotId)) continue;

                String effectiveTime = getEffectiveDoseTime(context, dose);
                int[] parsedTime = parseTime(effectiveTime);
                if (parsedTime == null) continue;

                long triggerAt = findNextOccurrence(
                        medicine, parsedTime[0], parsedTime[1], now
                );
                if (triggerAt < 0L) continue;

                String alarmKey = makeAlarmKey(slotId, effectiveTime);
                AlarmSpec existing = specs.get(alarmKey);
                if (existing == null || triggerAt < existing.triggerAtMillis) {
                    specs.put(alarmKey, new AlarmSpec(
                            alarmKey,
                            slotId,
                            effectiveTime,
                            requestCodeFor(slotId, parsedTime[0], parsedTime[1]),
                            triggerAt
                    ));
                }
            }
        }

        return specs;
    }

    /**
     * Return the next eligible local date/time for one medicine. The search starts
     * directly at the later of today or startDate, then needs at most seven weekday checks.
     */
    private static long findNextOccurrence(
            JSONObject medicine,
            int hour,
            int minute,
        long nowMillis
    ) {
        Calendar candidate = Calendar.getInstance();
        candidate.setTimeInMillis(nowMillis);
        candidate.set(Calendar.HOUR_OF_DAY, hour);
        candidate.set(Calendar.MINUTE, minute);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);

        if (candidate.getTimeInMillis() <= nowMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1);
        }

        String startDate = medicine.optString("startDate", "");
        if (isValidDateKey(startDate)
                && calendarDateKey(candidate).compareTo(startDate) < 0) {
            Calendar start = calendarFromDateKey(startDate);
            if (start != null) {
                start.set(Calendar.HOUR_OF_DAY, hour);
                start.set(Calendar.MINUTE, minute);
                start.set(Calendar.SECOND, 0);
                start.set(Calendar.MILLISECOND, 0);
                candidate = start;
                if (candidate.getTimeInMillis() <= nowMillis) {
                    candidate.add(Calendar.DAY_OF_YEAR, 1);
                }
            }
        }

        String endDate = getEffectiveEndDate(medicine);
        for (int attempts = 0; attempts < 8; attempts++) {
            String candidateDate = calendarDateKey(candidate);
            if (isValidDateKey(endDate) && candidateDate.compareTo(endDate) > 0) {
                return -1L;
            }
            if (isMedicineScheduledOnDate(medicine, candidate)) {
                return candidate.getTimeInMillis();
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1);
        }

        return -1L;
    }

    /**
     * Used by ReminderReceiver to make the native due-now decision.
     */
    public static boolean isMedicineScheduledOnDate(
            JSONObject medicine,
            Calendar date
    ) {
        String dateString = calendarDateKey(date);
        String startDate = medicine.optString("startDate", "");
        if (isValidDateKey(startDate) && dateString.compareTo(startDate) < 0) {
            return false;
        }

        String endDate = getEffectiveEndDate(medicine);
        if (isValidDateKey(endDate) && dateString.compareTo(endDate) > 0) {
            return false;
        }

        JSONArray days = medicine.optJSONArray("days");
        if (days == null || days.length() == 0) return true;

        String dayKey = calendarDayToKey(date.get(Calendar.DAY_OF_WEEK));
        for (int i = 0; i < days.length(); i++) {
            if (dayKey.equals(days.optString(i))) return true;
        }
        return false;
    }

    public static String getDoseSlot(JSONObject dose) {
        String slot = dose.optString("slot", "");
        if (slot.isEmpty()) slot = dose.optString("time", "");
        return slot;
    }

    /**
     * Resolve a dose dynamically so Settings changes continue to affect default-linked doses.
     */
    public static String getEffectiveDoseTime(Context context, JSONObject dose) {
        String slotId = getDoseSlot(dose);
        boolean useDefault = dose.optBoolean("useDefaultTime", true);
        if (!useDefault) {
            String customTime = dose.optString("customTime", "");
            if (parseTime(customTime) != null) return customTime;
        }
        return getSlotTimeString(context, slotId);
    }

    private static String getSlotTimeString(Context context, String slotId) {
        String fallback;
        switch (slotId) {
            case "morning":   fallback = "08:00"; break;
            case "afternoon": fallback = "13:00"; break;
            case "evening":   fallback = "18:00"; break;
            case "night":     fallback = "22:00"; break;
            default:          fallback = "08:00"; break;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String timesJson = prefs.getString(KEY_TIMES, null);
        if (timesJson == null) return fallback;

        try {
            JSONObject times = new JSONObject(timesJson);
            String stored = times.optString(slotId, fallback);
            return parseTime(stored) != null ? stored : fallback;
        } catch (Exception error) {
            Log.e(TAG, "Cannot parse slot defaults", error);
            return fallback;
        }
    }

    private static String getEffectiveEndDate(JSONObject medicine) {
        String storedEnd = medicine.optString("endDate", "");
        if (isValidDateKey(storedEnd)) return storedEnd;

        String unit = medicine.optString("durationUnit", "indefinite");
        if ("indefinite".equals(unit)) return "";

        String startDate = medicine.optString("startDate", "");
        int amount = medicine.optInt("durationValue", 0);
        if (!isValidDateKey(startDate) || amount < 1) return "";

        Calendar end = calendarFromDateKey(startDate);
        if (end == null) return "";

        switch (unit) {
            case "days":
                end.add(Calendar.DAY_OF_YEAR, amount - 1);
                break;
            case "weeks":
                end.add(Calendar.DAY_OF_YEAR, amount * 7 - 1);
                break;
            case "months": {
                int originalDay = end.get(Calendar.DAY_OF_MONTH);
                end.set(Calendar.DAY_OF_MONTH, 1);
                end.add(Calendar.MONTH, amount);
                int lastDay = end.getActualMaximum(Calendar.DAY_OF_MONTH);
                end.set(Calendar.DAY_OF_MONTH, Math.min(originalDay, lastDay));
                end.add(Calendar.DAY_OF_YEAR, -1);
                break;
            }
            case "years": {
                int originalMonth = end.get(Calendar.MONTH);
                int originalDay = end.get(Calendar.DAY_OF_MONTH);
                end.set(Calendar.DAY_OF_MONTH, 1);
                end.add(Calendar.YEAR, amount);
                end.set(Calendar.MONTH, originalMonth);
                int lastDay = end.getActualMaximum(Calendar.DAY_OF_MONTH);
                end.set(Calendar.DAY_OF_MONTH, Math.min(originalDay, lastDay));
                end.add(Calendar.DAY_OF_YEAR, -1);
                break;
            }
            default:
                return "";
        }

        return calendarDateKey(end);
    }

    private static void scheduleAlarm(Context context, AlarmSpec spec) {
        AlarmManager manager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        PendingIntent reminderIntent = buildReminderPendingIntent(
                context,
                spec.alarmKey,
                spec.slotId,
                spec.time,
                dateKeyFromMillis(spec.triggerAtMillis),
                spec.requestCode,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent showIntent = new Intent(context, MainActivity.class);
        showIntent.setAction("com.example.meditrack.SHOW_MEDICINES");
        showIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPendingIntent = PendingIntent.getActivity(
                context,
                RC_SHOW_APP,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        spec.triggerAtMillis,
                        reminderIntent
                );
                Log.w(TAG, "Exact-alarm access unavailable; scheduled inexact fallback for "
                        + spec.alarmKey);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlarmManager.AlarmClockInfo alarmClockInfo =
                        new AlarmManager.AlarmClockInfo(
                                spec.triggerAtMillis,
                                showPendingIntent
                        );
                manager.setAlarmClock(alarmClockInfo, reminderIntent);
            } else {
                manager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        spec.triggerAtMillis,
                        reminderIntent
                );
            }
        } catch (SecurityException error) {
            Log.e(TAG, "Exact alarm denied for " + spec.alarmKey, error);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        spec.triggerAtMillis,
                        reminderIntent
                );
            } else {
                manager.set(
                        AlarmManager.RTC_WAKEUP,
                        spec.triggerAtMillis,
                        reminderIntent
                );
            }
        }
    }

    private static PendingIntent buildReminderPendingIntent(
            Context context,
            String alarmKey,
            String slotId,
            String time,
            String scheduledDate,
            int requestCode,
            int baseFlags
    ) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ACTION_PREFIX + slotId.toUpperCase(Locale.US)
                + "_" + time.replace(":", ""));
        intent.putExtra("alarm_key", alarmKey);
        intent.putExtra("slot_id", slotId);
        intent.putExtra("alarm_time", time);
        intent.putExtra("scheduled_date", scheduledDate);

        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                baseFlags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void cancelDynamicAlarm(Context context, String alarmKey) {
        ParsedAlarmKey parsed = parseAlarmKey(alarmKey);
        if (parsed == null) return;

        AlarmManager manager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        PendingIntent pendingIntent = buildReminderPendingIntent(
                context,
                alarmKey,
                parsed.slotId,
                parsed.time,
                "",
                requestCodeFor(parsed.slotId, parsed.hour, parsed.minute),
                PendingIntent.FLAG_NO_CREATE
        );
        if (pendingIntent != null) {
            manager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "Cancelled stale alarm " + alarmKey);
        }
    }

    private static void cancelLegacySlotAlarms(Context context) {
        AlarmManager manager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        for (int i = 0; i < SLOT_IDS.length; i++) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            intent.setAction(LEGACY_ACTIONS[i]);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    LEGACY_REQUEST_CODES[i],
                    intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (pendingIntent != null) {
                manager.cancel(pendingIntent);
                pendingIntent.cancel();
            }
        }
    }

    private static int requestCodeFor(
            String slotId,
            int hour,
            int minute
    ) {
        int slotIndex = 0;
        for (int i = 0; i < SLOT_IDS.length; i++) {
            if (SLOT_IDS[i].equals(slotId)) {
                slotIndex = i;
                break;
            }
        }
        return RC_DYNAMIC_BASE + slotIndex * 1440 + hour * 60 + minute;
    }

    private static String makeAlarmKey(String slotId, String time) {
        return slotId + "@" + time;
    }

    private static ParsedAlarmKey parseAlarmKey(String alarmKey) {
        if (alarmKey == null) return null;
        int separator = alarmKey.indexOf('@');
        if (separator <= 0 || separator >= alarmKey.length() - 1) return null;

        String slotId = alarmKey.substring(0, separator);
        String time = alarmKey.substring(separator + 1);
        int[] parsed = parseTime(time);
        if (!isKnownSlot(slotId) || parsed == null) return null;

        return new ParsedAlarmKey(slotId, time, parsed[0], parsed[1]);
    }

    private static int[] parseTime(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return null;
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return new int[]{hour, minute};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean isKnownSlot(String slotId) {
        for (String known : SLOT_IDS) {
            if (known.equals(slotId)) return true;
        }
        return false;
    }

    private static boolean isValidDateKey(String value) {
        Calendar parsed = calendarFromDateKey(value);
        return parsed != null && value.equals(calendarDateKey(parsed));
    }

    public static Calendar calendarFromDateKey(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            int year = Integer.parseInt(value.substring(0, 4));
            int month = Integer.parseInt(value.substring(5, 7));
            int day = Integer.parseInt(value.substring(8, 10));
            Calendar calendar = Calendar.getInstance();
            calendar.setLenient(false);
            calendar.clear();
            calendar.set(year, month - 1, day, 12, 0, 0);
            calendar.getTimeInMillis();
            return calendar;
        } catch (Exception error) {
            return null;
        }
    }

    public static String calendarDateKey(Calendar calendar) {
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
        );
    }

    private static String dateKeyFromMillis(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendarDateKey(calendar);
    }

    public static String calendarDayToKey(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY:    return "mon";
            case Calendar.TUESDAY:   return "tue";
            case Calendar.WEDNESDAY: return "wed";
            case Calendar.THURSDAY:  return "thu";
            case Calendar.FRIDAY:    return "fri";
            case Calendar.SATURDAY:  return "sat";
            case Calendar.SUNDAY:    return "sun";
            default:                 return "mon";
        }
    }

    private static final class AlarmSpec {
        final String alarmKey;
        final String slotId;
        final String time;
        final int requestCode;
        final long triggerAtMillis;

        AlarmSpec(
                String alarmKey,
                String slotId,
                String time,
                int requestCode,
                long triggerAtMillis
        ) {
            this.alarmKey = alarmKey;
            this.slotId = slotId;
            this.time = time;
            this.requestCode = requestCode;
            this.triggerAtMillis = triggerAtMillis;
        }
    }

    private static final class ParsedAlarmKey {
        final String slotId;
        final String time;
        final int hour;
        final int minute;

        ParsedAlarmKey(String slotId, String time, int hour, int minute) {
            this.slotId = slotId;
            this.time = time;
            this.hour = hour;
            this.minute = minute;
        }
    }
}
