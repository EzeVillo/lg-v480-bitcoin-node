package ar.eze.v480wifiautoconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "V480Wifi";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Boot event received; starting Wi-Fi reconnect helper");
        context.startService(new Intent(context, WifiReconnectService.class));
    }
}
