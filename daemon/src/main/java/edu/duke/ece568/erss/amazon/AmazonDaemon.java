package edu.duke.ece568.erss.amazon;

import edu.duke.ece568.erss.amazon.proto.AmazonUPSProtocol.*;
import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.*;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.CodedInputStream;

import static edu.duke.ece568.erss.amazon.Utils.recvMsgFrom;
import static edu.duke.ece568.erss.amazon.Utils.sendMsgTo;

/**
 * 1. packageId == shipId (for compatibility with World-sim)
 * 2. World communication is **blocking** (load() returns only when finished loading)
 * 3. UPS communication is **non-blocking**: we return once the command is sent (and acked)
 * 4. Full flow:
 *        purchase(World) -> pack(World) & request_pickup(UPS) -> load(World) & load_ready(UPS)
 *        -> delivery_complete(UPS)
 */
public class AmazonDaemon {

    // ---------------------- connection constants -----------------------
    private static final String WORLD_HOST = 
        System.getenv().getOrDefault("WORLD_HOST", "localhost");
    private static final int    WORLD_PORT = 23456;

    private static final String UPS_HOST = 
        System.getenv().getOrDefault("UPS_HOST", "localhost");
    private static final int UPS_PORT = 
        Integer.parseInt(System.getenv().getOrDefault("UPS_PORT", "34567"));

    private static final int TIME_OUT_MS     = 3_000;   // resend window (ms) for un-acked msgs

    // ------------------------- runtime state --------------------------
    private InputStream  worldIn;
    private OutputStream worldOut;
    private long worldId = 1;

    // UPS persistent connection fields
    private Socket          upsSocket;
    private InputStream     upsIn;
    private OutputStream    upsOut;

    private long seqNum = 0;                // global monotonically-increasing seq-num

    private DaemonThread         daemonThread;

    private final Map<Long, Package> packageMap = new ConcurrentHashMap<>();
    private final Map<Long, Timer>   requestMap = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor threadPool;
    private final List<AInitWarehouse> warehouses;

    // ---------------------------- ctor -------------------------------
    public AmazonDaemon() throws IOException {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>(30);
        threadPool = new ThreadPoolExecutor(50, 80, 5, TimeUnit.SECONDS, q);
        warehouses = new SQL().queryWHs();
    }

    // -------------------------- bootstrap ----------------------------
    /**
     * Initialise the daemon:
     *  - connect to the World simulator (passing worldId = -1 so the simulator allocates one).
     *  - connect to UPS (persistent connection)
     */
    public void config() throws IOException {
        System.out.println("Daemon is starting...");

        // Connect to World - let it choose / allocate the world-id for this session.
        if (!connectToWorld(worldId)) {
            throw new IOException("Failed to connect to World simulator");
        }
        
        // Connect to UPS with persistent connection
        connectToUPS();
        
        // Start UPS receiver thread
        startUPSReceiver();
    }

    /** Establishes a persistent connection with UPS that will be maintained throughout the lifetime of the daemon */
    private void connectToUPS() throws IOException {
        upsSocket = new Socket(UPS_HOST, UPS_PORT);
        upsIn = upsSocket.getInputStream();
        upsOut = upsSocket.getOutputStream();
        System.out.printf("Connected to UPS at %s:%d%n", UPS_HOST, UPS_PORT);
    }

    /** Starts a dedicated thread that continuously listens for messages from UPS */
    private void startUPSReceiver() {
        new Thread(() -> {
            try {
                while (true) {
                    // Use parseDelimitedFrom to read framed messages
                    UPSToAmazon msg = UPSToAmazon.parseDelimitedFrom(upsIn);
                    if (msg == null) {
                        // Stream EOF or connection closed
                        System.err.println("UPS connection closed!");
                        break;
                    }
                    System.out.println("<- UPS: " + msg);
                    
                    // Cancel resend timers for acked messages
                    for (long ackSeq : msg.getAcksList()) {
                        Timer t = requestMap.remove(ackSeq);
                        if (t != null) t.cancel();
                    }
                    
                    // Process incoming messages
                    for (PickupResp pickup : msg.getPickupRespList()) {
                        setTruckIdOnly(pickup.getOrderId(), pickup.getTruckId());
                    }
                    for (TruckArrived truckArrival : msg.getTruckArrivedList()) 
                        picked(truckArrival.getPackageId(), truckArrival.getTruckId());
                    for (DeliveryComplete delivery : msg.getDeliveryCompleteList()) 
                        delivered(delivery.getPackageId());
                    
                    // Send acknowledgments back to UPS
                    ackUPS(msg);
                }
            } catch (IOException e) {
                System.err.println("Error in UPS receiver thread: " + e);
                // Try to reconnect if the connection fails
                try {
                    System.out.println("Attempting to reconnect to UPS...");
                    connectToUPS();
                    startUPSReceiver();
                } catch (IOException reconnectError) {
                    System.err.println("Failed to reconnect to UPS: " + reconnectError);
                }
            }
        }, "UPS-Receiver").start();
    }

