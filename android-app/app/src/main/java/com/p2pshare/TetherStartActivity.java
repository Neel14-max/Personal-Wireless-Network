package com.p2pshare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible launcher used by the Quick Settings tile and the widget.
 * Running from a real activity guarantees Android lets us start the foreground
 * service and show permission prompts. It starts sharing, then closes at once.
 */
public class TetherStartActivity extends Activity {
    private static final int REQ = 77;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TetherUi.hasPermissions(this)) {
            go();
            return;
        }
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(perms.toArray(new String[0]), REQ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (TetherUi.hasPermissions(this)) go();
        else {
            Toast.makeText(this, "Allow Location / Nearby devices to use Tether Direct",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void go() {
        Intent i = TetherUi.serviceIntent(this, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        TetherUi.refresh(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
