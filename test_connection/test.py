import socket
import world_amazon_1_pb2
from google.protobuf.internal.encoder import _VarintEncoder
from google.protobuf.internal.decoder import _DecodeVarint
import sys

def send_protobuf_message(sock, message):
    msg_bytes = message.SerializeToString()
    varint_buff = []
    _VarintEncoder()(varint_buff.append, len(msg_bytes), False)
    sock.sendall(b''.join(varint_buff) + msg_bytes)

def recv_protobuf_message(sock, message_type):
    varint_buff = b""
    while True:
        byte = sock.recv(1)
        if not byte:
            raise RuntimeError("Failed to read varint size (connection closed)")
        varint_buff += byte
        try:
            msg_len, _ = _DecodeVarint(varint_buff, 0)
            break
        except IndexError:
            continue

    msg_data = b""
    while len(msg_data) < msg_len:
        chunk = sock.recv(msg_len - len(msg_data))
        if not chunk:
            raise RuntimeError("Socket connection broken while receiving message")
        msg_data += chunk

    msg = message_type()
    msg.ParseFromString(msg_data)
    return msg

def main():
    HOST = "ece650-vm.colab.duke.edu"
    PORT = 23456  # Amazon port
    TIMEOUT = 5  # seconds

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(TIMEOUT)
            print(f"Connecting to {HOST}:{PORT}...")
            sock.connect((HOST, PORT))
            print("Connected.")

            connect_msg = world_amazon_1_pb2.AConnect()
            connect_msg.isAmazon = True
            wh = connect_msg.initwh.add()
            wh.id = 1
            wh.x = 3
            wh.y = 4

            send_protobuf_message(sock, connect_msg)
            print("Sent AConnect.")

            connected = recv_protobuf_message(sock, world_amazon_1_pb2.AConnected)
            print(f"✅ World connected: worldid = {connected.worldid}, result = {connected.result}")

    except Exception as e:
        print(f"❌ Connection failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
