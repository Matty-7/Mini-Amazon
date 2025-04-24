from sqlalchemy import Integer, Column
from .base import Base

class Warehouse(Base):
    __tablename__ = "warehouses"
    id = Column(Integer, primary_key=True)
    x  = Column(Integer, nullable=False)
    y  = Column(Integer, nullable=False) 
