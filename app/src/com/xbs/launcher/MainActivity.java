package com.xbs.launcher;

import app.binnacle.DashActivity;

/**
 * The dash activity under the class name the stock CAN app (com.xbs.canapp, r1.e "DoorMsg")
 * treats as its own launcher: it skips its door-open popup when the top activity is
 * com.xbs.launcher/com.xbs.launcher.MainActivity (or the stock com.xbs.launcher1). That package
 * isn't installed on the unit. Build with APP_ID=com.xbs.launcher (app/build.sh) to get the
 * matching package name; with the default APP_ID the class name alone changes nothing.
 * No behaviour of its own: everything is in DashActivity.
 */
public class MainActivity extends DashActivity {
}
