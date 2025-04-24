package edu.duke.ece568.erss.amazon;

import java.util.Timer;

public class Request {
    private final Timer timer;
    private final long packageID;

    public Request(Timer timer, long packageID) {
        this.timer = timer;
        this.packageID = packageID;
    }

    public void stopTimer(){
        timer.cancel();
    }

    public long getPackageID() {
        return packageID;
    }
}
