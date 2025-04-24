package edu.duke.ece568.erss.amazon;

import edu.duke.ece568.erss.amazon.listener.onPurchaseListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * This thread handle the request coming from out front-end.
 */
public class DaemonThread extends Thread {
    onPurchaseListener listener;

    public DaemonThread(onPurchaseListener listener) {
        this.listener = listener;
        this.setDaemon(true);
    }

    @Override
    public void run() {
        // this outermost while loop ensure our server will runAll "forever"
        // if any exceptions are thrown, go to the catch clause and then will close
        // the serve automatically, next loop, will restart the server
        while (!Thread.currentThread().isInterrupted()){
            try (Server daemonServer = new Server(8888)){
                System.out.println("Listening connection from front-end at 8888");
                while (!Thread.currentThread().isInterrupted()){
                    Socket s = daemonServer.accept();
                    if (s != null){
                        handlePurchaseRequest(s);
                    }
                }
            }catch (Exception e){
                System.err.println(e.toString());
            }
        }
    }

    /**
     * Front-end will only communicate with our daemon when it wants to purchase something.
     * Other case(e.g. query status), it can simply query the data from database.
     * @param socket new connection
     * @throws IOException probably because of stream error
     */
    void handlePurchaseRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter writer = new PrintWriter(socket.getOutputStream());
        String req = reader.readLine();
        System.out.println("new buying request: " + req);
        long id = Long.parseLong(req);
        writer.write(String.format("ack:%d", id));
        writer.flush();
        // close the connection
        socket.close();
        if (listener != null){
            listener.onPurchase(id);
        }
    }
}
