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
                                      InputStream in) {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(in);
            int len      = cis.readRawVarint32();
            int oldLimit = cis.pushLimit(len);
            builder.mergeFrom(cis);
            cis.popLimit(oldLimit);
            return true;
        } catch (IOException e) {
            System.err.println("recvMsgFrom: " + e);
            return false;
        }
    }

    // no instantiation
    private Utils() {}
}
