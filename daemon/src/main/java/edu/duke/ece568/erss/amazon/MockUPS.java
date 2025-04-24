package edu.duke.ece568.erss.amazon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import edu.duke.ece568.erss.amazon.proto.AmazonUPSProtocol;
import edu.duke.ece568.erss.amazon.proto.WorldUPSProtocol.*;

import static edu.duke.ece568.erss.amazon.AmazonDaemon.UPS_SERVER_PORT;
import static edu.duke.ece568.erss.amazon.Utils.recvMsgFrom;
import static edu.duke.ece568.erss.amazon.Utils.sendMsgTo;

/**
 * This class is mainly for testing purpose, mock a simple UPS.
 */
public class MockUPS {
    private static final String HOST = "vcm-13663.vm.duke.edu";
    private static final int PORT = 12345;

    public long worldID;
    public int truckID;

    private final InputStream in;
    private final OutputStream out;
    private long seqNum;

    public MockUPS() throws IOException {
        worldID = -1;
        seqNum = 0;
        truckID = 1;
        Socket socket = new Socket(HOST, PORT);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public void init(){
        new Thread(() -> {
            // 1. create a new world
            connectToWorld(-1);
            // 2. connect to amazon and tell it the result
            try {
                Socket socket = new Socket("localhost", UPS_SERVER_PORT);
                sendMsgTo(AmazonUPSProtocol.UAstart.newBuilder().setWorldid((int) worldID).setSeqnum(seqNum).build(), socket.getOutputStream());
                AmazonUPSProtocol.Res.Builder builder = AmazonUPSProtocol.Res.newBuilder();
                recvMsgFrom(builder, socket.getInputStream());
                System.out.println("ups rec: " + builder.toString());
            }catch (Exception e){
                System.err.println("ups init: " + e.toString());
            }
        }).start();
    }

    public boolean connectToWorld(long worldID) {
        // init two trucks
        UInitTruck.Builder builder = UInitTruck.newBuilder();
        builder.setId(1);
        builder.setX(1);
        builder.setY(1);

        UInitTruck.Builder builder1 = UInitTruck.newBuilder();
        builder1.setId(2);
        builder1.setX(2);
        builder1.setY(2);

        UInitTruck.Builder builder2 = UInitTruck.newBuilder();
        builder2.setId(3);
        builder2.setX(3);
        builder2.setY(3);

        UConnect.Builder connect =  UConnect.newBuilder();
        connect.setIsAmazon(false);
        connect.addTrucks(builder);
        connect.addTrucks(builder1);
        connect.addTrucks(builder2);
        if (worldID >= 0){
            connect.setWorldid(worldID);
        }

        UConnected.Builder connected = UConnected.newBuilder();

        sendMsgTo(connect.build(), out);
        seqNum++;
        recvMsgFrom(connected, in);

        this.worldID = connected.getWorldid();
        System.out.println("world id: " + connected.getWorldid());
        System.out.println("result: " + connected.getResult());

        return connected.getResult().equals("connected!");
    }

    public synchronized void pick(int whID, long packageID){
        UCommands.Builder command = UCommands.newBuilder();

        UGoPickup.Builder pick = UGoPickup.newBuilder();
        pick.setWhid(whID);
        pick.setTruckid(packageID % 2 == 0 ? 1 : 2);
        pick.setSeqnum(seqNum);
        command.addPickups(pick);

        UResponses.Builder responses = UResponses.newBuilder();

        sendMsgTo(command.build(), out);
        seqNum++;
        recvMsgFrom(responses, in);
        System.out.println(responses.toString());

        if (responses.getCompletionsCount() == 0){
            responses.clear();
            synchronized (in) {
                recvMsgFrom(responses, in);
            }
            System.out.println(responses.toString());
        }

        List<Long> seqs = new ArrayList<>();
        for (UFinished finished : responses.getCompletionsList()){
            seqs.add(finished.getSeqnum());
        }
        sendAck(seqs);

        try{
            Socket socket = new Socket("localhost", UPS_SERVER_PORT);
            AmazonUPSProtocol.UApicked.Builder picked = AmazonUPSProtocol.UApicked.newBuilder();

            picked.setSeqnum(seqNum);
            picked.setShipid(packageID);
            picked.setTruckid(packageID % 2 == 0 ? 1 : 2);

            AmazonUPSProtocol.UAcommand.Builder c = AmazonUPSProtocol.UAcommand.newBuilder();
            c.addPick(picked);

            AmazonUPSProtocol.Res.Builder r = AmazonUPSProtocol.Res.newBuilder();

            sendMsgTo(c.build(), socket.getOutputStream());
            recvMsgFrom(r, socket.getInputStream());
            if(r.getAck(0) == seqNum){
                seqNum++;
                System.out.println("ups pick receive correct ack");
            }
        }catch (IOException e){
            System.err.println("ups pick:" + e.toString());
        }
    }

    public synchronized void delivery(int destX, int destY, long packageID){
        UCommands.Builder command = UCommands.newBuilder();

        UGoDeliver.Builder delivery = UGoDeliver.newBuilder();
        delivery.setTruckid(packageID % 2 == 0 ? 1 : 2);
        delivery.addPackages(UDeliveryLocation.newBuilder().setPackageid(packageID).setX(destX).setY(destY));
        delivery.setSeqnum(seqNum);
        command.addDeliveries(delivery);

        UResponses.Builder responses = UResponses.newBuilder();

        sendMsgTo(command.build(), out);
        seqNum++;
        recvMsgFrom(responses, in);
        System.out.println(responses.toString());

        if (responses.getDeliveredCount() == 0){
            responses.clear();
            recvMsgFrom(responses, in);
            System.out.println(responses.toString());
        }

        List<Long> seqs = new ArrayList<>();
        for (UDeliveryMade d : responses.getDeliveredList()){
            seqs.add(d.getSeqnum());
        }
        sendAck(seqs);

        try{
            Socket socket = new Socket("localhost", UPS_SERVER_PORT);
            AmazonUPSProtocol.UAdelivered.Builder delivered = AmazonUPSProtocol.UAdelivered.newBuilder();

            delivered.setSeqnum(seqNum);
            delivered.setShipid(packageID);

            AmazonUPSProtocol.UAcommand.Builder c = AmazonUPSProtocol.UAcommand.newBuilder();
            c.addDeliver(delivered);

            AmazonUPSProtocol.Res.Builder r = AmazonUPSProtocol.Res.newBuilder();

            sendMsgTo(c.build(), socket.getOutputStream());
            recvMsgFrom(r, socket.getInputStream());
            if(r.getAck(0) == seqNum){
                seqNum++;
                System.out.println("ups delivery receive correct ack");
            }
        }catch (IOException e){
            System.err.println("ups delivery:" + e.toString());
        }
    }

    public synchronized void disconnect(){
        UCommands.Builder command = UCommands.newBuilder();
        command.setDisconnect(true);

        UResponses.Builder responses = UResponses.newBuilder();

        sendMsgTo(command.build(), out);
        seqNum++;
        recvMsgFrom(responses, in);
        System.out.println(responses.toString());

        if (responses.hasFinished()){
            System.out.println("ups toDisconnect finish");
        }

    }

    void sendAck(List<Long> seqs){
        UCommands.Builder commands = UCommands.newBuilder();
        for (long seq : seqs){
            commands.addAcks(seq);
        }
        sendMsgTo(commands.build(), out);
    }
}
