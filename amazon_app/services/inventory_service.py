from __future__ import annotations

from enum import Enum, auto
from typing import Dict, Optional
import logging
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError

from amazon_app.models.product import Product
from amazon_app.models.shipment import Shipment
from amazon_app.pb_generated.world_amazon_1_pb2 import ACommands as pb
from .world_client import ReliableChannel

logger = logging.getLogger(__name__)


class ShipmentStatus(str, Enum):
    PACKING = "packing"
    PACKED = "packed"
    LOADING = "loading"
    LOADED = "loaded"
    DELIVERING = "delivering"
    DELIVERED = "delivered"


class InventoryService:
    """High‑level façade combining DB operations and world/UPS side‑effects."""

    def __init__(self, db_session_factory, world_channel: ReliableChannel[pb.AResponses]):
        self._db_session_factory = db_session_factory
        self._world = world_channel

    # ------------------------------------------------------------------
    # Public API – called by Flask route handlers
    # ------------------------------------------------------------------

    def create_order(
        self,
        whnum: int,
        items: Dict[int, int],  # product_id -> cnt
        user_dest: tuple[int, int],
    ) -> int:
        """Insert DB rows, send APack to world, return *shipid*."""
        with self._db_session_factory() as db:  # type: Session
            # 1) Ensure inventory has enough stock; if not, raise 409
            for prod_id, cnt in items.items():
                prod: Product = db.get(Product, prod_id)
                if prod is None or prod.stock < cnt:
                    raise ValueError("Insufficient inventory for product %d" % prod_id)
            # 2) Allocate new shipid (auto‑inc seq in DB)
            ship = Shipment.create(db, whnum, items, user_dest)  # type: ignore[arg-type]
            db.commit()
        # 3) Send topack cmd
        apack = pb.APack(whnum=whnum, shipid=ship.id, seqnum=0)
        for pid, cnt in items.items():
            apack.things.add(id=pid, description="", count=cnt)
        self._world.send(apack)  # seqnum set inside
        logger.info("Order %d created & APack sent", ship.id)
        return ship.id

    def query_status(self, shipid: int) -> ShipmentStatus:
        with self._db_session_factory() as db:
            ship: Optional[Shipment] = db.get(Shipment, shipid)
            if ship is None:
                raise KeyError("Unknown shipment %d" % shipid)
            return ShipmentStatus(ship.status)

    # ------------------------------------------------------------------
    # World event processing – called by background consumer thread
    # ------------------------------------------------------------------

    def on_world_message(self, resp: pb.AResponses) -> None:
        """Handle *AResponses* and perform DB transitions (idempotent)."""
        with self._db_session_factory() as db:  # noqa: SIM117
            # 1) inventory arrived  ➜ increment stock
            for arrive in resp.arrived:
                for p in arrive.things:
                    self._inc_stock(db, p.id, p.count)
            # 2) ready / loaded state transitions
            for ready in resp.ready:
                self._update_status(db, ready.shipid, ShipmentStatus.PACKED)
            for loaded in resp.loaded:
                self._update_status(db, loaded.shipid, ShipmentStatus.LOADED)
            # 3) package status reply (queried)
            for pkg in resp.packagestatus:
                self._update_status(db, pkg.packageid, ShipmentStatus(pkg.status))
            db.commit()
            # ACK housekeeping
            for ack in resp.acks:
                self._world.mark_acked(ack)

    # --------------------------- helpers ------------------------------

    def _inc_stock(self, db: Session, pid: int, delta: int) -> None:
        prod: Product | None = db.get(Product, pid)
        if prod is None:
            prod = Product(id=pid, description="auto", stock=0)
            db.add(prod)
        prod.stock += delta

    def _update_status(self, db: Session, shipid: int, new_status: ShipmentStatus) -> None:
        ship: Shipment | None = db.get(Shipment, shipid)
        if ship is None:
            logger.warning("Received event for unknown shipment %d", shipid)
            return
        # idempotent: only advance if makes sense
        cur = ShipmentStatus(ship.status)
        order = [
            ShipmentStatus.PACKING,
            ShipmentStatus.PACKED,
            ShipmentStatus.LOADING,
            ShipmentStatus.LOADED,
            ShipmentStatus.DELIVERING,
            ShipmentStatus.DELIVERED,
        ]
        if order.index(new_status) > order.index(cur):
            ship.status = new_status.value
