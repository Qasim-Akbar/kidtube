"""add pairing codes

Revision ID: 5e8b9df77b8c
Revises: fcc8f247cde3
Create Date: 2026-11-05 12:26:40.004546
"""
from alembic import op
import sqlalchemy as sa


revision = '5e8b9df77b8c'
down_revision = 'fcc8f247cde3'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('pairing_codes',
    sa.Column('family_id', sa.Uuid(), nullable=False),
    sa.Column('created_by', sa.Uuid(), nullable=True),
    sa.Column('code_hash', sa.String(length=64), nullable=False),
    sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('used_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.ForeignKeyConstraint(['created_by'], ['parents.id'], ondelete='SET NULL'),
    sa.ForeignKeyConstraint(['family_id'], ['families.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('pairing_codes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_pairing_codes_code_hash'), ['code_hash'], unique=False)
        batch_op.create_index(batch_op.f('ix_pairing_codes_family_id'), ['family_id'], unique=False)



def downgrade() -> None:
    with op.batch_alter_table('pairing_codes', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_pairing_codes_family_id'))
        batch_op.drop_index(batch_op.f('ix_pairing_codes_code_hash'))

    op.drop_table('pairing_codes')
