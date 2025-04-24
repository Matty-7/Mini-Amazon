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
 * 1. packageid is the same as shipid, and this is created when we send the APack
 * 2. all communication with the world will be blocking, e.g. load() will return only after finish loading
 * 3. all communication with the UPS will be unblocking, e.g. toPick() will return once send successfully(receive ack)
 * 4. a full process will contain "purchase(world) ---> pack(world) & pick(UPS) ---> load(world) ---> deliver(UPS)"
 */
public class AmazonDaemon {
    // private static final String WORLD_HOST = "vcm-13663.vm.duke.edu";
    private static final String WORLD_HOST = "vcm-14299.vm.duke.edu";
    //private static final String WORLD_HOST = "vcm-14250.vm.duke.edu";
    private static final int WORLD_PORT = 23456;

    private static final String UPS_HOST = "vcm-14299.vm.duke.edu";
    private static final int UPS_PORT = 54321;

    //private static final String UPS_HOST = "vcm-14250.vm.duke.edu";
    //private static final int UPS_PORT = 6666;

    public static final int UPS_SERVER_PORT = 9999;

    // the default timeout for each request
    // i.e. resend request if don't receive ack within TIME_OUT

    private static final int TIME_OUT = 3000;

    // TODO: debug info
    MockUPS ups;
    boolean isMockingUPS;

    // NOTE!!! the world simulator use only one socket to communicate with us
    // i.e. the server expect to receive AConnect from each new connection
    private InputStream in;
    private OutputStream out;
    // global sequence number
    private long seqNum;
    // server communicate with UPS
    private Server upsServer;
    // daemon thread used to communicate with front-end
    private DaemonThread daemonThread;
    // a mpa of all unfinished package(key is the package id)
    private final Map<Long, Package> packageMap;
    // mapping between sequence number and request(the timer handle the re-send task)
    private final Map<Long, Timer> requestMap;
    // thread pool, used to achieve concurrency
    private final ThreadPoolExecutor threadPool;
    // all warehouses
    private final List<AInitWarehouse> warehouses;


    public AmazonDaemon() throws IOException {
        isMockingUPS = false;
        if(isMockingUPS){
            ups = new MockUPS();
        }

        this.seqNum = 0;
        this.daemonThread = null;
        this.packageMap = new ConcurrentHashMap<>();
        this.requestMap = new ConcurrentHashMap<>();
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(30);
        this.threadPool = new ThreadPoolExecutor(50, 80, 5, TimeUnit.SECONDS, workQueue);
        this.warehouses = new SQL().queryWHs();
    }

    /**
     * This function will set up a (UPS) server, waiting for the connection from UPS.
     * After getting the world id, and successfully connected to the world, it will
     * open another thread handle the request coming from Django front-end. The main
     * thread is used to communicate with UPS and the world.
     */
    public void config() throws IOException {
        if (isMockingUPS){
            ups.init();
        }

        System.out.println("Daemon is running...");
        System.out.println("Listening connection from UPS at " + UPS_SERVER_PORT);
        // the server listening the request comes from UPS
        upsServer = new Server(UPS_SERVER_PORT);
        // we will keep trying until we successfully connect to the world
        while (true) {
            Socket s = upsServer.accept();
            if (s != null) {
                UAstart.Builder builder = UAstart.newBuilder();
                recvMsgFrom(builder, s.getInputStream());
                // has a valid world id
                if (builder.hasWorldid() && connectToWorld(builder.getWorldid())) {
                    System.out.println("Amazon connected to the world.");
                    // send back ack
                    sendMsgTo(Res.newBuilder().addAck(builder.getSeqnum()).build(), s.getOutputStream());
                    break;
                }
            }
        }
    }

    /**
     * Connect to the world.
     * @param worldID target world id
     * @return true if connect to the world successfully
     */
    boolean connectToWorld(long worldID) throws IOException {
        // set up the TCP connection to the world
        Socket socket = new Socket(WORLD_HOST, WORLD_PORT);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        // connect to the world(send AConnect message)
        AConnect.Builder connect = AConnect.newBuilder();
        connect.setIsAmazon(true);
        connect.addAllInitwh(warehouses);
        if (worldID >= 0) {
            connect.setWorldid(worldID);
        }

        AConnected.Builder connected = AConnected.newBuilder();

        sendMsgTo(connect.build(), out);
        recvMsgFrom(connected, in);

        System.out.println("world id: " + connected.getWorldid());
        System.out.println("result: " + connected.getResult());

        return connected.getResult().equals("connected!");
    }

