package com.example.meditrack;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class ReminderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen and turn on screen
        setupLockScreenFlags();

        setContentView(R.layout.activity_reminder);

        // Read intent extras
        String slotId = getIntent().getStringExtra("slot_id");
        ArrayList<String> medNames = getIntent().getStringArrayListExtra("med_names");
        ArrayList<String> medFoods = getIntent().getStringArrayListExtra("med_foods");

        if (medNames == null || medNames.isEmpty()) {
            finish();
            return;
        }

        // Set slot label
        TextView slotLabel = findViewById(R.id.reminderSlot);
        slotLabel.setText(getSlotDisplayText(slotId));

        // Build medicine list dynamically
        LinearLayout container = findViewById(R.id.medicineListContainer);
        for (int i = 0; i < medNames.size(); i++) {
            String food = (medFoods != null && i < medFoods.size()) ? medFoods.get(i) : "before";
            container.addView(createMedicineItem(medNames.get(i), food));
        }

        // Dismiss button
        Button btnDismiss = findViewById(R.id.btnDismiss);
        btnDismiss.setOnClickListener(v -> dismissReminder());

        // Block back button — force use of Dismiss button
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally do nothing
            }
        });
    }

    private void setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private LinearLayout createMedicineItem(String name, String food) {
        float dp = getResources().getDisplayMetrics().density;

        // Outer container
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundResource(R.drawable.reminder_med_item_bg);
        int padH = (int) (16 * dp);
        int padV = (int) (14 * dp);
        item.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.bottomMargin = (int) (8 * dp);
        item.setLayoutParams(itemParams);

        // Pill emoji
        TextView icon = new TextView(this);
        icon.setText("💊");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        iconParams.setMarginEnd((int) (12 * dp));
        icon.setLayoutParams(iconParams);
        item.addView(icon);

        // Name + food tag column
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // Medicine name
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameView.setTextColor(Color.parseColor("#1A2B2B"));
        nameView.setTypeface(null, Typeface.BOLD);
        col.addView(nameView);

        // Food timing tag
        TextView foodTag = new TextView(this);
        boolean isBefore = "before".equals(food);
        foodTag.setText(isBefore ? "🍽️ খাবার আগে" : "🥄 খাবার পরে");
        foodTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        foodTag.setTypeface(null, Typeface.BOLD);
        foodTag.setTextColor(Color.parseColor(isBefore ? "#92400E" : "#065F46"));
        foodTag.setBackgroundResource(isBefore ? R.drawable.tag_before_food : R.drawable.tag_after_food);
        int tagPadH = (int) (10 * dp);
        int tagPadV = (int) (4 * dp);
        foodTag.setPadding(tagPadH, tagPadV, tagPadH, tagPadV);

        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tagParams.topMargin = (int) (5 * dp);
        foodTag.setLayoutParams(tagParams);
        col.addView(foodTag);

        item.addView(col);
        return item;
    }

    private String getSlotDisplayText(String slotId) {
        if (slotId == null) return "";
        switch (slotId) {
            case "morning":   return "🌅 সকাল — Morning";
            case "afternoon": return "☀️ দুপুর — Afternoon";
            case "evening":   return "🌆 বিকাল — Evening";
            case "night":     return "🌙 রাত — Night";
            default: return slotId;
        }
    }

    private void stopReminderService() {
        Intent stopIntent = new Intent(this, ReminderService.class);
        stopIntent.setAction(ReminderService.ACTION_STOP);
        startService(stopIntent);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ReminderService.NOTIFICATION_ID);
        }
    }

    private void dismissReminder() {
        stopReminderService();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stops alarm when user presses home or swipes away
        stopReminderService();
        finish();
    }
}
