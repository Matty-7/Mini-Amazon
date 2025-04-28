package edu.duke.ece568.erss.amazon;

import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.APack;
import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.APurchaseMore;
import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.AProduct;

import java.util.List;

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
    private boolean truckArrived;
    private long upsPackageId;

    public Package(long id, int whID, APack pack) {
        this.id = id;
        this.whID = whID;
        this.pack = pack;
        this.truckID = -1;
        this.truckArrived = false;
        this.destination = new SQL().queryPackageDest(id);
        this.upsName = new SQL().queryUPSName(id);
    }

    public boolean getTruckArrived() {
        return truckArrived;
    }

    public void setTruckArrived(boolean truckArrived) {
        this.truckArrived = truckArrived;
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
     * Get UPS user ID associated with this package
     * @return the UPS user ID as a long (0 if none)
     */
    public long getUpsUserId() {
        if (upsName == null || upsName.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(upsName);
        } catch (NumberFormatException e) {
            // If the upsName is not a valid number, use a hash of the string
            return Math.abs(upsName.hashCode());
        }
    }
    
    /**
     * Check if this package matches the arrived purchase
     * @param buy The purchase more message from World
     * @return true if this package matches the arrived purchase
     */
    public boolean matchesArrival(APurchaseMore buy) {
        // First, check if warehouse ID matches
        if (this.whID != buy.getWhnum()) {
            return false;
        }
        
        // Then check if the products match
        // Get the products from the package's pack
        List<AProduct> packProducts = pack.getThingsList();
        List<AProduct> buyProducts = buy.getThingsList();
        
        // Simple match: just check if the number of products is the same
        // You could implement a more sophisticated matching here if needed
        if (packProducts.size() != buyProducts.size()) {
            return false;
        }
        
        // Check if all products from the purchase match any product in the pack
        // This is a simple check - in a real system you might want more exact matching
        for (AProduct buyProd : buyProducts) {
            boolean found = false;
            for (AProduct packProd : packProducts) {
                // Check if ID matches
                if (buyProd.getId() == packProd.getId()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        
        return true;
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

    public long getUpsPackageId() {
        return upsPackageId;
    }

    public void setUpsPackageId(long upsPackageId) {
        this.upsPackageId = upsPackageId;
    }
}
