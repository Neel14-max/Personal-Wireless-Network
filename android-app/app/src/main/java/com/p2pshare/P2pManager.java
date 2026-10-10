package com.p2pshare;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Wi-Fi Direct manager.
 *
 * Important: createGroup() succeeding does NOT guarantee that requestGroupInfo()
 * will immediately return a non-null WifiP2pGroup. Android creates the group
 * asynchronously. We therefore poll requestGroupInfo() for a short period and
 * only report an error after the group has genuinely failed to appear.
 */
public class P2pManager {

    private static final String TAG = "P2pManager";
    private static final long RETRY_MS = 500L;
    private static final int MAX_RETRIES = 24; // 12 seconds

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pGroup currentGroup;
    private Listener listener;
    private int groupInfoAttempts = 0;
    private boolean waitingForGroup = false;

    public interface Listener {
        void onGroupCreated(WifiP2pGroup group);
        void onGroupRemoved();
        void onError(String message);
    }

    public P2pManager(Context context) {
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, Looper.getMainLooper(), null);
    }

    public void setListener(Listener l) { this.listener = l; }

    private boolean hasP2pPermission(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void createGroup() {
        if (manager == null || channel == null) {
            reportError("Wi-Fi Direct manager/channel is unavailable");
            return;
        }

        Context context = AppContextHolder.get();
        if (context != null && !hasP2pPermission(context)) {
            reportError("Nearby Wi-Fi permission is not granted. Open app permissions and allow Nearby devices.");
            return;
        }

        waitingForGroup = true;
        groupInfoAttempts = 0;

        // If Android still has an old P2P group, remove it first. This avoids
        // the BUSY state and makes repeated starts much more reliable.
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Old P2P group removed; creating a fresh group");
                actuallyCreateGroup();
            }

            @Override
            public void onFailure(int reason) {
                // NO_SERVICE / group-not-present is normal on a fresh start.
                Log.d(TAG, "No old group to remove (reason=" + reason + "); creating group");
                actuallyCreateGroup();
            }
        });
    }

    private void actuallyCreateGroup() {
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup success; waiting for Android to publish group info");
                groupInfoAttempts = 0;
                requestGroupInfoWithRetry();
            }

            @Override
            public void onFailure(int reason) {
                waitingForGroup = false;
                String msg = "Failed to create Wi-Fi Direct group (code: " + reason + ")";
                Log.e(TAG, msg);
                reportError(msg);
            }
        });
    }

    private void requestGroupInfoWithRetry() {
        if (!waitingForGroup) return;

        groupInfoAttempts++;
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (group != null && group.getNetworkName() != null
                        && !group.getNetworkName().isEmpty()) {
                    waitingForGroup = false;
                    currentGroup = group;
                    String name = group.getNetworkName();
                    String pass = group.getPassphrase();
                    Log.d(TAG, "P2P GROUP READY: " + name + " / pass=" + pass);
                    if (listener != null) listener.onGroupCreated(group);
                    return;
                }

                if (groupInfoAttempts < MAX_RETRIES) {
                    Log.d(TAG, "Group info not ready yet (attempt " + groupInfoAttempts + "/" + MAX_RETRIES + ")");
                    handler.postDelayed(() -> requestGroupInfoWithRetry(), RETRY_MS);
                } else {
                    waitingForGroup = false;
                    reportError("Wi-Fi Direct group was created, but Android did not publish group information after 12 seconds. Keep Wi-Fi ON, disable any active hotspot/VPN, then retry.");
                }
            }
        });
    }

    private void reportError(String message) {
        if (listener != null) listener.onError(message);
    }

    public void removeGroup() {
        waitingForGroup = false;
        handler.removeCallbacksAndMessages(null);
        if (manager == null || channel == null) {
            if (listener != null) listener.onGroupRemoved();
            return;
        }

        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                currentGroup = null;
                Log.d(TAG, "Group removed");
                if (listener != null) listener.onGroupRemoved();
            }

            @Override
            public void onFailure(int reason) {
                currentGroup = null;
                Log.d(TAG, "No group to remove / remove returned " + reason);
                if (listener != null) listener.onGroupRemoved();
            }
        });
    }

    public WifiP2pGroup getGroup() { return currentGroup; }
    public String getInterfaceName() { return "p2p0"; }
}
