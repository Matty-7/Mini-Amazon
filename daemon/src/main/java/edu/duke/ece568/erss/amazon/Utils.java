package edu.duke.ece568.erss.amazon;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

public final class Utils {

    /** Encode + length-prefix a protobuf **message** and write it to the stream. */
    public static boolean sendMsgTo(Message msg, OutputStream out) {
        try {
            byte[] data = msg.toByteArray();
            CodedOutputStream cos = CodedOutputStream.newInstance(out);
            cos.writeUInt32NoTag(data.length);   // length prefix
            cos.writeRawBytes(data);
            cos.flush();                         // always flush!
            return true;
        } catch (IOException e) {
            System.err.println("sendMsgTo: " + e);
            return false;
        }
    }

    /** Read one length-prefixed protobuf message into the provided **Builder**. */
    public static boolean recvMsgFrom(Message.Builder builder,
                                      InputStream in) throws IOException {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(in);
            int len = cis.readRawVarint32();
            if (len <= 0) {
                System.err.println("Warning: received message with length " + len);
                return false;
            }
            
            // Increase the size limit for large messages
            cis.setSizeLimit(Integer.MAX_VALUE);
            
            int oldLimit = cis.pushLimit(len);
            builder.mergeFrom(cis);
            cis.popLimit(oldLimit);
            return true;
        } catch (IOException e) {
            System.err.println("Error reading message: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected error in recvMsgFrom: " + e);
            throw new IOException("Failed to read message: " + e.getMessage(), e);
        }
    }

    // no instantiation
    private Utils() {}
}
