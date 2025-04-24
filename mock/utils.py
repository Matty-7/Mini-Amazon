from google.protobuf.internal.encoder import _VarintBytes
from google.protobuf.internal.decoder import _DecodeVarint32

def send_msg_to(msg, out):
    body = msg.SerializeToString()
    header = _VarintBytes(len(body))
    out.sendall(header + body)

def recv_msg_from(msg_class, conn):
    buf = bytearray()
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            break
        buf += chunk
        try:
            msg_len, idx = _DecodeVarint32(buf, 0)
            if len(buf) - idx >= msg_len:
                msg = msg_class()
                msg.ParseFromString(buf[idx:idx + msg_len])
                return msg
        except IndexError:
            continue