    private void setTruckIdOnly(long pkgId, int truckId) {
        if (!validatePkg(pkgId)) return;
        Package p = packageMap.get(pkgId);
        p.setTruckID(truckId);
        System.out.printf("Set truck_id=%d for pkg=%d based on PickupResp%n", truckId, pkgId);
    }

    private boolean connectToWorld(long worldID) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket(WORLD_HOST, WORLD_PORT);
            worldIn = socket.getInputStream();
            worldOut = socket.getOutputStream();

            // First try with warehouses
            AConnect.Builder connect = AConnect.newBuilder()
                    .setIsAmazon(true)
                    .addAllInitwh(warehouses);
            if (worldID >= 0) connect.setWorldid(worldID);

            if (!sendMsgTo(connect.build(), worldOut)) {
                throw new IOException("Failed to send Connect message to World");
            }

            AConnected.Builder connected = AConnected.newBuilder();
            if (!recvMsgFrom(connected, worldIn)) {
                throw new IOException("Failed to receive Connected response from World");
            }

            String result = connected.getResult();
            System.out.printf("World handshake -> id=%d  result=%s%n",
                    connected.getWorldid(), result);
            
            // If we get an error about warehouses already existing, try connecting without warehouses
            if (result != null && result.contains("warehouse_id") && result.contains("already exists")) {
                System.out.println("Warehouses already exist, reconnecting without creating warehouses...");
                
                // Close existing connection and open a new one
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Error closing socket: " + e);
                }
                
                socket = new Socket(WORLD_HOST, WORLD_PORT);
                worldIn = socket.getInputStream();
                worldOut = socket.getOutputStream();
                
                // Connect without warehouses
                connect = AConnect.newBuilder()
                        .setIsAmazon(true);
                if (worldID >= 0) connect.setWorldid(worldID);
                
                if (!sendMsgTo(connect.build(), worldOut)) {
                    throw new IOException("Failed to send Connect message to World (retry)");
                }
                
                connected = AConnected.newBuilder();
                if (!recvMsgFrom(connected, worldIn)) {
                    throw new IOException("Failed to receive Connected response from World (retry)");
                }
                
