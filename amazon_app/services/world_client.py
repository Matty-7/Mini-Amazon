"""Typed *WorldClient* helper wrapping ReliableChannel for Mini‑Amazon.

It speaks the protobuf schema you pasted (proto2) and exposes the four high‑
level operations: **buy / pack / load / query**.  Sequence numbers and ACK
book‑keeping now exactly match the World Simulator’s expectations.
"""

from __future__ import annotations

import logging
import threading
from typing import List

import amazon_app.protocols.world_amazon_1_pb2 as wam
from amazon_app.utils.reliable_channel import ChannelClosed, ReliableChannel

logger = logging.getLogger(__name__)


class WorldClient:  # ---------------------------------------------------------
    """High‑level façade – one instance per TCP connection."""

    def __init__(self, host: str, port: int):
        self._chan: ReliableChannel[wam.AResponses] = ReliableChannel(host, port)
        self._seq = 0
        self._lock = threading.Lock()
        self._running = False  # flipped to *True* only after handshake

    # ───────────────────────────────── handshake ────────────────────────────
    def connect(self, warehouses: List[wam.AInitWarehouse], *, timeout: int = 30) -> int:
        """Send *AConnect* and return the worldid on success."""
        logger.info("Connecting to world %s:%d …", *self._chan.remote)

        conn = wam.AConnect(isAmazon=True)
        conn.initwh.extend(warehouses)
        self._chan.send(conn)

        raw = self._chan.recv(timeout)
        if raw is None:
            raise RuntimeError(f"World simulator did not reply within {timeout}s")

        ack = wam.AConnected()
        ack.ParseFromString(raw)
        if ack.result != "connected!":
            raise RuntimeError(f"Handshake rejected: {ack.result}")
        logger.info("Handshake OK, worldid=%d", ack.worldid)

        self._running = True
        threading.Thread(target=self._recv_loop, daemon=True).start()
        return ack.worldid

    # ───────────────────────────────── public helpers ───────────────────────
    def buy(self, wh: int, prods: List[wam.AProduct]):
        self._enqueue_buy(wh, prods)

    def pack(self, wh: int, prods: List[wam.AProduct], shipid: int):
        self._enqueue_pack(wh, prods, shipid)

    def load(self, wh: int, truckid: int, shipid: int):
        self._enqueue_load(wh, truckid, shipid)

    def query(self, packageid: int):
        self._enqueue_query(packageid)

    # ───────────────────────────── internal builders ────────────────────────
    def _next_seq(self) -> int:
        with self._lock:
            self._seq += 1
            return self._seq

    def _dispatch(self, cmd: wam.ACommands):
        cmd.acks.extend(self._chan.pending_acks())
        self._chan.send(cmd)

    def _enqueue_buy(self, wh: int, prods: List[wam.AProduct]):
        cmd = wam.ACommands()
        r = cmd.buy.add()
        r.whnum = wh
        r.things.extend(prods)
        r.seqnum = self._next_seq()
        self._dispatch(cmd)

    def _enqueue_pack(self, wh: int, prods: List[wam.AProduct], ship: int):
        cmd = wam.ACommands()
        r = cmd.topack.add()
        r.whnum, r.shipid = wh, ship
        r.things.extend(prods)
        r.seqnum = self._next_seq()
        self._dispatch(cmd)

    def _enqueue_load(self, wh: int, truck: int, ship: int):
        cmd = wam.ACommands()
        r = cmd.load.add()
        r.whnum, r.truckid, r.shipid = wh, truck, ship
        r.seqnum = self._next_seq()
        self._dispatch(cmd)

    def _enqueue_query(self, package: int):
        cmd = wam.ACommands()
        r = cmd.queries.add()
        r.packageid = package
        r.seqnum = self._next_seq()
        self._dispatch(cmd)

    # ───────────────────────────── receive loop ────────────────────────────
    def _recv_loop(self):
        while self._running:
            try:
                raw = self._chan.recv(timeout=1)
                if raw is None:
                    continue
                resp = wam.AResponses()
                resp.ParseFromString(raw)

                # ACK bookkeeping
                for a in resp.acks:
                    self._chan.mark_acked(a)

                # minimal logging – you can expand as desired
                if resp.error:
                    for e in resp.error:
                        logger.warning("World error: %s (seq=%d)", e.err, e.originseqnum)

                if resp.finished:
                    logger.info("World requested shutdown → disconnecting")
                    self.shutdown()
            except ChannelClosed:
                break
            except Exception as exc:  # noqa: BLE001
                logger.exception("recv‑loop crash: %s", exc)

    # ───────────────────────────── graceful close ──────────────────────────
    def shutdown(self):
        if not self._running:
            return
        try:
            cmd = wam.ACommands(disconnect=True)
            self._chan.send(cmd)
        finally:
            self._running = False
            self._chan.close()