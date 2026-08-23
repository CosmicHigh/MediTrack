package com.example.meditrack;

import java.util.ArrayList;

/**
 * Holds every medicine represented by the currently ringing alarm. A second alarm
 * can arrive before the first one is dismissed, so entries are merged by medicine,
 * slot, and time instead of silently replacing the visible reminder.
 */
final class ReminderPayload {

    static final String MULTIPLE = "multiple";

    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> foods = new ArrayList<>();
    private final ArrayList<String> times = new ArrayList<>();
    private final ArrayList<String> slots = new ArrayList<>();

    void clear() {
        names.clear();
        foods.clear();
        times.clear();
        slots.clear();
    }

    boolean isEmpty() {
        return names.isEmpty();
    }

    int size() {
        return names.size();
    }

    void merge(
            ArrayList<String> incomingNames,
            ArrayList<String> incomingFoods,
            ArrayList<String> incomingTimes,
            ArrayList<String> incomingSlots,
            String fallbackTime,
            String fallbackSlot
    ) {
        if (incomingNames == null) return;

        for (int i = 0; i < incomingNames.size(); i++) {
            String name = clean(valueAt(incomingNames, i));
            if (name.isEmpty()) continue;

            String food = "after".equals(valueAt(incomingFoods, i))
                    ? "after"
                    : "before";
            String time = clean(valueAt(incomingTimes, i));
            String slot = clean(valueAt(incomingSlots, i));
            if (time.isEmpty()) time = clean(fallbackTime);
            if (slot.isEmpty()) slot = clean(fallbackSlot);

            int existing = indexOf(name, slot, time);
            if (existing >= 0) {
                foods.set(existing, food);
                continue;
            }

            names.add(name);
            foods.add(food);
            times.add(time);
            slots.add(slot);
        }
    }

    ArrayList<String> copyNames() {
        return new ArrayList<>(names);
    }

    ArrayList<String> copyFoods() {
        return new ArrayList<>(foods);
    }

    ArrayList<String> copyTimes() {
        return new ArrayList<>(times);
    }

    ArrayList<String> copySlots() {
        return new ArrayList<>(slots);
    }

    String commonTime() {
        return commonValue(times);
    }

    String commonSlot() {
        return commonValue(slots);
    }

    private int indexOf(String name, String slot, String time) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)
                    && slots.get(i).equals(slot)
                    && times.get(i).equals(time)) {
                return i;
            }
        }
        return -1;
    }

    private static String commonValue(ArrayList<String> values) {
        if (values.isEmpty()) return "";
        String first = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (!first.equals(values.get(i))) return MULTIPLE;
        }
        return first;
    }

    private static String valueAt(ArrayList<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
