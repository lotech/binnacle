package app.binnacle;

import android.os.Bundle;

/** A source of car data: the real CAN feed or the demo simulator. */
interface Feed {
    void start();
    void stop();

    /** Receives data on the main thread. */
    interface Sink {
        void onData(int code, Bundle data);
        /** null = feed healthy; otherwise a short problem description for the UI. */
        void onStatus(String problem);
    }
}
