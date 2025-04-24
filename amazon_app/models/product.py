from sqlalchemy import Integer, Column, String
from .base import Base

class Product(Base):
    __tablename__ = "products"
    id          = Column(Integer, primary_key=True)
    description = Column(String, nullable=False)
    stock       = Column(Integer, default=0) 
