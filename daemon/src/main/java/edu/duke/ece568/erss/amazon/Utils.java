package edu.duke.ece568.erss.amazon;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.GeneratedMessageV3;

import java.io.*;

public class Utils {

    /**
     * This function will "send" the data(should follow Google Protocol Buffer) to corresponding output stream.
     * You should handle the timeout by yourself.
     * @param msg the message to be send
     * @param out output stream
     * @param <T> generic type of the data
     * @return send result
     */
    public static <T extends GeneratedMessageV3> boolean sendMsgTo(T msg, OutputStream out) {
        try {
            byte[] data = msg.toByteArray();
            CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(out);
            codedOutputStream.writeUInt32NoTag(data.length);
            codedOutputStream.writeRawBytes(data);
            // NOTE!!! always flush the result to stream
            codedOutputStream.flush();
            return true;
        } catch (IOException e) {
            System.err.println("sendToWorld: " + e.toString());
            return false;
        }
    }

    /**
     * This function will only "read" the data from corresponding input stream.
     * You should handle the sendToWorld back ask by yourself.
     * @param response response(by reference)
     * @param in       input stream
     * @param <T>      generic type of the response
     * @return true receive successful
     */
    public static <T extends GeneratedMessageV3.Builder<?>> boolean recvMsgFrom(T response, InputStream in) {
        try {
            CodedInputStream codedInputStream = CodedInputStream.newInstance(in);
            int len = codedInputStream.readRawVarint32();
            int oldLimit = codedInputStream.pushLimit(len);
            response.mergeFrom(codedInputStream);
            codedInputStream.popLimit(oldLimit);
            return true;
        } catch (IOException e) {
            System.err.println("recv: " + e.toString());
            return false;
        }
    }
}
