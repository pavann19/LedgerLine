-- Durable obligations are separate from successfully processed events.
CREATE TABLE projection_quarantine (
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    payload text,
    payload_sha256 char(64) NOT NULL,
    failure_class varchar(128) NOT NULL,
    parked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(topic, partition_id, record_offset),
    CHECK (payload IS NULL OR octet_length(payload) <= 1048576)
);
COMMENT ON TABLE projection_quarantine IS
    'Unresolved reconciliation obligations, never equivalent to successful projection. Raw payload is restricted operator evidence, never agent prompt content.';
