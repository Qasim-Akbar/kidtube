"""plan_items.added_on, events.plan_item_id and supporting indexes

Revision ID: a1c4e7d90b21
Revises: 5e8b9df77b8c
"""
from alembic import op
import sqlalchemy as sa

revision = 'a1c4e7d90b21'
down_revision = '5e8b9df77b8c'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('plan_items', schema=None) as batch_op:
        batch_op.add_column(sa.Column('added_on', sa.Date(), nullable=True))
    # Existing rows: the UTC date of created_at (the family timezone is not available inside a migration).
    # date(...) exists on both SQLite and PostgreSQL.
    op.execute("UPDATE plan_items SET added_on = date(created_at)")
    with op.batch_alter_table('plan_items', schema=None) as batch_op:
        batch_op.alter_column('added_on', existing_type=sa.Date(), nullable=False)

    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.add_column(sa.Column('plan_item_id', sa.Uuid(), nullable=True))
        batch_op.create_index(
            'ix_events_child_id_type_plan_item_id_local_date',
            ['child_id', 'type', 'plan_item_id', 'local_date'], unique=False,
        )
        batch_op.create_index('ix_events_child_id_type_local_date', ['child_id', 'type', 'local_date'], unique=False)
    # No events existed before this migration (there was no endpoint writing them), so no plan_item_id backfill.


def downgrade() -> None:
    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.drop_index('ix_events_child_id_type_local_date')
        batch_op.drop_index('ix_events_child_id_type_plan_item_id_local_date')
        batch_op.drop_column('plan_item_id')
    with op.batch_alter_table('plan_items', schema=None) as batch_op:
        batch_op.drop_column('added_on')
