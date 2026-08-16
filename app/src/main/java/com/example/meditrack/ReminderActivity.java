package com.example.meditrack;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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

import java.util.ArrayList;

public class ReminderActivity extends AppCompatActivity {

    private ObjectAnimator iconPulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Preserve the existing lock-screen / wake behaviour.
        setupLockScreenFlags();

        // iOS-like translucent window. Android 12+ gets real blur-behind;
        // older devices keep the translucent material fallback from the XML.
        configureLiquidWindow();

        setContentView(R.layout.activity_reminder);

        String slotId = getIntent().getStringExtra("slot_id");
        ArrayList<String> medNames = getIntent().getStringArrayListExtra("med_names");
        ArrayList<String> medFoods = getIntent().getStringArrayListExtra("med_foods");

        if (medNames == null || medNames.isEmpty()) {
            finish();
            return;
        }

        TextView title = findViewById(R.id.reminderTitle);
        title.setText(medNames.size() == 1
                ? "Time for your medicine"
                : "Time for your medicines");

        TextView reminderIcon = findViewById(R.id.reminderIcon);
        reminderIcon.setText(getSlotIcon(slotId));
        reminderIcon.setTextColor(getSlotAccentColor(slotId));

        TextView slotLabel = findViewById(R.id.reminderSlot);
        slotLabel.setText(getSlotDisplayText(slotId));
        slotLabel.setTextColor(getSlotAccentColor(slotId));

        LinearLayout container = findViewById(R.id.medicineListContainer);
        for (int i = 0; i < medNames.size(); i++) {
            String food = (medFoods != null && i < medFoods.size())
                    ? medFoods.get(i)
                    : "before";
            container.addView(createMedicineItem(medNames.get(i), food));
        }

        Button btnDismiss = findViewById(R.id.btnDismiss);
        btnDismiss.setOnClickListener(v -> dismissReminder());

        animateEntrance();

        // Preserve the existing behaviour: Back cannot silently dismiss the alarm.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally do nothing.
            }
        });
    }

    private void configureLiquidWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.28f;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Real cross-window blur. This can be disabled by Android/GPU policy,
            // so the translucent XML surfaces are deliberately readable without it.
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
            KeyguardManager km =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
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
        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundResource(R.drawable.reminder_med_item_bg);

        int padH = Math.round(18 * dp);
        int padV = Math.round(17 * dp);
        item.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.bottomMargin = Math.round(10 * dp);
        item.setLayoutParams(itemParams);

        TextView icon = new TextView(this);
        icon.setText("◆");
        icon.setTextColor(Color.parseColor("#9AD9D2"));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.reminder_icon_bg);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                Math.round(48 * dp),
                Math.round(48 * dp)
        );
        iconParams.setMarginEnd(Math.round(14 * dp));
        icon.setLayoutParams(iconParams);
        item.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameView.setTextColor(Color.parseColor("#F7FBFC"));
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setLineSpacing(Math.round(2 * dp), 1f);
        col.addView(nameView);

        TextView foodText = new TextView(this);
        boolean isBefore = "before".equals(food);
        foodText.setText(isBefore
                ? "খাবার আগে  ·  Before food"
                : "খাবার পরে  ·  After food");
        foodText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        foodText.setTextColor(Color.parseColor(
                isBefore ? "#FFD48A" : "#9FE3C7"
        ));
        foodText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        LinearLayout.LayoutParams foodParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        foodParams.topMargin = Math.round(7 * dp);
        foodText.setLayoutParams(foodParams);
        col.addView(foodText);

        item.addView(col);
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
                icon, scaleX, scaleY
        );
        iconPulseAnimator.setDuration(1800);
        iconPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        iconPulseAnimator.setStartDelay(500);
        iconPulseAnimator.start();
    }

    private String getSlotIcon(String slotId) {
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
        if (slotId == null) return "";
        switch (slotId) {
            case "morning":   return "সকাল  ·  Morning";
            case "afternoon": return "দুপুর  ·  Afternoon";
            case "evening":   return "বিকাল  ·  Evening";
            case "night":     return "রাত  ·  Night";
            default:          return slotId;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void stopReminderService() {
        Intent stopIntent = new Intent(this, ReminderService.class);
        stopIntent.setAction(ReminderService.ACTION_STOP);
        startService(stopIntent);

        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ReminderService.NOTIFICATION_ID);
        }
    }

    private void dismissReminder() {
        stopReminderService();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (iconPulseAnimator != null) {
            iconPulseAnimator.cancel();
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Preserve existing behaviour: leaving this full-screen activity stops the alarm.
        stopReminderService();
        finish();
    }
}
