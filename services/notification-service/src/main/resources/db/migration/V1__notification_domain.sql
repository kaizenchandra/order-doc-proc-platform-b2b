-- A separate logical database and role; no dependency on the order database.
CREATE TABLE inbox_events (
    consumer_name varchar(100) NOT NULL CHECK (btrim(consumer_name) <> ''),
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
CREATE INDEX ix_inbox_retention ON inbox_events (processed_at);

CREATE TABLE audit_records (
    id uuid PRIMARY KEY,
    consumer_name varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL CHECK (btrim(event_type) <> ''),
    event_version integer NOT NULL CHECK (event_version > 0),
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    summary jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
    CONSTRAINT uq_audit_consumer_event UNIQUE (consumer_name, event_id)
);
CREATE INDEX ix_audit_tenant_aggregate ON audit_records (tenant_id, aggregate_id, recorded_at, id);

CREATE TABLE notification_records (
    id uuid PRIMARY KEY,
    audit_record_id uuid NOT NULL REFERENCES audit_records (id),
    channel varchar(24) NOT NULL CHECK (channel = 'IN_APP'),
    status varchar(24) NOT NULL CHECK (status = 'RECORDED'),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_notification_audit_channel UNIQUE (audit_record_id, channel)
);
-- No delivery timestamp: recording intent is not proof of email/SMS delivery.
-- Inbox has no retention-coupling FK; audit records survive inbox cleanup.
