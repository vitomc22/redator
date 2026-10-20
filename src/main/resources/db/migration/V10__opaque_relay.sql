CREATE TABLE opaque_relay_channel (
    id TEXT PRIMARY KEY,
    professor_token_hash TEXT NOT NULL UNIQUE,
    student_token_hash TEXT NOT NULL UNIQUE,
    request_blob BLOB NOT NULL,
    response_blob BLOB,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    request_read_at TEXT,
    response_created_at TEXT,
    response_read_at TEXT,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_opaque_relay_expiry ON opaque_relay_channel(expires_at);
