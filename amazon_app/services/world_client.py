from __future__ import annotations
import logging
import threading
import queue
from typing import List, Optional
import amazon_app.pb_generated.world_amazon_1_pb2 as wam
from amazon_app.utils.reliable_channel import ChannelClosed, ReliableChannel

logger = logging.getLogger(__name__)


class WorldClient:

    def __init__(self, host: str, port: int, response_queue: queue.Queue):
        self._chan: ReliableChannel[wam.AResponses] = ReliableChannel(host, port)
        self._seq = 0
        self._lock = threading.Lock()
        self._running = False
        self._response_queue = response_queue

    def connect(self, warehouses: List[wam.AInitWarehouse], *, timeout: int = 30) -> Optional[int]:
        logger.info("Connecting to world %s:%d …", *self._chan.remote)
        try:
            conn = wam.AConnect(isAmazon=True)
            conn.initwh.extend(warehouses)
            self._chan.send(conn) # Removed .SerializeToString() assuming ReliableChannel handles it

            raw = self._chan.recv(timeout)
            if raw is None:
                logger.error(f"World simulator did not reply within {timeout}s")
                raise RuntimeError(f"World simulator did not reply within {timeout}s")

            ack = wam.AConnected()
            ack.ParseFromString(raw)
            if ack.result != "connected!":
                logger.error(f"Handshake rejected: {ack.result}")
                raise RuntimeError(f"Handshake rejected: {ack.result}")
            logger.info("Handshake OK, worldid=%d", ack.worldid)

            self._running = True
            threading.Thread(target=self._recv_loop, daemon=True).start()
            return ack.worldid
        except (RuntimeError, ConnectionRefusedError, Exception) as e:
             logger.exception(f"Failed to connect to world: {e}")
             self.shutdown()
             return None

    def buy(self, wh: int, prods: List[wam.AProduct]):
        self._enqueue_buy(wh, prods)

    def pack(self, wh: int, prods: List[wam.AProduct], shipid: int):
        self._enqueue_pack(wh, prods, shipid)

    def load(self, wh: int, truckid: int, shipid: int):
        self._enqueue_load(wh, truckid, shipid)

    def query(self, packageid: int):
        self._enqueue_query(packageid)

    def _next_seq(self) -> int:
        with self._lock:
            self._seq += 1
            return self._seq

    def _dispatch(self, cmd: wam.ACommands):
        if not self._running:
            logger.warning("Attempted to dispatch command but client is not running.")
            return
        try:
            # Add pending acks *before* sending the new command
            pending = self._chan.pending_acks()
            if pending:
                 cmd.acks.extend(pending)
            self._chan.send(cmd) # Assuming send handles serialization
        except ChannelClosed:
            logger.error("Cannot dispatch command: Channel is closed.")
            self.shutdown() # Mark as not running
        except Exception as e:
            logger.exception(f"Error dispatching command: {e}")
            # Decide if channel should be closed based on error type

    def _enqueue_buy(self, wh: int, prods: List[wam.AProduct]):
        cmd = wam.ACommands()
        r = cmd.buy.add()
        r.whnum = wh
        r.things.extend(prods)
        r.seqnum = self._next_seq()
        logger.info(f"Enqueuing BUY command (Seq={r.seqnum}): WH={wh}, {len(prods)} items")
        self._dispatch(cmd)

    def _enqueue_pack(self, wh: int, prods: List[wam.AProduct], ship: int):
        cmd = wam.ACommands()
        r = cmd.topack.add()
        r.whnum, r.shipid = wh, ship
        r.things.extend(prods)
        r.seqnum = self._next_seq()
        logger.info(f"Enqueuing PACK command (Seq={r.seqnum}): WH={wh}, ShipID={ship}, {len(prods)} items")
        self._dispatch(cmd)

    def _enqueue_load(self, wh: int, truck: int, ship: int):
        cmd = wam.ACommands()
        r = cmd.load.add()
        r.whnum, r.truckid, r.shipid = wh, truck, ship
        r.seqnum = self._next_seq()
        logger.info(f"Enqueuing LOAD command (Seq={r.seqnum}): WH={wh}, Truck={truck}, ShipID={ship}")
        self._dispatch(cmd)

    def _enqueue_query(self, package: int):
        cmd = wam.ACommands()
        r = cmd.queries.add()
        r.packageid = package
        r.seqnum = self._next_seq()
        logger.info(f"Enqueuing QUERY command (Seq={r.seqnum}): PackageID={package}")
        self._dispatch(cmd)

    def _recv_loop(self):
        logger.info("Starting WorldClient receive loop...")
        while self._running:
            try:
                # Use a shorter timeout to periodically check self._running
                raw = self._chan.recv(timeout=1.0)
                if raw is None:
                    continue # Timeout, loop again

                resp = wam.AResponses()
                resp.ParseFromString(raw)

                # ACK bookkeeping
                if resp.acks:
                    for a in resp.acks:
                        self._chan.mark_acked(a)
                    # logger.debug(f"Marked ACKs: {list(resp.acks)}")

                processed_data = self._process_responses(resp)
                if processed_data:
                    for item in processed_data:
                         logger.debug(f"Putting item on response queue: {item}")
                         self._response_queue.put(item)


                # Minimal logging
                if resp.error:
                    for e in resp.error:
                        logger.warning("World error: %s (seq=%d)", e.err, e.originseqnum)
                        # Also put errors on the queue
                        self._response_queue.put({
                            "type": "error",
                            "originseqnum": e.originseqnum,
                            "error_message": e.err,
                            "seqnum": e.seqnum # The error message seqnum itself
                        })

                if resp.finished:
                    logger.info("World requested shutdown -> disconnecting")
                    self._response_queue.put({"type": "finished"})
                    self.shutdown()
                    break

            except ChannelClosed:
                logger.info("Receive loop: Channel closed.")
                if self._running:
                     self._response_queue.put({"type": "disconnected"})
                self.shutdown()
                break
            except queue.Full:
                 logger.warning("Response queue is full. Responses may be lost.")
            except Exception as exc:
                logger.exception("Receive loop crashed: %s", exc)
                self._response_queue.put({"type": "recv_loop_error", "error": str(exc)})
                self.shutdown()
                break
        logger.info("Receive loop stopped.")

    def _process_responses(self, resp: wam.AResponses) -> List[dict]:
        items = []
        for item in resp.arrived:
            items.append({
                "type": "arrived",
                "whnum": item.whnum,
                "things": [{"id": p.id, "description": p.description, "count": p.count} for p in item.things],
                "seqnum": item.seqnum
            })
        for item in resp.ready:
             items.append({"type": "ready", "shipid": item.shipid, "seqnum": item.seqnum})
        for item in resp.loaded:
             items.append({"type": "loaded", "shipid": item.shipid, "seqnum": item.seqnum})
        for item in resp.packagestatus:
             items.append({"type": "packagestatus", "packageid": item.packageid, "status": item.status, "seqnum": item.seqnum})

        return items


    def shutdown(self):
        if not self._running:
            return
        logger.info("Shutting down WorldClient connection...")
        self._running = False
        try:
            if not self._chan.closed:
                 cmd = wam.ACommands(disconnect=True)
                 pending = self._chan.pending_acks()
                 if pending:
                      cmd.acks.extend(pending)
                 self._chan.send(cmd)
                 logger.info("Sent disconnect command.")
            else:
                 logger.info("Channel already closed, skipping disconnect command.")
        except Exception as e:
             logger.warning(f"Error sending disconnect command during shutdown: {e}")
        finally:
            self._chan.close()
            logger.info("WorldClient connection closed.")

    def get_reliable_channel(self):
        """Returns the ReliableChannel instance for the InventoryService."""
        return self._chan
