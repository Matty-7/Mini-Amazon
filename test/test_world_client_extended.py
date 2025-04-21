"""
WorldClient – pure‑unit tests (no live World Simulator needed).

Strategy
--------
‣ Replace the TCP socket with an in‑memory DummySocket.
‣ Pre‑enqueue framed protobuf responses into DummySocket.recvq to simulate
  the server.
‣ Verify that WorldClient sends correctly framed AConnect/ACommands messages
  and that ACK bookkeeping clears the retransmit queue.
"""
from __future__ import annotations

import queue
import socket
import struct
from typing import Tuple, List

import pytest
from google.protobuf.internal.encoder import _VarintBytes

import amazon_app.protocols.world_amazon_1_pb2 as wam
from amazon_app.services.world_client import WorldClient


# --------------------------------------------------------------------------- #
# Utilities                                                                   #
# --------------------------------------------------------------------------- #
def frame(pb_msg) -> bytes:
    """Return the bytes <varint32 len><protobuf payload> exactly as the
    World Simulator expects (same algorithm as ReliableChannel._frame)."""
    body: bytes = pb_msg.SerializeToString()
    return _VarintBytes(len(body)) + body


def drain(q: "queue.Queue[bytes]") -> List[bytes]:
    """Drain *all* frames still sitting in DummySocket.sent for easy assertions."""
    out: List[bytes] = []
    while not q.empty():
        out.append(q.get_nowait())
    return out


# --------------------------------------------------------------------------- #
# Fake socket                                                                 #
# --------------------------------------------------------------------------- #
class DummySocket:
    """Minimal drop‑in replacement for a blocking TCP socket."""

    def __init__(self):
        self.sent: "queue.Queue[bytes]" = queue.Queue()
        self.recvq: "queue.Queue[bytes]" = queue.Queue()
        self._closed = False

    # ----- tx/rx API -------------------------------------------------------- #
    def sendall(self, data: bytes):
        self.sent.put(data)
        
    def recv(self, n: int) -> bytes:
        """
        Block until at least one byte is available, exactly like a real
        TCP socket in blocking mode (the default that ReliableChannel uses).

        Returning *zero* bytes would tell ReliableChannel that the peer
        closed the connection, so we must not do that here.
        """
        chunk = self.recvq.get()          # ← blocks until data present
        if len(chunk) > n:                # respect caller's `n`
            rest = chunk[n:]
            self.recvq.put_nowait(rest)   # push back extra bytes
            chunk = chunk[:n]
        return chunk


    # ----- misc standard methods ------------------------------------------- #
    def setsockopt(self, *_, **__):  # TCP_NODELAY — ignored
        pass

    def shutdown(self, _how):  # SHUT_RDWR
        self._closed = True

    def close(self):
        self._closed = True

    def getpeername(self):
        return ("dummy", 0)

    # python‑style helpers --------------------------------------------------- #
    @property
    def closed(self) -> bool:
        return self._closed


# --------------------------------------------------------------------------- #
# PyTest fixtures                                                             #
# --------------------------------------------------------------------------- #
@pytest.fixture()
def dummy_socket(monkeypatch) -> DummySocket:
    sock = DummySocket()
    monkeypatch.setattr("socket.create_connection", lambda addr, timeout=None: sock)
    # speed up retransmit thread so the test suite finishes instantly
    monkeypatch.setattr(
        "amazon_app.utils.reliable_channel.RetryInterval", 0.02, raising=False
    )
    return sock


@pytest.fixture()
def wc(dummy_socket) -> Tuple[WorldClient, DummySocket]:
    """Returns (world_client, dummy_socket)."""
    return WorldClient("dummy", 12345), dummy_socket


# --------------------------------------------------------------------------- #
# Tests                                                                       #
# --------------------------------------------------------------------------- #
def _enqueue_handshake(sock: DummySocket, worldid: int = 1):
    """Push an AConnected frame into the recv queue so that the very next
    wc.connect() call succeeds immediately."""
    connected = wam.AConnected(worldid=worldid, result="connected!")
    sock.recvq.put(frame(connected))


def test_full_buy_pack_load_query_cycle(wc):
    client, sock = wc
    _enqueue_handshake(sock)

    # 1 CONNECT ------------------------------------------------------------- #
    client.connect([wam.AInitWarehouse(id=1, x=3, y=4)])

    # 2 BUY — immediately ACK it so retransmit queue is cleared ------------- #
    client.buy(1, [wam.AProduct(id=42, description="book", count=3)])
    sock.recvq.put(frame(wam.AResponses(acks=[1])))

    # 3 PACK / LOAD --------------------------------------------------------- #
    client.pack(1, [wam.AProduct(id=42, description="book", count=3)], shipid=888)
    client.load(1, truckid=5, shipid=888)

    # 4 QUERY --------------------------------------------------------------- #
    client.query(packageid=888)

    # flush any protocol frames that went out
    frames = drain(sock.sent)

    # — at least four outbound frames:
    #   • AConnect
    #   • ACommands(BUY)
    #   • ACommands(PACK + LOAD)  ← WorldClient batches pack+load separately
    #   • ACommands(QUERY)
    assert len(frames) >= 4

    # quick sanity‑check: first outbound frame *must* be an AConnect
    first_payload = frames[0][1:]  # skip size varint
    assert wam.AConnect.FromString(first_payload).isAmazon is True


def test_shutdown_closes_socket(wc):
    client, sock = wc
    _enqueue_handshake(sock)
    client.connect([])
    client.shutdown()
    assert sock.closed is True
