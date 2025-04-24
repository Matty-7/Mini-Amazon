from sqlalchemy import Integer, Column, String, ForeignKey, JSON
from sqlalchemy.orm import relationship, Mapped, mapped_column
from .base import Base

class Shipment(Base):
    __tablename__ = "shipments"
    id: Mapped[int]          = mapped_column(Integer, primary_key=True)
    whnum: Mapped[int]       = mapped_column(Integer, ForeignKey("warehouses.id"))
    items: Mapped[dict]      = mapped_column(JSON)        # {product_id: qty}
    dest_x: Mapped[int]      = mapped_column(Integer)
    dest_y: Mapped[int]      = mapped_column(Integer)
    status: Mapped[str]      = mapped_column(String, default="packing")

    warehouse = relationship("Warehouse") 
