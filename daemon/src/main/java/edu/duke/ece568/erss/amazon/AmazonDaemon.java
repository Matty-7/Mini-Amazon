package edu.duke.ece568.erss.amazon;

import edu.duke.ece568.erss.amazon.proto.AmazonUPSProtocol.*;
import edu.duke.ece568.erss.amazon.proto.WorldAmazonProtocol.*;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

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
    private static final String WORLD_HOST = "ece650-vm.colab.duke.edu";
    private static final int    WORLD_PORT = 23456;

    private static final String UPS_HOST   = "vcm-14299.vm.duke.edu";
    private static final int    UPS_PORT   = 54321;

    public static final int  UPS_SERVER_PORT = 9999;    // Flask / real-UPS callback port
    private static final int TIME_OUT_MS     = 3_000;   // resend window (ms) for un-acked msgs

    // ------------------------- runtime state --------------------------
    private InputStream  worldIn;
    private OutputStream worldOut;

    private long seqNum = 0;                // global monotonically-increasing seq-num

    private Server               upsServer; // local HTTP server that UPS POSTs to
    private DaemonThread         daemonThread;

    private final Map<Long, Package> packageMap = new ConcurrentHashMap<>();
    private final Map<Long, Timer>   requestMap = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor threadPool;
    private final List<AInitWarehouse> warehouses;

    // optional in-process stub for integration tests
    private final boolean isMockingUPS = false;
    private MockUPS ups;

    // ---------------------------- ctor -------------------------------
    public AmazonDaemon() throws IOException {
        if (isMockingUPS) {
            ups = new MockUPS();
        }
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>(30);
        threadPool = new ThreadPoolExecutor(50, 80, 5, TimeUnit.SECONDS, q);
        warehouses = new SQL().queryWHs();
    }

    // -------------------------- bootstrap ----------------------------
    /**
     * Initialise the daemon:
     *  - (optional) start Mock-UPS.
     *  - spin up the local callback server so the real UPS can reach us at any time.
     *  - connect to the World simulator (passing worldId = -1 so the simulator allocates one).
     */
    public void config() throws IOException {
        if (isMockingUPS) ups.init();

        System.out.println("Daemon is starting...");

        // 1) UPS callback listener first - UPS may ping us immediately after launch.
        upsServer = new Server(UPS_SERVER_PORT);
        System.out.println("Listening for UPS callbacks on port " + UPS_SERVER_PORT);

        // 2) Connect to World - let it choose / allocate the world-id for this session.
        if (!connectToWorld(-1)) {
            throw new IOException("Failed to connect to World simulator");
        }
    }

    private boolean connectToWorld(long worldID) throws IOException {
        Socket socket = new Socket(WORLD_HOST, WORLD_PORT);
        worldIn  = socket.getInputStream();
        worldOut = socket.getOutputStream();

        AConnect.Builder connect = AConnect.newBuilder()
                .setIsAmazon(true)
                .addAllInitwh(warehouses);
        if (worldID >= 0) connect.setWorldid(worldID);

        sendMsgTo(connect.build(), worldOut);

        AConnected.Builder connected = AConnected.newBuilder();
        recvMsgFrom(connected, worldIn);

        System.out.printf("World handshake -> id=%d  result=%s%n",
                          connected.getWorldid(), connected.getResult());
        return "connected!".equals(connected.getResult());
    }

    // ----------------------- main threads ---------------------------
    public void runAll() {
        threadPool.prestartAllCoreThreads();

        runDaemonServer();
        runUPSServer();

        // dedicated reader for async World events
        new Thread(() -> {
            while (true) {
                AResponses.Builder resp = AResponses.newBuilder();
                recvMsgFrom(resp, worldIn);
                handleWorldResponse(resp.build());
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

    private void runUPSServer() {
        new Thread(() -> {
            while (true) {
                Socket s = upsServer.accept();
                if (s == null) continue;
                threadPool.execute(() -> {
                    try (InputStream is = s.getInputStream(); OutputStream os = s.getOutputStream()) {
                        handleUPSResponse(is, os);
                    } catch (Exception e) {
                        System.err.println("UPS-handler: " + e);
                    }
                });
            }
        }, "UPS-callback-server").start();
    }

    // -------------------  World  -> Amazon  -----------------------
    private void handleWorldResponse(AResponses resp) {
        System.out.println("<- World: " + resp);
        sendAckToWorld(resp);

        for (APurchaseMore p : resp.getArrivedList())  purchased(p);
        for (APacked       p : resp.getReadyList())   packed(p.getShipid());
        for (ALoaded       l : resp.getLoadedList())  loaded(l.getShipid());
        for (AErr          e : resp.getErrorList())   System.err.println("World-err: " + e.getErr());

        for (APackage st : resp.getPackagestatusList()) {
            Package pkg = packageMap.get(st.getPackageid());
            if (pkg != null) pkg.setStatus(st.getStatus());
        }
    }

    private void sendAckToWorld(AResponses resp) {
        List<Long> seqs = new ArrayList<>();
        resp.getArrivedList()      .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getReadyList()        .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getLoadedList()       .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getErrorList()        .forEach(v -> seqs.add(v.getSeqnum()));
        resp.getPackagestatusList().forEach(v -> seqs.add(v.getSeqnum()));
        if (seqs.isEmpty()) return;

        ACommands.Builder ack = ACommands.newBuilder().addAllAcks(seqs);
        synchronized (worldOut) { sendMsgTo(ack.build(), worldOut); }
    }

    // --------------------  UPS  -> Amazon  ------------------------
    private void handleUPSResponse(InputStream is, OutputStream os) {
        UPSToAmazon.Builder upsMsg = UPSToAmazon.newBuilder();
        recvMsgFrom(upsMsg, is);
        UPSToAmazon msg = upsMsg.build();
        System.out.println("<- UPS: " + msg);

        ackUPS(msg, os);

        for (TruckArrived      t : msg.getTruckArrivedList())   picked   (t.getPackageId(), t.getTruckId());
        for (DeliveryComplete  d : msg.getDeliveryCompleteList()) delivered(d.getPackageId());
    }

    private void ackUPS(UPSToAmazon msg, OutputStream os) {
        List<Long> seqs = new ArrayList<>();
        msg.getPickupRespList()     .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getRedirectRespList()   .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getCancelRespList()     .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getTruckArrivedList()   .forEach(v -> seqs.add(v.getSeqnum()));
        msg.getDeliveryStartedList().forEach(v -> seqs.add(v.getSeqnum()));
        msg.getDeliveryCompleteList().forEach(v -> seqs.add(v.getSeqnum()));
        if (seqs.isEmpty()) return;

        AmazonToUPS.Builder ack = AmazonToUPS.newBuilder().addAllAcks(seqs);
        sendMsgTo(ack.build(), os);
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
                    .setShipid(pkgId);
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
                    .setUpsUserId(p.getUpsUserId())
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
        packageMap.values().stream()
                .filter(p -> p.matchesArrival(buy))
                .findFirst()
                .ifPresent(p -> {
                    logStage("purchased", p.getId());
                    p.setStatus(Package.PROCESSED);
                    toRequestPickup(p.getId());  // ask UPS for pickup
                    toPack(p.getId());           // concurrently ask World to pack
                });
    }

    private void packed(long pkgId) {
        if (!validatePkg(pkgId)) return;
        logStage("packed", pkgId);
        Package p = packageMap.get(pkgId);
        p.setStatus(Package.PACKED);
        if (p.getTruckID() != -1) toLoad(pkgId); // truck already here
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
        Timer t = scheduleResend(() -> sendMsgTo(cmd.build(), worldOut));
        requestMap.put(seq, t);
    }

    private void sendToUPS(AmazonToUPS cmd, long seq) {
        try {
            Socket s = new Socket(UPS_HOST, UPS_PORT);
            InputStream  is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            Timer t = scheduleResend(() -> sendMsgTo(cmd, os));
            requestMap.put(seq, t);

            while (true) {
                UPSToAmazon.Builder resp = UPSToAmazon.newBuilder();
                recvMsgFrom(resp, is);
                if (resp.getAcksList().contains(seq)) {
                    t.cancel();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("sendToUPS: " + e);
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