                result = connected.getResult();
                System.out.printf("World handshake (retry) -> id=%d  result=%s%n",
                        connected.getWorldid(), result);
            }
            
            return "connected!".equals(result);
        } catch (IOException e) {
            System.err.println("Error connecting to World: " + e);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException closeError) {
                    System.err.println("Error closing socket: " + closeError);
                }
            }
            throw e;
        }
    }

    // ----------------------- main threads ---------------------------
    public void runAll() {
        threadPool.prestartAllCoreThreads();
        runDaemonServer();

        // dedicated reader for async World events
        new Thread(() -> {
            while (true) {
                try {
                    AResponses.Builder resp = AResponses.newBuilder();
                    if (recvMsgFrom(resp, worldIn)) {
                        AResponses builtResp = resp.build();
                        // Print detailed contents of message for debugging
                        System.out.println("<- World FULL RESPONSE: " + builtResp);
                        
                        // Process the message even if it only contains acks
                        if (builtResp.getAcksCount() > 0) {
                            System.out.println("<- World received ACKs: " + builtResp.getAcksList());
                        }
                        
                        // Check if we have actual content (not just ACKs)
                        boolean hasContent = builtResp.getArrivedCount() > 0 ||
                                            builtResp.getReadyCount() > 0 ||
                                            builtResp.getLoadedCount() > 0 ||
                                            builtResp.getErrorCount() > 0 || 
                                            builtResp.getPackagestatusCount() > 0;
                        
                        if (hasContent) {
                            System.out.println("<- World CONTENT RESPONSE received with " + 
                                builtResp.getArrivedCount() + " arrivals, " + 
                                builtResp.getReadyCount() + " ready, " + 
                                builtResp.getLoadedCount() + " loaded");
                            handleWorldResponse(builtResp);
                        }
                    } else {
                        System.err.println("Failed to receive message from World, will retry...");
                        Thread.sleep(1000); // Small delay before retry
                    }
                } catch (IOException e) {
                    System.err.println("Error in World receiver thread: " + e);
                    try {
                        System.out.println("Attempting to reconnect to World...");
                        if (connectToWorld(worldId)) {
                            System.out.println("Successfully reconnected to World");
                        } else {
                            System.err.println("Failed to reconnect to World");
                            // Wait before retrying to avoid rapid reconnection attempts
                            Thread.sleep(5000);
                        }
                    } catch (IOException | InterruptedException reconnectError) {
                        System.err.println("Failed to reconnect to World: " + reconnectError);
                        try {
                            // Wait before retrying to avoid rapid reconnection attempts
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (InterruptedException ie) {
                    System.err.println("World receiver thread interrupted: " + ie);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "World-recv-thread").start();
    }

    private void runDaemonServer() {
        daemonThread = new DaemonThread(pkgId -> {
            System.out.printf("[FrontEnd] new purchase request: pkg=%d%n", pkgId);
            toPurchase(pkgId);
        });
        daemonThread.start();
    }

    // -------------------  World  -> Amazon  -----------------------
    private void handleWorldResponse(AResponses resp) {
        if (resp == null) {
            System.err.println("Warning: Received null response from World");
            return;
        }
        
        System.out.println("<- World: " + resp);
        
        // First acknowledge receipt of this message
        sendAckToWorld(resp);
        
        // Process each type of message
        boolean hasProcessed = false;
        
        if (resp.getArrivedCount() > 0) {
            System.out.println("Processing " + resp.getArrivedCount() + " arrived purchase(s)");
            for (APurchaseMore p : resp.getArrivedList()) {
                purchased(p);
                hasProcessed = true;
            }
        }
        
        if (resp.getReadyCount() > 0) {
            System.out.println("Processing " + resp.getReadyCount() + " ready package(s)");
            for (APacked p : resp.getReadyList()) {
                packed(p.getShipid());
                hasProcessed = true;
            }
        }
        
        if (resp.getLoadedCount() > 0) {
            System.out.println("Processing " + resp.getLoadedCount() + " loaded package(s)");
            for (ALoaded l : resp.getLoadedList()) {
                loaded(l.getShipid());
                hasProcessed = true;
            }
        }
        
        if (resp.getErrorCount() > 0) {
            for (AErr e : resp.getErrorList()) {
                System.err.println("World-err: " + e.getErr() + " (origin seq: " + e.getOriginseqnum() + ")");
                // Cancel the resend timer for the original message
                Timer t = requestMap.remove(e.getOriginseqnum());
                if (t != null) t.cancel();
                hasProcessed = true;
            }
        }

        if (resp.getPackagestatusCount() > 0) {
            System.out.println("Processing " + resp.getPackagestatusCount() + " package status updates");
            for (APackage st : resp.getPackagestatusList()) {
                Package pkg = packageMap.get(st.getPackageid());
                if (pkg != null) {
                    pkg.setStatus(st.getStatus());
                    System.out.println("Updated package " + st.getPackageid() + " status to: " + st.getStatus());
                } else {
                    System.err.println("Warning: Status update for unknown package " + st.getPackageid());
                }
                hasProcessed = true;
            }
        }
        
        // Additional processing for acknowledgements
        if (resp.getAcksCount() > 0) {
            for (long ackSeq : resp.getAcksList()) {
                Timer t = requestMap.remove(ackSeq);
                if (t != null) {
                    t.cancel();
                    System.out.println("Cancelled resend timer for seq " + ackSeq);
                }
            }
        }
        
        if (!hasProcessed && resp.getAcksCount() == 0) {
            System.out.println("Received empty response from World (no content, no acks)");
        }
    }

    private void sendAckToWorld(AResponses resp) {
        if (resp == null) return;
        
        List<Long> seqs = new ArrayList<>();
        resp.getArrivedList()      .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getReadyList()        .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getLoadedList()       .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getErrorList()        .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getPackagestatusList().forEach(v -> seqs.add(v.getSeqnum()));
        if (seqs.isEmpty()) return;

        ACommands.Builder ack = ACommands.newBuilder().addAllAcks(seqs);
        ACommands builtAck = ack.build();
        System.out.println("-> World (ACK): " + builtAck);
        synchronized (worldOut) {
            try {
                if (!sendMsgTo(builtAck, worldOut)) {
                    System.err.println("Failed to send ACK to World");
                }
            } catch (Exception e) {
                System.err.println("Exception when sending ACK to World: " + e);
            }
        }
    }

    // ----------------------- internal ops -------------------------
    private void toPurchase(long pkgId) {
        logStage("purchasing", pkgId);
        threadPool.execute(() -> {
            long seq = nextSeq();
            APurchaseMore.Builder buy = new SQL().queryPackage(pkgId);
            buy.setSeqnum(seq);

            // cache package meta-info
            APack.Builder packProto = APack.newBuilder()
                    .setWhnum(buy.getWhnum())
                    .addAllThings(buy.getThingsList())
                    .setShipid(pkgId)
                    .setSeqnum(seq);
            packageMap.put(pkgId, new Package(pkgId, buy.getWhnum(), packProto.build()));

            ACommands.Builder cmd = ACommands.newBuilder().addBuy(buy);
            sendToWorld(cmd, seq);
        });
    }

    private void toPack(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("packing", pkgId);
        Package p = packageMap.get(pkgId);
        p.setStatus(Package.PACKING);
        threadPool.execute(() -> {
            long seq = nextSeq();
            APack pack = p.getPack().toBuilder().setSeqnum(seq).build();
            ACommands.Builder cmd = ACommands.newBuilder().addTopack(pack);
            sendToWorld(cmd, seq);
        });
    }

    private void toRequestPickup(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("request_pickup", pkgId);

        Package p = packageMap.get(pkgId);
        threadPool.execute(() -> {
            long seq = nextSeq();
            RequestPickup.Builder req = RequestPickup.newBuilder()
                    .setSeqnum(seq)
                    .setUpsUserId(String.valueOf(p.getUpsUserId()))
                    .setOrderId(pkgId)
                    .setWarehouseId(p.getWhID())
                    .setUserDestination(Coordinate.newBuilder()
                            .setX(p.getDestX())
                            .setY(p.getDestY()));
            // convert items
            for (AProduct prod : p.getPack().getThingsList()) {
                req.addItems(ItemInfo.newBuilder()
                        .setItemName(prod.getDescription())
                        .setQuantity(prod.getCount()));
            }
            AmazonToUPS.Builder cmd = AmazonToUPS.newBuilder().addRequestPickup(req);
            sendToUPS(cmd.build(), seq);
        });
    }

    private void toLoadReady(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("load_ready", pkgId);
        threadPool.execute(() -> {
            long seq = nextSeq();
            LoadReady ready = LoadReady.newBuilder()
                    .setSeqnum(seq)
                    .setPackageId(pkgId)
                    .build();
            AmazonToUPS.Builder cmd = AmazonToUPS.newBuilder().addLoadReady(ready);
            sendToUPS(cmd.build(), seq);
        });
    }

    // World-event handlers  
    private void purchased(APurchaseMore buy) {
        System.out.printf("Searching for package with warehouse ID %d (arrived purchase seq=%d)%n", 
                          buy.getWhnum(), buy.getSeqnum());
        System.out.printf("Current packages in map: %s%n", packageMap.keySet());
        
        // Try to find a matching package
        Optional<Package> matchedPkg = packageMap.values().stream()
                .filter(p -> p.matchesArrival(buy))
                .findFirst();
        
        if (matchedPkg.isPresent()) {
            Package p = matchedPkg.get();
            logStage("purchased", p.getId());
            p.setStatus(Package.PROCESSED);
            toRequestPickup(p.getId());  // ask UPS for pickup
            toPack(p.getId());           // concurrently ask World to pack
        } else {
            // No matching package found, create a new one
            System.out.println("No matching package found for arrived purchase, creating new package...");
            
            // Generate a unique package ID (using sequence number)
            long pkgId = nextSeq() + 10000; // Add offset to avoid collisions with request sequence numbers
            
            // Create a packing instruction
            APack.Builder packProto = APack.newBuilder()
                    .setWhnum(buy.getWhnum())
                    .addAllThings(buy.getThingsList())
                    .setShipid(pkgId)
                    .setSeqnum(buy.getSeqnum());
            
            // Create and store package
            packageMap.put(pkgId, new Package(pkgId, buy.getWhnum(), packProto.build()));
            
            logStage("purchased", pkgId);
            packageMap.get(pkgId).setStatus(Package.PROCESSED);
            toRequestPickup(pkgId);  // ask UPS for pickup
            toPack(pkgId);           // concurrently ask World to pack
        }
    }

    private void packed(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("packed", pkgId);
        Package p = packageMap.get(pkgId);
        p.setStatus(Package.PACKED);
    }

    private void picked(long pkgId, int truckId) {
        if (!validatePkg(pkgId)) return;
        logStage("truck_arrived", pkgId);
        Package p = packageMap.get(pkgId);
        p.setTruckID(truckId);
        if (p.getStatus().equals(Package.PACKED)) toLoad(pkgId);
    }

    private void toLoad(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("loading", pkgId);
        Package p = packageMap.get(pkgId);
        p.setStatus(Package.LOADING);
        threadPool.execute(() -> {
            long seq = nextSeq();
            APutOnTruck load = APutOnTruck.newBuilder()
                    .setSeqnum(seq)
                    .setWhnum(p.getWhID())
                    .setTruckid(p.getTruckID())
                    .setShipid(pkgId)
                    .build();
            ACommands.Builder cmd = ACommands.newBuilder().addLoad(load);
            sendToWorld(cmd, seq);
        });
    }

    private void loaded(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("loaded", pkgId);
        Package p = packageMap.get(pkgId);
        p.setStatus(Package.LOADED);
        toLoadReady(pkgId); // notify UPS that package is ready on truck
    }

    private void delivered(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("delivered", pkgId);
        packageMap.remove(pkgId);
    }

    // -------------------- comm helpers -------------------------
    private void sendToWorld(ACommands.Builder cmd, long seq) {
        cmd.setSimspeed(500); // DEBUG: speed-up sim
        ACommands builtCmd = cmd.build();
        System.out.println("-> World: " + builtCmd);
        Timer t = scheduleResend(() -> {
            synchronized (worldOut) {
                sendMsgTo(builtCmd, worldOut);
            }
        });
        requestMap.put(seq, t);
    }

    /** Send a message to UPS over the persistent connection */
    private void sendToUPS(AmazonToUPS cmd, long seq) {
        try {
            byte[] bytes = cmd.toByteArray();  // serialize once
            CodedOutputStream cos = CodedOutputStream.newInstance(upsOut);
            cos.writeUInt32NoTag(bytes.length);
            cos.writeRawBytes(bytes);
            cos.flush();

            System.out.println("-> UPS: " + cmd);

            // Schedule resend with exact same bytes
            Timer t = scheduleResend(() -> {
                try {
                    CodedOutputStream resendCos = CodedOutputStream.newInstance(upsOut);
                    resendCos.writeUInt32NoTag(bytes.length);
                    resendCos.writeRawBytes(bytes);
                    resendCos.flush();
                    System.out.println("🔁 UPS resend: " + cmd);
                } catch (IOException e) {
                    System.err.println("UPS resend failed: " + e);
                }
            });
            requestMap.put(seq, t);  // Only add to requestMap after successful write

        } catch (IOException e) {
            System.err.println("sendToUPS error: " + e);
        }
    }

    /** Send acknowledgments to UPS for received messages */
    private void ackUPS(UPSToAmazon msg) {
        List<Long> seqs = new ArrayList<>();
        msg.getPickupRespList()     .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getRedirectRespList()   .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getCancelRespList()     .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getTruckArrivedList()   .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getDeliveryStartedList().forEach(v -> seqs.add(v.getSeqnum()));
        msg.getDeliveryCompleteList().forEach(v -> seqs.add(v.getSeqnum()));
        if (seqs.isEmpty()) return;

        AmazonToUPS ack = AmazonToUPS.newBuilder().addAllAcks(seqs).build();
        try {
            ack.writeDelimitedTo(upsOut);
            upsOut.flush();
            System.out.println("-> UPS (ACK): " + ack);
        } catch (IOException e) {
            System.err.println("ackUPS error: " + e);
        }
    }

    private Timer scheduleResend(Runnable sendOp) {
        Timer t = new Timer();
        t.schedule(new TimerTask() { @Override public void run() { sendOp.run(); } }, 0, TIME_OUT_MS);
        return t;
    }

    // -----------------------  util  ----------------------------
    private synchronized long nextSeq() { return seqNum++; }

    private boolean validatePkg(long id) {
        if (!packageMap.containsKey(id)) {
            System.err.println("[WARN] unknown package id " + id);
            return false;
        }
        return true;
    }

    private void logStage(String stage, long pkg) {
        System.out.printf("** %-12s pkg=%d **%n", stage, pkg);
    }

    // ------------------------- main ----------------------------
    public static void main(String[] args) throws Exception {
        AmazonDaemon d = new AmazonDaemon();
        d.config();
        d.runAll();
    }
}
