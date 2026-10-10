package com.p2pshare;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.widget.RemoteViews;

/** Shared helpers for the Quick Settings tile and the home-screen widget. */
public final class TetherUi {
    private TetherUi() {}

    public static final int STATE_OFF = 0, STATE_STARTING = 1, STATE_ON = 2, STATE_ERROR = 3;

    public static int state() {
        if (!P2pService.isActive) return STATE_OFF;
        String err = P2pService.currentError;
        if (err != null && !err.isEmpty()) return STATE_ERROR;
        return P2pService.isRunning ? STATE_ON : STATE_STARTING;
    }

    public static boolean hasPermissions(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return false;
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            return false;
        return true;
    }

    public static Intent serviceIntent(Context c, boolean start) {
        Intent i = new Intent(c, P2pService.class);
        i.setAction(start ? P2pService.ACTION_START : P2pService.ACTION_STOP);
        return i;
    }

    /** Ask the tile and all widgets to redraw. Safe to call from anywhere. */
    public static void refresh(Context c) {
        Context app = c.getApplicationContext();
        try {
            TileService.requestListeningState(app, new ComponentName(app, TetherTileService.class));
        } catch (Throwable ignored) {}
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(app);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(app, TetherWidgetProvider.class));
            if (ids != null && ids.length > 0) updateWidgets(app, mgr, ids);
        } catch (Throwable ignored) {}
    }

    static void updateWidgets(Context c, AppWidgetManager mgr, int[] ids) {
        int st = state();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_tether);
        boolean on = st == STATE_ON || st == STATE_STARTING;
        rv.setInt(R.id.widget_root, "setBackgroundResource",
                on ? R.drawable.widget_bg_on : R.drawable.widget_bg_off);
        int label = st == STATE_ON ? R.string.state_on
                : st == STATE_STARTING ? R.string.state_starting
                : st == STATE_ERROR ? R.string.state_error : R.string.state_off;
        rv.setTextViewText(R.id.widget_state, c.getString(label));

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi;
        if (on || st == STATE_ERROR) {
            // Active -> tap stops it.
            pi = PendingIntent.getService(c, 1, serviceIntent(c, false), flags);
        } else if (hasPermissions(c)) {
            // Inactive -> tap starts the foreground service directly (like the hotspot tile).
            pi = PendingIntent.getForegroundService(c, 2, serviceIntent(c, true), flags);
        } else {
            // First run: open the app so the user can grant permissions.
            pi = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class), flags);
        }
        rv.setOnClickPendingIntent(R.id.widget_root, pi);
        mgr.updateAppWidget(ids, rv);
    }
}