    /**
     * Run both daemon thread, receiving thread the the UPS server.
     */
    public void runAll() {
        // TODO: debug info, mock a new purchase request after 3s and another after 2s
        /*new Thread(() -> {
            try {
                Thread.sleep(3000);
                // WARNING!!! only two package while debugging(mock UPS only have two trucks)
                List<String> packages = new ArrayList<>(Arrays.asList("4\n", "5\n"));
                for (String p : packages){
                    System.out.println("try to connect to daemon server");
                    Socket socket = new Socket("localhost", 8888);
                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                    out.write(p);
                    out.flush();
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    System.out.println("receive confirm from amazon: " + in.readLine());
                    socket.close();
                    Thread.sleep(1000);
                }
            }catch (Exception e){
                System.err.println("debug thread: " + e.toString());
            }
	    }).start();*/

        // prepare all core threads
        threadPool.prestartAllCoreThreads();

        // run two separate servers
        runDaemonServer();
        runUPSServer();

        // open a new thread dedicate for receiving message from the world
        Thread recvThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()){
                AResponses.Builder responses = AResponses.newBuilder();
                // only this thread will access the "in" object, no need to synchronized
                recvMsgFrom(responses, in);
                handleWorldResponse(responses.build());
            }
        });
        recvThread.start();
    }

    /**
     * This function will create a daemon thread, which used to communicate with front-end.
     */
    void runDaemonServer() {
        daemonThread = new DaemonThread(packageID -> {
            System.out.println(String.format("Receive new buying request, id: %d", packageID));
            toPurchase(packageID);
        });
        daemonThread.start();
    }

    /**
     * This function will keep waiting for new connections from UPS side.
     */
    void runUPSServer() {
        Thread upsThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = upsServer.accept();
                if (socket != null) {
                    threadPool.execute(() -> {
                        try {
                            handleUPSResponse(socket.getInputStream(), socket.getOutputStream());
                        } catch (Exception e) {
                            System.err.println("runUPSServer: " + e.toString());
                        }
                    });
                }
            }
        });
        upsThread.start();
    }

    /**
     * This function will handle the response coming back from the world.
     * Loop for every possible field in the response and update corresponding package.
     * @param responses response
     */
    void handleWorldResponse(AResponses responses){
        System.out.println("recv from world: " + responses.toString());
        // send back the ack
        sendAck(responses);
        // arrived package
        for (APurchaseMore purchaseMore : responses.getArrivedList()){
            purchased(purchaseMore);
        }
        // packed package ---> to load
        for (APacked p : responses.getReadyList()){
            packed(p.getShipid());
        }
        // loaded package ---> to delivery
        for (ALoaded l : responses.getLoadedList()){
            loaded(l.getShipid());
        }
        // error message
        for (AErr err : responses.getErrorList()){
            System.err.println(err.getErr());
        }
        // package status
        for (APackage aPackage : responses.getPackagestatusList()){
            System.out.println(aPackage.getStatus());
            packageMap.get(aPackage.getPackageid()).setStatus(aPackage.getStatus());
        }
        // check all ack
        for (long ack : responses.getAcksList()){
            // sanity check(in case world send some duplicate ack)
            if (requestMap.containsKey(ack)){
                requestMap.get(ack).cancel();
                requestMap.remove(ack);
            }
        }
        // disconnect from the world
        if (responses.hasFinished()){
            System.out.println("amazon disconnect finish");
        }
    }

    /**
     * Handle the response coming from UPS(e.g. picked & delivered)
     * @param inputStream receive from UPS
     * @param outputStream send back to UPS
     */
    void handleUPSResponse(InputStream inputStream, OutputStream outputStream) {
        UAcommand.Builder command = UAcommand.newBuilder();
        recvMsgFrom(command, inputStream);
        System.out.println("receive from ups:" + command);
        // send back ack once received
        sendAck(command.build(), outputStream);
        // check all trucks which arrive at the warehouse
        for (UApicked p : command.getPickList()) {
            // update package truck id and tell it to load
            picked(p.getShipid(), p.getTruckid());
        }
        // check all packages which are delivered
        for (UAdelivered d : command.getDeliverList()) {
            // set the package delivered and remove it from the map(don't care anymore)
            delivered(d.getShipid());
        }
    }

    /**
     * This function will check the validation of package ID.
     * It will throw an IllegalArgumentException if the package is not in the unfinished map.
     *
     * @param packageID ID to be checked
     */
    boolean checkPackageID(long packageID) {
        if (!packageMap.containsKey(packageID)) {
            System.err.println("invalid package id: " + packageID);
            return false;
        }
        return true;
    }

    /* ====== all functions start with to, e.g. toXXX() is asynchronous ====== */
    /**
     * Tell the world to purchase something to the corresponding warehouse.
     * Function is asynchronous, will use thread-pool and return immediately.
     * @param packageID corresponding package
     */
    void toPurchase(long packageID) {
        printStatus("purchasing", packageID);
        threadPool.execute(() -> {
            // 1. retrieve the package info from DB
            long seq = getSeqNum();
            APurchaseMore.Builder newPackage = new SQL().queryPackage(packageID);
            newPackage.setSeqnum(seq);

            // 2. create & store the package object
            // this APack object is mainly for the convenient of following operation
            APack.Builder builder = APack.newBuilder();
            builder.setWhnum(newPackage.getWhnum());
            builder.addAllThings(newPackage.getThingsList());
            builder.setShipid(packageID);
            builder.setSeqnum(-1);
            Package p = new Package(packageID, newPackage.getWhnum(), builder.build());
            p.setStatus(Package.PROCESSING);
            // store this unfinished package to the map
            packageMap.put(packageID, p);

            // 3. tell the world to purchase more
            sendToWorld(ACommands.newBuilder().addBuy(newPackage), seq);
        });
    }

    /**
     * Tell the world to pack the package.
     * Function is asynchronous, will use thread-pool and return immediately.
     * @param packageID corresponding package
     */
    void toPack(long packageID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("packing", packageID);
        Package p = packageMap.get(packageID);
        p.setStatus(Package.PACKING);
        threadPool.execute(() -> {
            ACommands.Builder command = ACommands.newBuilder();
            long seq = getSeqNum();
            APack pack = packageMap.get(packageID).getPack();
            command.addTopack(pack.toBuilder().setSeqnum(seq));
            sendToWorld(command, seq);
        });
    }

    /**
     * Tell UPS go to a specific warehouse to pick up the package.
     * @param packageID corresponding package
     */
    void toPick(long packageID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("picking", packageID);
        Package p = packageMap.get(packageID);
        threadPool.execute(() -> {
            if (isMockingUPS) {
                // TODO: debug info
                ups.pick(p.getWhID(), packageID);
            } else {
                long seqNum = getSeqNum();
                AUpick.Builder pick = AUpick.newBuilder();
                pick.setPackage(p.getPack());
                pick.setSeqnum(seqNum);
                // warehouse id start from 1
                pick.setWh(warehouses.get(p.getWhID() - 1));
                pick.setX(p.getDestX());
                pick.setY(p.getDestY());
                // set ups name
                String name = p.getUpsName();
                if (!name.isEmpty()) {
                    pick.setUpsUserName(name);
                }
                AUcommand.Builder command = AUcommand.newBuilder();
                command.addPick(pick);

                sendToUPS(command.build(), seqNum);
            }
        });
    }

    /**
     * Tell the world to load the package onto the truck.
     * NOTE: this function should only be called after the truck has arrived.
     * @param packageID corresponding package
     */
    void toLoad(long packageID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("loading", packageID);
        Package p = packageMap.get(packageID);
        p.setStatus(Package.LOADING);
        threadPool.execute(() -> {
            ACommands.Builder command = ACommands.newBuilder();
            long seq = getSeqNum();
            APutOnTruck.Builder load = APutOnTruck.newBuilder();
            load.setWhnum(p.getWhID());
            load.setTruckid(p.getTruckID());
            load.setShipid(packageID);
            load.setSeqnum(seq);
            command.addLoad(load);

            sendToWorld(command, seq);
        });
    }

    /**
     * Tell UPS to deliver the package.
     * NOTE: this function should only be called after the package is loaded.
     *
     * @param packageID corresponding package
     */
    void toDelivery(long packageID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("delivering", packageID);
        Package p = packageMap.get(packageID);
        p.setStatus(Package.DELIVERING);
        threadPool.execute(() -> {
            if (isMockingUPS) {
                // TODO: debug info
                ups.delivery(p.getDestX(), p.getDestY(), packageID);
            } else {
                long seqNum = getSeqNum();
                AUdeliver.Builder deliver = AUdeliver.newBuilder();
                deliver.setPackage(p.getPack());
                deliver.setSeqnum(seqNum);

                AUcommand.Builder command = AUcommand.newBuilder();
                command.addDeliver(deliver);

                sendToUPS(command.build(), seqNum);
            }
        });
    }

    /**
     * Query the status of a package, not so useful(we can keep track by ourselves)
     * @param packageID corresponding package
     */
    public void toQuery(int packageID) {
        printStatus("querying", packageID);
        threadPool.execute(() -> {
            ACommands.Builder command = ACommands.newBuilder();

            long seq = getSeqNum();
            AQuery.Builder query = AQuery.newBuilder();
            query.setPackageid(packageID);
            query.setSeqnum(seq);
            command.addQueries(query);

            sendToWorld(command, seq);
        });
    }

    /**
     * Disconnect from the world.
     */
    public void toDisconnect() {
        ACommands.Builder command = ACommands.newBuilder();
        command.setDisconnect(true);
        sendToWorld(command, 0);
    }

    /* ============ end ============ */

    /* ============ all functions end with ed(e.g. purchased) is used to handle the result coming from world or UPS ============ */

    /**
     * The world has purchased corresponding package to the warehouse.
     */
    void purchased(APurchaseMore purchaseMore){
        synchronized (packageMap){
            // loop all package to find the corresponding package
            for (Package p : packageMap.values()){
                if (p.getWhID() != purchaseMore.getWhnum()){
                    continue;
                }
                if (!p.getPack().getThingsList().equals(purchaseMore.getThingsList())){
                    continue;
                }
                printStatus("purchased", p.getId());
                // this is the corresponding package
                // no worry, if two package has exactly the same warehouse and product list(item + count)
                // it doesn't matter we process which first
                p.setStatus(Package.PROCESSED);
                // tell UPS to pick the package
                toPick(p.getId());
                // tell the world to pack the package
                toPack(p.getId());
                break;
            }
        }
    }

    /**
     * The world has packed our package.
     * @param packageID id of the packed package
     */
    void packed(long packageID){
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("packed", packageID);
        Package p = packageMap.get(packageID);
        p.setStatus(Package.PACKED);
        // check whether the truck has already arrived the warehouse
        if (p.getTruckID() != -1){
            toLoad(packageID);
        }
    }

    /**
     * The truck has arrived at the warehouse, should load the package.
     * @param packageID corresponding package
     * @param truckID   corresponding truck
     */
    void picked(long packageID, int truckID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("picked", packageID);
        Package p = packageMap.get(packageID);
        p.setTruckID(truckID);
        // check whether the package is packed
        if (p.getStatus().equals(Package.PACKED)) {
            toLoad(packageID);
        }
    }

    /**
     * The world has loaded our package to the truck.
     * @param packageID id of the loaded package
     */
    void loaded(long packageID){
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("loaded", packageID);
        packageMap.get(packageID).setStatus(Package.LOADED);
        toDelivery(packageID);
    }

    /**
     * The package is delivered.
     * @param packageID corresponding package
     */
    void delivered(long packageID) {
        if (!checkPackageID(packageID)){
            return;
        }
        printStatus("delivered", packageID);
        packageMap.get(packageID).setStatus(Package.DELIVERED);
        // remove from the unfinished package map
        packageMap.remove(packageID);
    }

    /* ============ end ============ */

    /**
     * Send back the ack to UPS.
     * This function will extract all seqnum in the UAcommand, and send back corresponding ack.
     * message UAcommand{
     * repeated UApicked pick = 2;
     * repeated UAdelivered deliver = 3;
     * }
     * @param command      the incoming command you want to ack
     * @param outputStream output stream
     */
    void sendAck(UAcommand command, OutputStream outputStream) {
        List<Long> seqs = new ArrayList<>();
        for (UApicked a : command.getPickList()) {
            seqs.add(a.getSeqnum());
        }
        for (UAdelivered a : command.getDeliverList()) {
            seqs.add(a.getSeqnum());
        }
        Res.Builder res = Res.newBuilder();
        for (long seq : seqs) {
            res.addAck(seq);
        }
        System.out.println("send ack back(to UPS): " + res.toString());
        sendMsgTo(res.build(), outputStream);
    }

    /**
     * This function will extract all seqnum in the AResponse and send back corresponding ack.
     * message AResponses {
     * repeated APurchaseMore arrived = 1;
     * repeated APacked ready = 2;
     * repeated ALoaded loaded = 3;
     * optional bool finished = 4;
     * repeated AErr error = 5;
     * repeated int64 acks = 6;
     * repeated APackage packagestatus = 7;
     * }
     * @param responses the response you want to ack
     */
    void sendAck(AResponses responses) {
        List<Long> seqs = new ArrayList<>();
        for (APurchaseMore a : responses.getArrivedList()) {
            seqs.add(a.getSeqnum());
        }
        for (APacked a : responses.getReadyList()) {
            seqs.add(a.getSeqnum());
        }
        for (ALoaded a : responses.getLoadedList()) {
            seqs.add(a.getSeqnum());
        }
        for (AErr a : responses.getErrorList()) {
            seqs.add(a.getSeqnum());
        }
        for (APackage a : responses.getPackagestatusList()) {
            seqs.add(a.getSeqnum());
        }
        if (seqs.size() > 0) {
            ACommands.Builder commands = ACommands.newBuilder();
            for (long seq : seqs) {
                commands.addAcks(seq);
            }
            System.out.println("send ack back(to World): " + commands.toString());
            synchronized (out) {
                sendMsgTo(commands.build(), out);
            }
        }
    }

    /**
     * This function will send the AUcommand to UPS and receive an ack.
     * @param command AUcommand
     */
    void sendToUPS(AUcommand command, long seqNum) {
        try {
            System.out.println("amazon sending(to UPS): " + command.toString());
            Socket socket = new Socket(UPS_HOST, UPS_PORT);
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();

            Res.Builder r = Res.newBuilder();
            // note: we should use the new stream not the global one(which is for the world)
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sendMsgTo(command, outputStream);
                }
            }, 0, TIME_OUT);

            while (true) {
                recvMsgFrom(r, inputStream);
		        System.out.println("recv UPS ack: " + r.toString());
                // print out any error message
                if (r.getErrCount() > 0) {
                    for (Err err : r.getErrList()) {
                        System.err.println(err.toString());
                    }
                }

                if (r.getAckCount() > 0) {
                    timer.cancel();
                    break;
                }
                r.clear();
            }
        } catch (Exception e) {
            System.err.println("sendToUPS: " + e.toString());
        }
    }

    /**
     * A wrapper of the actual send function, this function will re-send automatically if timeout.
     * This function used to communicate with the world.
     * message ACommands {
     * repeated APurchaseMore buy = 1;
     * repeated APack topack = 2;
     * repeated APutOnTruck load = 3;
     * repeated AQuery queries = 4;
     * optional uint32 simspeed = 5;
     * optional bool toDisconnect = 6;
     * repeated int64 acks =7;
     * }
     * @param commands ACommand
     * @param seqNum   the sequence number of this command(used to check ack)
     */
    void sendToWorld(ACommands.Builder commands, long seqNum) {
        // TODO: debug info
        commands.setSimspeed(500);
        System.out.println("amazon sending(to world): " + commands.toString());
        // if not receive the ack within TIME_OUT, it will resend the message
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (out){
                    sendMsgTo(commands.build(), out);
                }
            }
        }, 0, TIME_OUT);

        requestMap.put(seqNum, timer);
    }

    /**
     * This function will handle the sequence number related stuff.
     * also, this function is thread safe
     * 1. return the latest sequence number
     * 2. make sequence number auto-increment
     *
     * @return the latest sequence number
     */
    synchronized long getSeqNum() {
        long tmp = seqNum;
        seqNum++;
        return tmp;
    }

    void printStatus(String status, long packageID){
        System.out.println(String.format("** %s package %d **", status, packageID));
    }

    public static void main(String[] args) throws Exception {
        AmazonDaemon amazonDaemon = new AmazonDaemon();
        amazonDaemon.config();
        amazonDaemon.runAll();
    }
}
