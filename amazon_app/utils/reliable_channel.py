import socket
import struct
import threading
import time
import logging
from queue import Queue, Empty
from typing import Dict, Optional, Callable, TypeVar, Generic

from google.protobuf.message import Message
from google.protobuf.internal.decoder import _DecodeVarint32
from google.protobuf.internal.encoder import _EncodeVarint

logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

# ---------------------------------------------------------------------------
# Public‑facing types
# ---------------------------------------------------------------------------
T = TypeVar("T", bound=Message)


class ChannelClosed(RuntimeError):
    """Raised when the underlying TCP connection is irrecoverably closed."""


# ---------------------------------------------------------------------------
# ReliableChannel – thin wrapper around a TCP socket that enforces
# (1) varint‑prefixed Protobuf framing and (2) seqnum/ack 重试语义。
# ---------------------------------------------------------------------------


class ReliableChannel(Generic[T]):
    """Bidirectional *exactly‑once* channel to the World Simulator.

    ▸ Handles varint32 framing (GPB requirement)
    ▸ Maintains monotonically increasing seqnum (int)
    ▸ Retransmits un‑acked msgs with exponential backoff
    ▸ Dispatches all inbound msgs onto a thread‑safe Queue
    """

    RETRY_INTERVAL_S: float = 0.5  # Initial retransmission interval
    MAX_RETRIES: int = 5

    def __init__(self, host: str, port: int):
        self._sock = socket.create_connection((host, port))
        # Disable Nagle to reduce latency of tiny ACK frames.
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        self._tx_lock = threading.Lock()  # protects _seq & socket writes
        self._seq: int = 0
        self._pending: Dict[int, bytes] = {}  # seqnum -> raw bytes awaiting ACK

        self._rx_queue: "Queue[bytes]" = Queue()
        self._shutdown = threading.Event()

        # Start background threads
        threading.Thread(target=self._recv_loop, daemon=True).start()
        threading.Thread(target=self._retransmit_loop, daemon=True).start()

    # ---------------------------------------------------------------------
    # Public API
    # ---------------------------------------------------------------------

    def send(self, pb_msg: T, *, ack_for: Optional[int] = None) -> int:
        """Serialize & send *pb_msg* and return its seqnum."""
        with self._tx_lock:
            self._seq += 1
            seq = self._seq
            # Reflection: assume message has attr 'seqnum' & optional 'acknum'.
            setattr(pb_msg, "seqnum", seq)
            if ack_for is not None:
                setattr(pb_msg, "acknum", ack_for)
            raw_body = pb_msg.SerializeToString()
            raw_framed = self._frame(raw_body)
            self._sock.sendall(raw_framed)
            self._pending[seq] = raw_framed
            logger.debug("Sent seq=%d bytes=%d", seq, len(raw_body))
            return seq

    def recv(self, timeout: Optional[float] = None) -> Optional[bytes]:
        """Return next raw protobuf payload (still need caller to ParseFromString)."""
        try:
            return self._rx_queue.get(timeout=timeout)
        except Empty:
            return None

    def close(self) -> None:
        self._shutdown.set()
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        finally:
            self._sock.close()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _frame(body: bytes) -> bytes:
        """Prefix‑encode *body* with varint32 length header."""
        header = []
        _EncodeVarint(header.append, len(body), None)  # type: ignore[arg-type]
        return bytes(header) + body

    def _recv_loop(self) -> None:
        buffer = bytearray()
        while not self._shutdown.is_set():
            try:
                chunk = self._sock.recv(4096)
                if not chunk:
                    raise ChannelClosed("Connection closed by peer")
                buffer.extend(chunk)
                # Process multiple frames that may co‑exist in buffer
                while True:
                    msg_len, idx = _DecodeVarint32(buffer, 0)
                    if idx == 0 or len(buffer) - idx < msg_len:
                        break  # incomplete frame, read more
                    start = idx
                    end = idx + msg_len
                    payload = bytes(buffer[start:end])
                    del buffer[:end]
                    self._rx_queue.put(payload)
            except (OSError, ChannelClosed) as e:
                logger.error("recv loop terminated: %s", e)
                self._shutdown.set()
                break

    def _retransmit_loop(self) -> None:
        """Resend un‑acked frames with backoff."""
        interval = self.RETRY_INTERVAL_S
        while not self._shutdown.is_set():
            time.sleep(interval)
            for seq, frame in list(self._pending.items()):
                logger.debug("Retransmitting seq=%d", seq)
                try:
                    self._sock.sendall(frame)
                except OSError as e:
                    logger.error("retransmit failed: %s", e)
                    self._shutdown.set()
                    break
            interval = min(interval * 2, 4 * self.RETRY_INTERVAL_S)

    # ------------------------------------------------------------------
    # ACK bookkeeping – should be invoked by caller after ParseFromString
    # ------------------------------------------------------------------

    def mark_acked(self, acknum: int) -> None:
        """Remove seqnum from retransmit pool when peer ACKs it."""
        self._pending.pop(acknum, None)
