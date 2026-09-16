-- PostgreSQL-specific schema; Flyway owns DDL, Hibernate only validates it.
CREATE TABLE orders
(
    id                 uuid PRIMARY KEY,
    tenant_id          uuid           NOT NULL,
    customer_id        uuid           NOT NULL,
    customer_reference varchar(100)   NOT NULL CHECK (btrim(customer_reference) <> ''),
    total_amount       numeric(19, 2) NOT NULL CHECK (total_amount > 0),
    currency           varchar(3)     NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP', 'INR')),
    status             varchar(24)    NOT NULL CHECK (status IN ('CREATED', 'CONFIRMED', 'FULFILLED', 'CANCELLED')),
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL CHECK (updated_at >= created_at),
    version            bigint         NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_order_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX ix_orders_tenant_created ON orders (tenant_id, created_at DESC, id);

CREATE TABLE order_documents
(
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL,
    order_id              uuid         NOT NULL,
    file_name             varchar(255) NOT NULL CHECK (btrim(file_name) <> ''),
    declared_content_type varchar(100) NOT NULL CHECK (btrim(declared_content_type) <> ''),
    bucket                varchar(222) NOT NULL CHECK (btrim(bucket) <> ''),
    object_name           varchar(512) NOT NULL CHECK (btrim(object_name) <> ''),
    status                varchar(24)  NOT NULL CHECK (status IN
                                                       ('AWAITING_UPLOAD', 'QUEUED', 'PROCESSED', 'FAILED', 'EXPIRED')),
    upload_expires_at     timestamptz  NOT NULL,
    object_generation     bigint CHECK (object_generation > 0),
    size_bytes            bigint CHECK (size_bytes > 0 AND size_bytes <= 26214400),
    processing_request_id uuid,
    processor_version     varchar(32),
    queued_at             timestamptz,
    completed_at          timestamptz,
    report_bucket         varchar(222),
    report_object_name    varchar(512),
    report_generation     bigint CHECK (report_generation > 0),
    sha256                varchar(64) CHECK (sha256 ~ '^[0-9a-f]{64}$'
) ,
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_document_order_tenant FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id),
    CONSTRAINT uq_document_object UNIQUE (bucket, object_name),
    CONSTRAINT uq_processing_request UNIQUE (processing_request_id),
    CONSTRAINT ck_upload_window CHECK (upload_expires_at > created_at),
    CONSTRAINT ck_processing_identity CHECK (
        (status IN ('AWAITING_UPLOAD','EXPIRED') AND object_generation IS NULL AND size_bytes IS NULL
         AND processing_request_id IS NULL AND processor_version IS NULL AND queued_at IS NULL)
        OR (status IN ('QUEUED','PROCESSED','FAILED') AND object_generation IS NOT NULL
         AND size_bytes IS NOT NULL AND processing_request_id IS NOT NULL
         AND processor_version IS NOT NULL AND btrim(processor_version) <> ''
         AND queued_at IS NOT NULL AND queued_at >= created_at)
    ),
    CONSTRAINT ck_processing_result CHECK (
        (status IN ('AWAITING_UPLOAD','EXPIRED','QUEUED') AND completed_at IS NULL
         AND report_bucket IS NULL AND report_object_name IS NULL AND report_generation IS NULL
         AND sha256 IS NULL AND failure_code IS NULL)
        OR (status = 'PROCESSED' AND completed_at IS NOT NULL AND completed_at >= queued_at
         AND report_bucket IS NOT NULL AND btrim(report_bucket) <> ''
         AND report_object_name IS NOT NULL AND btrim(report_object_name) <> ''
         AND report_generation IS NOT NULL AND sha256 IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND completed_at >= queued_at
         AND report_bucket IS NOT NULL AND btrim(report_bucket) <> ''
         AND report_object_name IS NOT NULL AND btrim(report_object_name) <> ''
         AND report_generation IS NOT NULL AND sha256 IS NULL
         AND failure_code IS NOT NULL AND btrim(failure_code) <> '')
    )
);
CREATE INDEX ix_documents_order ON order_documents (tenant_id, order_id, created_at, id);
CREATE INDEX ix_documents_awaiting_expiry ON order_documents (upload_expires_at) WHERE status = 'AWAITING_UPLOAD';
CREATE INDEX ix_documents_queued_age ON order_documents (queued_at) WHERE status = 'QUEUED';

CREATE TABLE outbox_events
(
    event_id        uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL,
    aggregate_id    uuid         NOT NULL,
    event_type      varchar(100) NOT NULL CHECK (btrim(event_type) <> ''),
    event_version   integer      NOT NULL CHECK (event_version > 0),
    correlation_id  uuid         NOT NULL,
    causation_id    uuid,
    occurred_at     timestamptz  NOT NULL,
    source          varchar(100) NOT NULL CHECK (btrim(source) <> ''),
    destination     varchar(100) NOT NULL CHECK (btrim(destination) <> ''),
    data            jsonb        NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    traceparent     varchar(128),
    available_at    timestamptz  NOT NULL,
    attempt_count   integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    claim_token     uuid,
    lease_until     timestamptz,
    published_at    timestamptz,
    last_error_code varchar(100),
    CONSTRAINT ck_outbox_lease CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_outbox_published CHECK (published_at IS NULL OR (claim_token IS NULL AND lease_until IS NULL))
);
-- No FK to orders: event retention and aggregate deletion have independent lifecycles.
CREATE INDEX ix_outbox_due ON outbox_events (available_at, occurred_at, event_id) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_cleanup ON outbox_events (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE inbox_events
(
    consumer_name varchar(100) NOT NULL CHECK (btrim(consumer_name) <> ''),
    event_id      uuid         NOT NULL,
    processed_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
CREATE INDEX ix_inbox_retention ON inbox_events (processed_at);

CREATE TABLE api_idempotency
(
    tenant_id       uuid         NOT NULL,
    operation       varchar(200) NOT NULL CHECK (btrim(operation) <> ''),
    idempotency_key varchar(128) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash    varchar(64)  NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'
) ,
    resource_id uuid NOT NULL,
    response_status integer NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_body jsonb NOT NULL CHECK (jsonb_typeof(response_body) = 'object'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (tenant_id, operation, idempotency_key)
);
CREATE INDEX ix_idempotency_retention ON api_idempotency (expires_at);
