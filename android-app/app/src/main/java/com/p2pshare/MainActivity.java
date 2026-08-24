package com.p2pshare;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Switch switchMain;
    private TextView textStatus, textDetails;
    private Button buttonCopy;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable uiUpdater;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean v : result.values()) if (!v) { allGranted = false; break; }
                textStatus.setText(allGranted ? "Permissions OK. Ready." : "Some permissions denied.");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContextHolder.init(this);

        // Build dark UI programmatically (no XML dependency issues)
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 80, 48, 48);
        root.setBackgroundColor(Color.parseColor("#0D1117"));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("TetherDirect");
        title.setTextColor(Color.parseColor("#58A6FF"));
        title.setTextSize(32);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpTitle.bottomMargin = 8;
        root.addView(title, lpTitle);

        TextView subtitle = new TextView(this);
        subtitle.setText("Share your phone's internet\nNo root  ·  No USB  ·  No hotspot");
        subtitle.setTextColor(Color.parseColor("#8B949E"));
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSub.bottomMargin = 48;
        root.addView(subtitle, lpSub);

        switchMain = new Switch(this);
        switchMain.setThumbTintList(getResources().getColorStateList(android.R.color.holo_blue_light));
        LinearLayout.LayoutParams lpSw = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSw.bottomMargin = 24;
        root.addView(switchMain, lpSw);

        textStatus = new TextView(this);
        textStatus.setText("Tap switch to start sharing");
        textStatus.setTextColor(Color.parseColor("#C9D1D9"));
        textStatus.setTextSize(16);
        textStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpStatus = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpStatus.bottomMargin = 24;
        root.addView(textStatus, lpStatus);

        // Details card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 24, 32, 24);
        card.setBackgroundColor(Color.parseColor("#161B22"));
        LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCard.bottomMargin = 16;
        root.addView(card, lpCard);

        TextView cardTitle = new TextView(this);
        cardTitle.setText("Connection Info");
        cardTitle.setTextColor(Color.parseColor("#58A6FF"));
        cardTitle.setTextSize(13);
        cardTitle.setTypeface(null, Typeface.BOLD);
        card.addView(cardTitle);

        textDetails = new TextView(this);
        textDetails.setText("Start sharing to see details");
        textDetails.setTextColor(Color.parseColor("#8B949E"));
        textDetails.setTextSize(12);
        textDetails.setTypeface(Typeface.MONOSPACE);
        textDetails.setLineSpacing(4, 1.3f);
        LinearLayout.LayoutParams lpDetails = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpDetails.topMargin = 16;
        card.addView(textDetails, lpDetails);

        buttonCopy = new Button(this);
        buttonCopy.setText("Copy Info");
        buttonCopy.setBackgroundColor(Color.parseColor("#21262D"));
        buttonCopy.setTextColor(Color.parseColor("#C9D1D9"));
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBtn.bottomMargin = 32;
        root.addView(buttonCopy, lpBtn);

        TextView warning = new TextView(this);
        warning.setText("Keep Wi-Fi ON and Mobile Data ON while sharing.");
        warning.setTextColor(Color.parseColor("#F85149"));
        warning.setTextSize(11);
        warning.setGravity(Gravity.CENTER);
        root.addView(warning);

        scroll.addView(root);
        setContentView(scroll);

        // Events
        buttonCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("P2P", textDetails.getText()));
            buttonCopy.setText("Copied!");
            handler.postDelayed(() -> buttonCopy.setText("Copy Info"), 2000);
        });

        switchMain.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) startService(); else stopService();
        });

        requestPermissions();
        startUiUpdate();
    }

    private void requestPermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_WIFI_STATE);
        perms.add(Manifest.permission.CHANGE_WIFI_STATE);
        perms.add(Manifest.permission.INTERNET);
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        String[] needed = perms.toArray(new String[0]);
        permLauncher.launch(needed);
    }

    private void startService() {
        Intent intent = new Intent(this, P2pService.class);
        intent.setAction(P2pService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        textStatus.setText("Starting...");
    }

    private void stopService() {
        Intent intent = new Intent(this, P2pService.class);
        intent.setAction(P2pService.ACTION_STOP);
        startService(intent);
        textStatus.setText("Stopped");
        textDetails.setText("");
        switchMain.setChecked(false);
    }

    private void startUiUpdate() {
        uiUpdater = new Runnable() {
            @Override
            public void run() {
                if (P2pService.currentError != null && !P2pService.currentError.isEmpty()) {
                    textStatus.setText("ERROR");
                    textDetails.setText(
                        "Stage: Wi-Fi Direct / P2P\n\n" +
                        P2pService.currentError +
                        "\n\nChecklist:\n" +
                        "• Wi-Fi ON\n" +
                        "• Mobile data ON\n" +
                        "• Nearby devices permission ALLOWED\n" +
                        "• Turn OFF your phone's Hotspot\n" +
                        "• Turn OFF VPN temporarily"
                    );
                } else if (P2pService.currentGroupName != null && !P2pService.currentGroupName.isEmpty()) {
                    switchMain.setChecked(true);
                    textStatus.setText(P2pService.isRunning ? "ACTIVE - Sharing Data" : "P2P GROUP READY");

                    String pass = P2pService.currentGroupPass;
                    if (pass == null || pass.isEmpty()) pass = "Not provided by Android";

                    textDetails.setText(
                        "SHARING IS ON\n\n" +
                        "On your computer:\n" +
                        "  1. Open Wi-Fi settings\n" +
                        "  2. Connect to this network:\n\n" +
                        "Network name:\n  " + P2pService.currentGroupName + "\n\n" +
                        "Wi-Fi password:\n  " + pass + "\n\n" +
                        "  3. Open the TetherDirect app on the\n" +
                        "     computer and click Connect.\n\n" +
                        "Keep this screen open. Wi-Fi and Mobile Data\n" +
                        "must stay ON."
                    );
                } else if (!switchMain.isChecked()) {
                    textStatus.setText("Tap switch to start sharing");
                    textDetails.setText("Start sharing to show the Wi-Fi Direct SSID and password.");
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(uiUpdater, 2000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(uiUpdater);
    }
}