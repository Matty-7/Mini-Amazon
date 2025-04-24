"""High‑level *ReliableChannel* wrapper for the Mini‑Amazon/UPS World Simulator.

Key properties
--------------
* **Varint‑framed protobuf** – each outbound frame is prefixed with a Varint32
  length, exactly as required by the world server.
* **Exactly‑once semantics** – every *business* request carries a `seqnum`; the
  same frame is stored under those sequence numbers until an ACK arrives in a
  later `AResponses.acks` list. Frames are resent with exponential back‑off
  until ACKed or the connection is shut down.
* **Thread‑safe** – `.send()` may be called concurrently; a dedicated reader
  thread feeds a thread‑safe queue for callers to `.recv()`.

This refactor removes the earlier brittle `WhichOneof("")` call and aligns the
pending‑ACK bookkeeping with the World Simulator's contract: the server ACKs
**child request** `seqnum`s – not some hidden transport ID – so we map every
child's `seqnum` to the raw frame for retransmission.
"""

from __future__ import annotations

import logging
import socket
import threading
import time
from queue import Empty, Queue
from typing import Dict, Generic, List, Optional, TypeVar

from google.protobuf.internal.decoder import _DecodeVarint32
from google.protobuf.message import Message

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=Message)


class ChannelClosed(RuntimeError):
    """Raised when the underlying TCP connection is irrecoverably closed."""


class ReliableChannel(Generic[T]):
    """Bi‑directional *exactly‑once* channel with automatic retransmission."""

    RETRY_INTERVAL_S: float = 0.5  # initial back‑off base

    def __init__(self, host: str, port: int) -> None:
        self._sock = socket.create_connection((host, port))
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        self._tx_lock = threading.Lock()
        self._seq: int = 0  # transport‑level fallback seq allocator
        self._pending: Dict[int, bytes] = {}  # seqnum → raw frame

        self._rx_q: "Queue[bytes]" = Queue()
        self._shutdown = threading.Event()

        threading.Thread(target=self._recv_loop, daemon=True).start()
        threading.Thread(target=self._retransmit_loop, daemon=True).start()
        
    @property
    def remote(self):
        return self._sock.getpeername()
        
    @property
    def closed(self) -> bool:
        return self._shutdown.is_set()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def send(self, pb_msg: Message) -> List[int]:
        """Serialize *pb_msg*, transmit it, and return **all** seqnums carried."""
        with self._tx_lock:
            carried: List[int] = self._extract_seqnums(pb_msg)

            # If nothing carried (e.g. AConnect), allocate a transport id
            if not carried:
                self._seq += 1
                carried = [self._seq]
                if hasattr(pb_msg, "seqnum") and getattr(pb_msg, "seqnum") == 0:
                    setattr(pb_msg, "seqnum", carried[0])

            raw_frame = self._frame(pb_msg.SerializeToString())
            logger.info("Sending %s (seqs=%s)", pb_msg.DESCRIPTOR.name, carried)
            self._sock.sendall(raw_frame)

            for s in carried:
                self._pending[s] = raw_frame
            return carried

    def recv(self, timeout: Optional[float] = None) -> Optional[bytes]:
        try:
            return self._rx_q.get(timeout=timeout)
        except Empty:
            return None

    def close(self) -> None:
        self._shutdown.set()
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        finally:
            self._sock.close()

    def pending_acks(self) -> List[int]:
        return list(self._pending.keys())

    def mark_acked(self, ack: int) -> None:
        self._pending.pop(ack, None)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _extract_seqnums(self, msg: Message) -> List[int]:
        """Return **all** non‑zero `seqnum` fields contained in *msg*."""
        seqs: List[int] = []

        def _maybe_add(m):
            if hasattr(m, "seqnum"):
                val = getattr(m, "seqnum")
                if val:
                    seqs.append(val)

        if msg.DESCRIPTOR.name == "ACommands":
            for fld in msg.DESCRIPTOR.fields:
                if fld.label == fld.LABEL_REPEATED and fld.message_type:
                    for sub in getattr(msg, fld.name):
                        _maybe_add(sub)
        else:
            _maybe_add(msg)
        return seqs

    @staticmethod
    def _frame(body: bytes) -> bytes:
        size = len(body)
        header = bytearray()
        while size > 127:
            header.append((size & 0x7F) | 0x80)
            size >>= 7
        header.append(size & 0x7F)
        return bytes(header) + body

    # ----------------------------- background loops -------------------
    def _recv_loop(self) -> None:
        buf = bytearray()
        try:
            while not self._shutdown.is_set():
                chunk = self._sock.recv(4096)
                if not chunk:
                    lvl = logger.info if self._shutdown.is_set() else logger.error
                    lvl("TCP connection closed%s", " locally" if self._shutdown.is_set() else " by peer")
                    raise ChannelClosed()
                buf.extend(chunk)
                while True:
                    try:
                        msg_len, idx = _DecodeVarint32(buf, 0)
                    except IndexError:
                        break  # incomplete varint
                    if len(buf) - idx < msg_len:
                        break  # incomplete payload
                    payload = bytes(buf[idx : idx + msg_len])
                    del buf[: idx + msg_len]
                    self._rx_q.put(payload)
        except ChannelClosed:
            pass
        except Exception as exc:  # noqa: BLE001
            logger.exception("recv loop died: %s", exc)
        finally:
            self._shutdown.set()

    def _retransmit_loop(self) -> None:
        interval = self.RETRY_INTERVAL_S
        while not self._shutdown.is_set():
            time.sleep(interval)
            for seq, frame in list(self._pending.items()):
                try:
                    self._sock.sendall(frame)
                    logger.debug("Retransmitted seq=%d", seq)
                except OSError as exc:
                    logger.error("Retransmit failed: %s", exc)
                    self._shutdown.set()
                    break
            interval = min(interval * 2, 4 * self.RETRY_INTERVAL_S)
