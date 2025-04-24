import socket
import threading
from google.protobuf.internal.decoder import _DecodeVarint32
from google.protobuf.internal.encoder import _VarintBytes

import sys
import os

# Add the project root directory to the Python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from amazon_app.pb_generated import amazon_ups_pb2 as aup

PORT = 5002  # mock UPS server 监听端口

def send_protobuf_msg(sock, msg):
    data = msg.SerializeToString()
    header = _VarintBytes(len(data))
    sock.sendall(header + data)

def recv_protobuf_msg(sock, msg_cls):
    buf = bytearray()
    while True:
        chunk = sock.recv(4096)
        if not chunk:
            return None
        buf.extend(chunk)
        try:
            msg_len, idx = _DecodeVarint32(buf, 0)
            if len(buf) - idx >= msg_len:
                msg = msg_cls()
                msg.ParseFromString(buf[idx:idx + msg_len])
                return msg
        except IndexError:
            continue

def handle_client(client_socket):
    while True:
        msg = recv_protobuf_msg(client_socket, aup.AmazonToUPS)
        if msg is None:
            print("Amazon closed connection.")
            break

        print("Received from Amazon:", msg)

        response = aup.UPSToAmazon()

        # Pickup Response + TruckArrived
        if msg.request_pickup:
            for req in msg.request_pickup:
                pr = response.pickup_resp.add()
                pr.seqnum = req.seqnum
                pr.package_id = req.order_id
                pr.order_id = req.order_id
                pr.truck_id = req.order_id % 3 + 1

                ta = response.truck_arrived.add()
                ta.seqnum = req.seqnum
                ta.package_id = req.order_id
                ta.truck_id = pr.truck_id
                ta.warehouse_id = req.warehouse_id

                response.acks.append(req.seqnum)

        # Load Ready → DeliveryStarted + DeliveryComplete
        if msg.load_ready:
            for lr in msg.load_ready:
                ds = response.delivery_started.add()
                ds.seqnum = lr.seqnum
                ds.package_id = lr.package_id

                dc = response.delivery_complete.add()
                dc.seqnum = lr.seqnum
                dc.package_id = lr.package_id

                response.acks.append(lr.seqnum)

        # Redirect support
        if msg.redirect:
            for req in msg.redirect:
                rr = response.redirect_resp.add()
                rr.seqnum = req.seqnum
                rr.package_id = req.package_id
                rr.success = True
                rr.reason = "redirected successfully"
                response.acks.append(req.seqnum)

        # Cancel support
        if msg.cancel:
            for req in msg.cancel:
                cr = response.cancel_resp.add()
                cr.seqnum = req.seqnum
                cr.package_id = req.package_id
                cr.success = True
                cr.reason = "cancelled successfully"
                response.acks.append(req.seqnum)

        # Deduplicate ACKs
        response.acks.extend(list(set(response.acks)))

        print("Sending response to Amazon:", response)
        send_protobuf_msg(client_socket, response)

    client_socket.close()

    while True:
        msg = recv_protobuf_msg(client_socket, aup.AmazonToUPS)
        if msg is None:
            print("Amazon closed connection.")
            break

        print("Received from Amazon:", msg)

        # 构造响应消息
        response = aup.UPSToAmazon()
        if msg.request_pickup:
            for req in msg.request_pickup:
                resp = response.pickup_resp.add()
                resp.seqnum = req.seqnum
                resp.package_id = req.order_id  # 模拟为订单号即为包裹号
                resp.order_id = req.order_id
                resp.truck_id = req.order_id % 3 + 1  # mock truck_id
                arrived = response.truck_arrived.add()
                arrived.seqnum = req.seqnum
                arrived.package_id = req.order_id
                arrived.truck_id = req.order_id % 3 + 1
                arrived.warehouse_id = req.warehouse_id


        if msg.load_ready:
            for lr in msg.load_ready:
                delivered = response.delivery_complete.add()
                delivered.seqnum = lr.seqnum
                delivered.package_id = lr.package_id
        
        if msg.redirect:
            for req in msg.redirect:
                resp = response.redirect_resp.add()
                resp.seqnum = req.seqnum
                resp.package_id = req.package_id
                resp.success = True
                resp.reason = "redirected successfully"
                response.acks.append(req.seqnum)

        if msg.cancel:
            for req in msg.cancel:
                resp = response.cancel_resp.add()
                resp.seqnum = req.seqnum
                resp.package_id = req.package_id
                resp.success = True
                resp.reason = "cancelled successfully"
                response.acks.append(req.seqnum)

        if msg.load_ready:
            for lr in msg.load_ready:
                # DeliveryStarted
                started = response.delivery_started.add()
                started.seqnum = lr.seqnum
                started.package_id = lr.package_id

                # DeliveryComplete
                complete = response.delivery_complete.add()
                complete.seqnum = lr.seqnum
                complete.package_id = lr.package_id

                response.acks.append(lr.seqnum)

        response.acks.extend([r.seqnum for r in msg.request_pickup])
        response.acks.extend([r.seqnum for r in msg.load_ready])

        print("Sending response to Amazon:", response)
        send_protobuf_msg(client_socket, response)

    client_socket.close()

def start_mock_ups():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(("localhost", PORT))
    server.listen(1)
    print(f"Mock UPS listening on port {PORT}")

    while True:
        client_sock, addr = server.accept()
        print(f"Accepted connection from {addr}")
        client_thread = threading.Thread(target=handle_client, args=(client_sock,))
        client_thread.start()

if __name__ == "__main__":
    start_mock_ups()
