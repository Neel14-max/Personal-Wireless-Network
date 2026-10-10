package com.p2pshare;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget: single button that starts/stops Tether Direct. */
public class TetherWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        TetherUi.updateWidgets(context, mgr, ids);
    }
}
