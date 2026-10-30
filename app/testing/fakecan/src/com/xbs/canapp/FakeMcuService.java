package com.xbs.canapp;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.util.Log;

import java.util.Arrays;

/**
 * Emulator-only stand-in for the stock IMcuService. Accepts register (1) / unregister (2), then
 * streams Bundles shaped like the real capture. Logs EVERY transaction code it receives under tag
 * FakeCan, so a test can assert the dash only ever sent 1 and 2 ("FakeCan: VIOLATION" otherwise).
 */
public class FakeMcuService extends Service {
    private static final String TAG = "FakeCan";
    private final Handler h = new Handler(Looper.getMainLooper());
    private Messenger client;
    private int t;

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            Log.i(TAG, "txn " + code);
            if (code != 1 && code != 2) { Log.e(TAG, "VIOLATION: txn " + code); return false; }
            data.enforceInterface("com.mcu.iinterface.aidl.IMcuService");
            Messenger m = data.readInt() != 0 ? Messenger.CREATOR.createFromParcel(data) : null;
            if (code == 1) {
                Log.i(TAG, "register codes " + Arrays.toString(data.createIntArray()));
                client = m;
                h.removeCallbacks(tick);
                h.post(tick);
            } else {
                client = null;
            }
            reply.writeNoException();
            reply.writeInt(1);
            return true;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (client == null) return;
            t++;
            int speed = (t / 5) % 80;
            Bundle b10 = new Bundle();
            b10.putInt("carspeed", speed); b10.putString("speedunit", "KM/H");
            b10.putInt("speedlimit", 50); b10.putBoolean("speedlimitsw", true); b10.putInt("curentergyval", 20);
            send(10, b10);
            Bundle b11 = new Bundle();
            b11.putInt("batterylevel", 64); b11.putInt("gear", 3); b11.putInt("totalmail", 54790);
            b11.putString("distunit", "km"); b11.putString("enginetemp", "21.5℃"); b11.putInt("jhmail", 31600);
            send(11, b11);
            Bundle b9 = new Bundle();
            b9.putInt("leftlight", 2); b9.putInt("rightlight", (t / 4) % 2 == 0 ? 1 : 2);
            b9.putBoolean("nearlight", true); b9.putBoolean("farlight", true); b9.putInt("carcolor", 4);
            b9.putString("driverwarning", "garbage");     // wrong type on purpose
            send(9, b9);
            Bundle b12 = new Bundle();
            b12.putInt("lftpmsVal", 64000); b12.putString("tpmsUnit", "bar");
            send(12, b12);
            send(77, null);                               // unknown code, no data
            h.postDelayed(this, 200);
        }
    };

    private void send(int code, Bundle b) {
        try {
            Message m = Message.obtain(null, code);
            if (b != null) m.setData(b);
            client.send(m);
        } catch (Exception e) {
            Log.w(TAG, "client gone", e);
            client = null;
        }
    }

    @Override public IBinder onBind(Intent i) { return binder; }
}
