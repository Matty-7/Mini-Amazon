"""init tables

Revision ID: 9a6e9d9d4f2c
Revises: 
Create Date: 2023-11-01 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '9a6e9d9d4f2c'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Create warehouses table
    op.create_table(
        'warehouses',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('x', sa.Integer(), nullable=False),
        sa.Column('y', sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint('id')
    )

    # Create products table
    op.create_table(
        'products',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('description', sa.String(), nullable=False),
        sa.Column('stock', sa.Integer(), nullable=False, server_default='0'),
        sa.PrimaryKeyConstraint('id')
    )

    # Create shipments table
    op.create_table(
        'shipments',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('whnum', sa.Integer(), nullable=False),
        sa.Column('items', sa.JSON(), nullable=False),
        sa.Column('dest_x', sa.Integer(), nullable=False),
        sa.Column('dest_y', sa.Integer(), nullable=False),
        sa.Column('status', sa.String(), nullable=False, server_default='packing'),
        sa.ForeignKeyConstraint(['whnum'], ['warehouses.id'], ),
        sa.PrimaryKeyConstraint('id')
    )


def downgrade() -> None:
    op.drop_table('shipments')
    op.drop_table('products')
    op.drop_table('warehouses') 
