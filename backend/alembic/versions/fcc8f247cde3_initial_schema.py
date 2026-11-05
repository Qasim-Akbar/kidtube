"""initial schema

Revision ID: fcc8f247cde3
Revises: 
Create Date: 2026-11-05 12:21:02.857828
"""
from alembic import op
import sqlalchemy as sa


revision = 'fcc8f247cde3'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('families',
    sa.Column('name', sa.String(length=100), nullable=False),
    sa.Column('timezone', sa.String(length=64), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_table('children',
    sa.Column('family_id', sa.Uuid(), nullable=False),
    sa.Column('name', sa.String(length=100), nullable=False),
    sa.Column('avatar', sa.String(length=100), nullable=True),
    sa.Column('daily_app_minutes', sa.Integer(), nullable=True),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['family_id'], ['families.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('children', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_children_family_id'), ['family_id'], unique=False)

    op.create_table('devices',
    sa.Column('family_id', sa.Uuid(), nullable=False),
    sa.Column('name', sa.String(length=100), nullable=False),
    sa.Column('role', sa.Enum('parent', 'kid', name='devicerole', native_enum=False, length=20), nullable=False),
    sa.Column('token_hash', sa.String(length=255), nullable=True),
    sa.Column('paired_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['family_id'], ['families.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('token_hash')
    )
    with op.batch_alter_table('devices', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_devices_family_id'), ['family_id'], unique=False)

    op.create_table('parents',
    sa.Column('family_id', sa.Uuid(), nullable=False),
    sa.Column('email', sa.String(length=255), nullable=False),
    sa.Column('password_hash', sa.String(length=255), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['family_id'], ['families.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('email')
    )
    with op.batch_alter_table('parents', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_parents_family_id'), ['family_id'], unique=False)

    op.create_table('events',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('child_id', sa.Uuid(), nullable=False),
    sa.Column('device_id', sa.Uuid(), nullable=True),
    sa.Column('type', sa.Enum('view_complete', 'watch_progress', 'app_session', 'playback_error', name='eventtype', native_enum=False, length=20), nullable=False),
    sa.Column('occurred_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('local_date', sa.Date(), nullable=False),
    sa.Column('payload', sa.JSON(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['child_id'], ['children.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ondelete='SET NULL'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.create_index('ix_events_child_id_local_date', ['child_id', 'local_date'], unique=False)
        batch_op.create_index('ix_events_child_id_type_occurred_at', ['child_id', 'type', 'occurred_at'], unique=False)

    op.create_table('plans',
    sa.Column('child_id', sa.Uuid(), nullable=False),
    sa.Column('pace_type', sa.Enum('videos_per_day', 'minutes_per_day', name='pacetype', native_enum=False, length=20), nullable=False),
    sa.Column('pace_value', sa.Integer(), nullable=False),
    sa.Column('active_weekdays', sa.Integer(), nullable=False),
    sa.Column('max_extra_per_day', sa.Integer(), nullable=False),
    sa.Column('start_date', sa.Date(), nullable=False),
    sa.Column('end_date', sa.Date(), nullable=True),
    sa.Column('version', sa.Integer(), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['child_id'], ['children.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('child_id')
    )
    op.create_table('videos',
    sa.Column('family_id', sa.Uuid(), nullable=False),
    sa.Column('platform', sa.Enum('youtube', 'facebook', name='platform', native_enum=False, length=20), nullable=False),
    sa.Column('platform_video_id', sa.String(length=64), nullable=False),
    sa.Column('url', sa.String(length=2048), nullable=False),
    sa.Column('title', sa.String(length=500), nullable=True),
    sa.Column('thumbnail_url', sa.String(length=2048), nullable=True),
    sa.Column('duration_seconds', sa.Integer(), nullable=True),
    sa.Column('status', sa.Enum('pending', 'verified', 'unavailable', name='videostatus', native_enum=False, length=20), nullable=False),
    sa.Column('added_by', sa.Uuid(), nullable=True),
    sa.Column('added_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['added_by'], ['parents.id'], ondelete='SET NULL'),
    sa.ForeignKeyConstraint(['family_id'], ['families.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('family_id', 'platform', 'platform_video_id', name='uq_videos_family_platform_video')
    )
    with op.batch_alter_table('videos', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_videos_family_id'), ['family_id'], unique=False)

    op.create_table('plan_items',
    sa.Column('plan_id', sa.Uuid(), nullable=False),
    sa.Column('video_id', sa.Uuid(), nullable=False),
    sa.Column('position', sa.Integer(), nullable=False),
    sa.Column('required', sa.Boolean(), nullable=False),
    sa.Column('view_target', sa.Integer(), nullable=True),
    sa.Column('daily_cap', sa.Integer(), nullable=True),
    sa.Column('watch_threshold', sa.Float(), nullable=False),
    sa.Column('anchor_date', sa.Date(), nullable=True),
    sa.Column('on_miss', sa.Enum('shift', 'skip', 'drop', name='onmiss', native_enum=False, length=20), nullable=False),
    sa.Column('status', sa.Enum('active', 'completed', 'dropped', name='planitemstatus', native_enum=False, length=20), nullable=False),
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['plan_id'], ['plans.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['video_id'], ['videos.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('plan_items', schema=None) as batch_op:
        batch_op.create_index('ix_plan_items_plan_id_position', ['plan_id', 'position'], unique=False)
        batch_op.create_index(batch_op.f('ix_plan_items_video_id'), ['video_id'], unique=False)



def downgrade() -> None:
    with op.batch_alter_table('plan_items', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_plan_items_video_id'))
        batch_op.drop_index('ix_plan_items_plan_id_position')

    op.drop_table('plan_items')
    with op.batch_alter_table('videos', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_videos_family_id'))

    op.drop_table('videos')
    op.drop_table('plans')
    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.drop_index('ix_events_child_id_type_occurred_at')
        batch_op.drop_index('ix_events_child_id_local_date')

    op.drop_table('events')
    with op.batch_alter_table('parents', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_parents_family_id'))

    op.drop_table('parents')
    with op.batch_alter_table('devices', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_devices_family_id'))

    op.drop_table('devices')
    with op.batch_alter_table('children', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_children_family_id'))

    op.drop_table('children')
    op.drop_table('families')
