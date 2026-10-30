package app.binnacle.logger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Answers Binnacle's question "are you recording?" so the dash can always show that a logger is
 * installed and whether it is recording (it must never run unnoticed). Only apps holding the
 * signature permission CONTROL_LOGGER can ask, and only they receive the answer.
 */
public class StateReceiver extends BroadcastReceiver {
    static final String ACTION_QUERY = "app.binnacle.logger.QUERY";
    static final String ACTION_STATE = "app.binnacle.logger.STATE";
    static final String PERMISSION = "app.binnacle.permission.CONTROL_LOGGER";

    @Override public void onReceive(Context c, Intent intent) {
        if (ACTION_QUERY.equals(intent.getAction())) announce(c, CaptureService.running);
    }

    /**
     * Tells Binnacle whether recording is on (on start, on stop, and when asked). No fixed package:
     * the dash may be installed as app.binnacle or under another id (e.g. com.xbs.launcher on the
     * owner's unit); the permission limits receivers to same-key apps.
     */
    static void announce(Context c, boolean running) {
        c.sendBroadcast(new Intent(ACTION_STATE).putExtra("running", running), PERMISSION);
    }
}
