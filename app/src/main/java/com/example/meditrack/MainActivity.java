package com.example.meditrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATIONS = 100;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        // Add the JavaScript bridge for medicine data sync
        webView.addJavascriptInterface(new MediTrackBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // After the page loads, trigger a sync of medicine data to native
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
                                "  } catch(e) { console.log('Sync error: ' + e); }" +
                                "})();",
                        null
                );
            }
        });

        webView.loadUrl("file:///android_asset/MediTrack.html");

        // Handle back button
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

        // Request notification permission (Android 13+)
        requestNotificationPermission();
        ensureOverlayAndFullScreenPermissions();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS);
            }
        }
    }

    private void ensureOverlayAndFullScreenPermissions() {
        // Check "Display over other apps" permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
        }

        // Check full-screen intent permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    /**
     * JavaScript interface — called from the WebView whenever medicine data changes.
     */
    private class MediTrackBridge {

        @JavascriptInterface
        public void onMedicinesChanged(String medicinesJson) {
            Log.d(TAG, "Medicines synced from WebView: " + medicinesJson);
            AlarmScheduler.saveMedicinesAndReschedule(getApplicationContext(), medicinesJson);
        }

        @JavascriptInterface
        public void onSlotTimesChanged(String timesJson) {
            Log.d(TAG, "Slot times synced from WebView: " + timesJson);
            AlarmScheduler.saveTimesAndReschedule(getApplicationContext(), timesJson);
        }
    }
}
