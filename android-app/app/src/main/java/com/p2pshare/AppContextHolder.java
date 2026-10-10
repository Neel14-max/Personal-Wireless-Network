package com.p2pshare;

import android.content.Context;

public final class AppContextHolder {
    private static Context context;
    private AppContextHolder() {}

    public static void init(Context c) {
        context = c.getApplicationContext();
    }

    public static Context get() {
        return context;
    }
}
