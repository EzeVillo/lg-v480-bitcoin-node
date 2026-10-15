package ar.eze.v480wifiautoconnect;

import android.app.Service;
import android.content.Intent;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WifiReconnectService extends Service {
    private static final String TAG = "V480Wifi";
    private static final long CONNECTED_CHECK_MS = 60_000L;
    private static final long RETRY_MS = 10_000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING.compareAndSet(false, true)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    reconnectLoop();
                }
            }, "wifi-reconnect").start();
        }
        return START_STICKY;
    }

    private void reconnectLoop() {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "V480Wifi:reconnect");
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);

            Thread.sleep(8_000L);
            int attempt = 0;
            while (!Thread.currentThread().isInterrupted()) {
                if (isConnected(wifiManager)) {
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    Thread.sleep(CONNECTED_CHECK_MS);
                    attempt = 0;
                    continue;
                }

                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(30_000L);
                }
                if (!wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(true);
                    Thread.sleep(3_000L);
                }

                WifiConfiguration network = selectSavedNetwork(
                        wifiManager.getConfiguredNetworks());
                if (network != null) {
                    Log.i(TAG, "Selecting saved Wi-Fi network, attempt " + attempt);
                    wifiManager.enableNetwork(network.networkId, true);
                    wifiManager.reconnect();
                } else {
                    Log.w(TAG, "No saved Wi-Fi network is available yet");
                }
                attempt++;
                Thread.sleep(RETRY_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            Log.e(TAG, "Wi-Fi reconnect helper failed", error);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            RUNNING.set(false);
        }
    }

    private static boolean isConnected(WifiManager wifiManager) {
        WifiInfo info = wifiManager.getConnectionInfo();
        return info != null
                && info.getNetworkId() >= 0
                && info.getSupplicantState() == SupplicantState.COMPLETED;
    }

    private static WifiConfiguration selectSavedNetwork(List<WifiConfiguration> networks) {
        if (networks == null || networks.isEmpty()) {
            return null;
        }
        WifiConfiguration selected = null;
        for (WifiConfiguration network : networks) {
            if (selected == null || network.priority > selected.priority) {
                selected = network;
            }
        }
        return selected;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
