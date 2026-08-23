package com.example.meditrack;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATIONS = 100;

    private WebView webView;
    private boolean activityResumed;
    private boolean notificationRequestPending;
    private boolean exactAlarmRequestOffered;
    private boolean fullScreenSettingsOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new MediTrackBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Re-send the persisted WebView state after every full page load.
                view.evaluateJavascript(
                        "(function() {" +
                                "  try {" +
                                "    var s = localStorage.getItem('meditrack_state');" +
                                "    if (s) {" +
                                "      var parsed = JSON.parse(s);" +
                                "      if (parsed.medicines) {" +
                                "        Android.onMedicinesChanged(JSON.stringify(parsed.medicines));" +
                                "      }" +
                                "    }" +
                                "    var times = localStorage.getItem('meditrack_slot_times');" +
                                "    if (times) Android.onSlotTimesChanged(times);" +
                                "  } catch(e) { console.log('Sync error: ' + e); }" +
                                "})();",
                        null
                );
            }
        });

        webView.loadUrl("file:///android_asset/MediTrack.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;

        // Exact-alarm access can be granted or revoked while the app is outside this Activity.
        AlarmScheduler.scheduleAllAlarms(getApplicationContext());

        if (!notificationRequestPending && webView != null) {
            webView.postDelayed(this::ensureSpecialAlarmAccess, 250L);
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {
            notificationRequestPending = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            notificationRequestPending = false;
            if (webView != null) {
                webView.postDelayed(this::ensureSpecialAlarmAccess, 250L);
            }
        }
    }

    private void ensureSpecialAlarmAccess() {
        if (!activityResumed || isFinishing() || isDestroyed()) return;
        if (!AlarmScheduler.hasAnyMedicineDoses(getApplicationContext())) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager =
                    (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null
                    && !alarmManager.canScheduleExactAlarms()
                    && !exactAlarmRequestOffered) {
                exactAlarmRequestOffered = true;
                showExactAlarmRationale();
                return;
            }
        }

        ensureFullScreenIntentAccess();
    }

    private void showExactAlarmRationale() {
        new AlertDialog.Builder(this)
                .setTitle("Allow medicine alarms")
                .setMessage(
                        "MediTrack needs Alarms & reminders access so medicine alerts "
                                + "ring at the exact times you choose, even when the app is closed."
                )
                .setPositiveButton("Continue", (dialog, which) ->
                        openExactAlarmSettings()
                )
                .setNegativeButton("Not now", (dialog, which) ->
                        ensureFullScreenIntentAccess()
                )
                .setOnCancelListener(dialog -> ensureFullScreenIntentAccess())
                .show();
    }

    private void openExactAlarmSettings() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Exact-alarm settings are unavailable", error);
            ensureFullScreenIntentAccess();
        }
    }

    private void ensureFullScreenIntentAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || fullScreenSettingsOpened) {
            return;
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.canUseFullScreenIntent()) return;

        fullScreenSettingsOpened = true;
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Full-screen intent settings are unavailable", error);
        }
    }

    /**
     * JavaScript interface called whenever PWA medicine data or slot defaults change.
     */
    private class MediTrackBridge {

        @JavascriptInterface
        public void onMedicinesChanged(String medicinesJson) {
            Log.d(TAG, "Medicines synced from WebView");
            AlarmScheduler.saveMedicinesAndReschedule(
                    getApplicationContext(),
                    medicinesJson
            );
            runOnUiThread(() -> {
                if (!notificationRequestPending) ensureSpecialAlarmAccess();
            });
        }

        @JavascriptInterface
        public void onSlotTimesChanged(String timesJson) {
            Log.d(TAG, "Slot defaults synced from WebView");
            AlarmScheduler.saveTimesAndReschedule(
                    getApplicationContext(),
                    timesJson
            );
        }
    }
}
