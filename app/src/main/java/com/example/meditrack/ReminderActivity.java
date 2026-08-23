package com.example.meditrack;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;

public class ReminderActivity extends AppCompatActivity {

    private ObjectAnimator iconPulseAnimator;
    private boolean reminderReceiverRegistered;

    private final BroadcastReceiver reminderStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ReminderService.ACTION_REMINDER_DISMISSED.equals(intent.getAction())) {
                finish();
            } else if (ReminderService.ACTION_REMINDER_UPDATED.equals(intent.getAction())) {
                renderReminder(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLockScreenFlags();
        configureLiquidWindow();
        setContentView(R.layout.activity_reminder);
        registerReminderStateReceiver();

        if (!renderReminder(getIntent())) return;

        Button dismissButton = findViewById(R.id.btnDismiss);
        dismissButton.setOnClickListener(view -> dismissReminder());

        animateEntrance();

        // An alarm requires an explicit acknowledgement; Back must not silence it.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally do nothing.
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderReminder(intent);
    }

    private boolean renderReminder(Intent intent) {
        String slotId = intent.getStringExtra("slot_id");
        String alarmTime = intent.getStringExtra("alarm_time");
        ArrayList<String> medNames = intent.getStringArrayListExtra("med_names");
        ArrayList<String> medFoods = intent.getStringArrayListExtra("med_foods");
        ArrayList<String> medTimes = intent.getStringArrayListExtra("med_times");
        ArrayList<String> medSlots = intent.getStringArrayListExtra("med_slots");

        if (medNames == null || medNames.isEmpty()) {
            finish();
            return false;
        }

        TextView title = findViewById(R.id.reminderTitle);
        title.setText(medNames.size() == 1
                ? "Time for your medicine"
                : "Time for your medicines");

        TextView timeView = findViewById(R.id.reminderTime);
        String displayedTime = formatAlarmTime(alarmTime);
        timeView.setText(displayedTime);
        timeView.setContentDescription("Scheduled time " + displayedTime);

        TextView reminderIcon = findViewById(R.id.reminderIcon);
        reminderIcon.setText(getSlotIcon(slotId));
        reminderIcon.setTextColor(getSlotAccentColor(slotId));

        TextView slotLabel = findViewById(R.id.reminderSlot);
        slotLabel.setText(getSlotDisplayText(slotId));
        slotLabel.setTextColor(getSlotAccentColor(slotId));

        LinearLayout container = findViewById(R.id.medicineListContainer);
        container.removeAllViews();
        for (int i = 0; i < medNames.size(); i++) {
            String food = valueAt(medFoods, i, "before");
            String medicineTime = valueAt(medTimes, i, alarmTime);
            String medicineSlot = valueAt(medSlots, i, slotId);
            container.addView(createMedicineItem(
                    medNames.get(i),
                    food,
                    medicineTime,
                    medicineSlot,
                    i + 1
            ));
        }
        return true;
    }

    private void registerReminderStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ReminderService.ACTION_REMINDER_UPDATED);
        filter.addAction(ReminderService.ACTION_REMINDER_DISMISSED);
        ContextCompat.registerReceiver(
                this,
                reminderStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        reminderReceiverRegistered = true;
    }

    private void configureLiquidWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.28f;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            params.setBlurBehindRadius(dpToPx(56));
        }

        window.setAttributes(params);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
    }

    private void setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager manager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (manager != null) manager.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private LinearLayout createMedicineItem(
            String name,
            String food,
            String alarmTime,
            String slotId,
            int position
    ) {
        final float density = getResources().getDisplayMetrics().density;
        boolean beforeFood = !"after".equals(food);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundResource(R.drawable.reminder_med_item_bg);
        item.setPadding(
                Math.round(18 * density),
                Math.round(16 * density),
                Math.round(18 * density),
                Math.round(16 * density)
        );

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.bottomMargin = Math.round(10 * density);
        item.setLayoutParams(itemParams);

        TextView index = new TextView(this);
        index.setText(String.valueOf(position));
        index.setTextColor(Color.parseColor("#B8EEE8"));
        index.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        index.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        index.setGravity(Gravity.CENTER);
        index.setBackgroundResource(R.drawable.reminder_icon_bg);
        index.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout.LayoutParams indexParams = new LinearLayout.LayoutParams(
                Math.round(48 * density),
                Math.round(48 * density)
        );
        indexParams.setMarginEnd(Math.round(14 * density));
        index.setLayoutParams(indexParams);
        item.addView(index);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameView.setTextColor(Color.parseColor("#F7FBFC"));
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setLineSpacing(Math.round(2 * density), 1f);
        details.addView(nameView);

        TextView scheduleView = new TextView(this);
        String scheduleText = getSlotEnglishText(slotId)
                + "  ·  "
                + formatAlarmTime(alarmTime);
        scheduleView.setText(scheduleText);
        scheduleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        scheduleView.setTextColor(Color.parseColor("#B8EEE8"));
        scheduleView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        LinearLayout.LayoutParams scheduleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        scheduleParams.topMargin = Math.round(5 * density);
        scheduleView.setLayoutParams(scheduleParams);
        details.addView(scheduleView);

        TextView foodView = new TextView(this);
        String foodText = beforeFood
                ? "খাবার আগে  ·  Before food"
                : "খাবার পরে  ·  After food";
        foodView.setText(foodText);
        foodView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        foodView.setTextColor(Color.parseColor(
                beforeFood ? "#704400" : "#075B43"
        ));
        foodView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        foodView.setBackgroundResource(
                beforeFood
                        ? R.drawable.tag_before_food
                        : R.drawable.tag_after_food
        );
        foodView.setPadding(
                Math.round(10 * density),
                Math.round(5 * density),
                Math.round(10 * density),
                Math.round(5 * density)
        );
        LinearLayout.LayoutParams foodParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        foodParams.topMargin = Math.round(9 * density);
        foodView.setLayoutParams(foodParams);
        details.addView(foodView);

        item.addView(details);
        item.setContentDescription(name + ", " + scheduleText + ", " + foodText);
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return item;
    }

    private void animateEntrance() {
        View panel = findViewById(R.id.glassPanel);
        View dismiss = findViewById(R.id.btnDismiss);
        View icon = findViewById(R.id.reminderIcon);

        PathInterpolator ease = new PathInterpolator(0.20f, 0.80f, 0.20f, 1.00f);

        panel.setAlpha(0f);
        panel.setTranslationY(dpToPx(26));
        panel.setScaleX(0.975f);
        panel.setScaleY(0.975f);
        panel.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(460)
                .setInterpolator(ease)
                .start();

        dismiss.setAlpha(0f);
        dismiss.setTranslationY(dpToPx(18));
        dismiss.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(90)
                .setDuration(420)
                .setInterpolator(ease)
                .start();

        PropertyValuesHolder scaleX =
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f, 1f);
        PropertyValuesHolder scaleY =
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f, 1f);

        iconPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                icon,
                scaleX,
                scaleY
        );
        iconPulseAnimator.setDuration(1800);
        iconPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        iconPulseAnimator.setStartDelay(500);
        iconPulseAnimator.start();
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

    private String getSlotIcon(String slotId) {
        if (ReminderPayload.MULTIPLE.equals(slotId)) return "◆";
        if (slotId == null) return "◆";
        switch (slotId) {
            case "morning":   return "☼";
            case "afternoon": return "☀";
            case "evening":   return "◒";
            case "night":     return "☾";
            default:          return "◆";
        }
    }

    private int getSlotAccentColor(String slotId) {
        if (slotId == null) return Color.parseColor("#9AD9D2");
        switch (slotId) {
            case "morning":   return Color.parseColor("#F4C460");
            case "afternoon": return Color.parseColor("#78C6E5");
            case "evening":   return Color.parseColor("#B8A2F5");
            case "night":     return Color.parseColor("#9BB1E3");
            default:          return Color.parseColor("#9AD9D2");
        }
    }

    private String getSlotDisplayText(String slotId) {
        if (ReminderPayload.MULTIPLE.equals(slotId)) return "Multiple schedules";
        if (slotId == null) return "Medicine schedule";
        switch (slotId) {
            case "morning":   return "সকাল  ·  Morning";
            case "afternoon": return "দুপুর  ·  Afternoon";
            case "evening":   return "বিকাল  ·  Evening";
            case "night":     return "রাত  ·  Night";
            default:          return "Medicine schedule";
        }
    }

    private String getSlotEnglishText(String slotId) {
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

    private static String valueAt(
            ArrayList<String> values,
            int index,
            String fallback
    ) {
        if (values == null || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void dismissReminder() {
        Button dismissButton = findViewById(R.id.btnDismiss);
        dismissButton.setEnabled(false);
        dismissButton.setText("Stopping…");

        Intent stopIntent = new Intent(this, ReminderService.class);
        stopIntent.setAction(ReminderService.ACTION_STOP);
        startService(stopIntent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (iconPulseAnimator != null) iconPulseAnimator.cancel();
        if (reminderReceiverRegistered) {
            unregisterReceiver(reminderStateReceiver);
            reminderReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
