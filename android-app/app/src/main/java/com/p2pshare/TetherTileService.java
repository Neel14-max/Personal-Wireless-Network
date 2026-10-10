package com.p2pshare;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap to start/stop Tether Direct, like the Hotspot tile. */
public class TetherTileService extends TileService {

    @Override public void onStartListening() { render(); }
    @Override public void onTileAdded() { render(); }

    @Override
    public void onClick() {
        int st = TetherUi.state();
        if (st == TetherUi.STATE_OFF) {
            // Launch via an invisible activity so Android always allows the foreground service.
            openStarter();
        } else if (st == TetherUi.STATE_STARTING) {
            // Still starting: ignore extra taps so we don't cancel it by accident.
        } else {
            startService(TetherUi.serviceIntent(this, false));
        }
        render();
    }

    private void openStarter() {
        Intent i = new Intent(this, TetherStartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        int st = TetherUi.state();
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tether));
        t.setLabel(getString(R.string.tile_label));
        t.setState(st == TetherUi.STATE_OFF || st == TetherUi.STATE_ERROR
                ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            String err = P2pService.currentError;
            if (st == TetherUi.STATE_ERROR && err != null && !err.isEmpty()) {
                t.setSubtitle(err.length() > 40 ? err.substring(0, 40) + "…" : err);
            } else t.setSubtitle(getString(st == TetherUi.STATE_ON ? R.string.state_on
                    : st == TetherUi.STATE_STARTING ? R.string.state_starting
                    : st == TetherUi.STATE_ERROR ? R.string.state_error : R.string.state_off));
        }
        t.updateTile();
    }
}
