package edu.duke.ece568.erss.amazon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

import edu.duke.ece568.erss.amazon.proto.AmazonUPSProtocol.*;
import edu.duke.ece568.erss.amazon.proto.WorldUPSProtocol.*;

import static edu.duke.ece568.erss.amazon.AmazonDaemon.UPS_SERVER_PORT;
import static edu.duke.ece568.erss.amazon.Utils.recvMsgFrom;
import static edu.duke.ece568.erss.amazon.Utils.sendMsgTo;

public class MockUPS {
    private static final String WORLD_HOST = "vcm-13663.vm.duke.edu";
    private static final int    WORLD_PORT = 12345;

    private long worldId = -1;
    private int  nextTruck = 1;
    private long seq       = 0;

    private final InputStream  worldIn;
    private final OutputStream worldOut;

    public MockUPS() throws IOException {
        Socket s = new Socket(WORLD_HOST, WORLD_PORT);
        worldIn  = s.getInputStream();
        worldOut = s.getOutputStream();
    }

    public void init() {
        new Thread(() -> {
            connectToWorld(-1);
        }, "MockUPS-world").start();
    }

    private void connectToWorld(long hint) {
        UConnect.Builder c = UConnect.newBuilder().setIsAmazon(false);
        if (hint >= 0) c.setWorldid(hint);
        for (int i = 1; i <= 3; ++i) c.addTrucks(UInitTruck.newBuilder().setId(i).setX(i).setY(i));
        sendMsgTo(c.build(), worldOut); seq++;
        UConnected.Builder ok = UConnected.newBuilder();
        recvMsgFrom(ok, worldIn);
        worldId = ok.getWorldid();
        System.out.println("[MockUPS] world=" + worldId);
    }

    public synchronized void pick(int wh, long pkg) {
        int truck = allocTruck();
        sendMsgTo(UCommands.newBuilder().addPickups(UGoPickup.newBuilder()
                .setTruckid(truck).setWhid(wh).setSeqnum(seq)).build(), worldOut);
        long reqSeq = seq++;
        waitWorldAck(reqSeq);
        TruckArrived ta = TruckArrived.newBuilder()
                .setSeqnum(seq).setPackageId(pkg).setTruckId(truck).setWarehouseId(wh).build();
        sendToAmazon(UPSToAmazon.newBuilder().addTruckArrived(ta).build(), seq++);
    }

    public synchronized void delivery(int x,int y,long pkg){
        int truck=allocTruck();
        sendMsgTo(UCommands.newBuilder().addDeliveries(UGoDeliver.newBuilder()
                .setTruckid(truck).addPackages(UDeliveryLocation.newBuilder().setPackageid(pkg).setX(x).setY(y))
                .setSeqnum(seq)).build(), worldOut);
        long req=seq++;
        waitWorldDelivery(req);
        DeliveryComplete dc=DeliveryComplete.newBuilder().setSeqnum(seq).setPackageId(pkg).build();
        sendToAmazon(UPSToAmazon.newBuilder().addDeliveryComplete(dc).build(),seq++);
    }


    private int allocTruck(){int id=nextTruck;nextTruck=nextTruck%3+1;return id;}
    private void waitWorldAck(long s){UResponses.Builder r=UResponses.newBuilder();do{r.clear();recvMsgFrom(r,worldIn);}while(!r.getAcksList().contains(s));sendMsgTo(UCommands.newBuilder().addAcks(s).build(),worldOut);}    
    private void waitWorldDelivery(long s){UResponses.Builder r=UResponses.newBuilder();do{r.clear();recvMsgFrom(r,worldIn);}while(r.getDeliveredCount()==0||r.getDelivered(0).getSeqnum()!=s);sendMsgTo(UCommands.newBuilder().addAcks(s).build(),worldOut);}    

    private void sendToAmazon(UPSToAmazon m,long expect){try(Socket a=new Socket("localhost",UPS_SERVER_PORT)){sendMsgTo(m,a.getOutputStream());AmazonToUPS.Builder ack=AmazonToUPS.newBuilder();do{ack.clear();recvMsgFrom(ack,a.getInputStream());}while(!ack.getAcksList().contains(expect));}catch(Exception e){System.err.println("MockUPS->Amazon "+e);} }
}
