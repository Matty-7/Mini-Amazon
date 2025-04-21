# world_client.py

from amazon_app.services.socket_handler import ReliableChannel
from test_connection import world_amazon_1_pb2 as world_pb

class WorldClient:
    def __init__(self, host: str, port: int):
        self.channel = ReliableChannel[world_pb.AResponses](host, port)
        self.pending_acks = set()

    def connect_to_world(self, warehouse_list):
        # 构造 AConnect 消息
        connect_msg = world_pb.AConnect()
        connect_msg.isAmazon = True
        for wh in warehouse_list:
            init = connect_msg.initwh.add()
            init.id = wh["id"]
            init.x = wh["x"]
            init.y = wh["y"]

        # 直接发送 connect_msg 而非 ACommands
        self.channel._sock.sendall(
            self.channel._frame(connect_msg.SerializeToString())
        )

        # 接收 AConnected 响应（不带 framing 封装）
        data = self.channel.recv()
        conn_resp = world_pb.AConnected()
        conn_resp.ParseFromString(data)
        print(f"Connected to world {conn_resp.worldid}, status={conn_resp.result}")
        return conn_resp.worldid

    def send_command(self, cmd: world_pb.ACommands) -> int:
        # 添加 ack 列表
        cmd.acks.extend(self.pending_acks)
        self.pending_acks.clear()
        return self.channel.send(cmd)

    def receive_response(self, timeout=1.0) -> world_pb.AResponses:
        data = self.channel.recv(timeout)
        if not data:
            return None
        response = world_pb.AResponses()
        response.ParseFromString(data)

        # 把 ack 记下以便之后发送
        for ack in response.acks:
            self.channel.mark_acked(ack)
        return response

    def mark_for_ack(self, seqnum: int):
        self.pending_acks.add(seqnum)

    def close(self):
        self.channel.close()
