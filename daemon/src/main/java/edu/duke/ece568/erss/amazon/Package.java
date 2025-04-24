package edu.duke.ece568.erss.amazon;

import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.APack;

public class Package {
    public static final String PROCESSING = "processing";
    public static final String PROCESSED = "processed";
    public static final String PACKING = "packing";
    public static final String PACKED = "packed";
    public static final String LOADING = "loading";
    public static final String LOADED = "loaded";
    public static final String DELIVERING = "delivering";
    public static final String DELIVERED = "delivered";
    public static final String ERROR = "error";

    private long id;
    private int whID;
    private int truckID;
    private Destination destination;
    private String status;
    private APack pack;
    private String upsName;

    public Package(long id, int whID, APack pack) {
        this.id = id;
        this.whID = whID;
        this.pack = pack;
        this.truckID = -1;
        this.destination = new SQL().queryPackageDest(id);
        this.upsName = new SQL().queryUPSName(id);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getWhID() {
        return whID;
    }

    public void setWhID(int whID) {
        this.whID = whID;
    }

    public int getTruckID() {
        return truckID;
    }

    public void setTruckID(int truckID) {
        this.truckID = truckID;
    }

    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    public int getDestX() {
        return destination.getX();
    }

    public int getDestY() {
        return destination.getY();
    }

    public String getStatus() {
        return status;
    }

    public APack getPack() {
        return pack;
    }

    public String getUpsName() {
        return upsName;
    }

    /**
     * This function will update the status of current package, and also write the statue into database.
     * @param status latest status
     */
    public void setStatus(String status){
        this.status = status;
        // write the result into DB
        new SQL().updateStatus(this.id, this.status);
    }
}
