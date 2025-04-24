from amazon_app.models.base import Base
from amazon_app.db import engine, SessionLocal
from amazon_app.models.product import Product

def test_create_product():
    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        p = Product(id=1, description="book")
        db.add(p); db.commit()
        assert db.get(Product, 1).description == "book" 
