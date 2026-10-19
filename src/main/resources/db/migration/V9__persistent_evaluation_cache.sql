CREATE TABLE evaluation_cache (
    cache_key TEXT PRIMARY KEY,
    output_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_accessed_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE INDEX idx_evaluation_cache_expiry ON evaluation_cache(expires_at);
CREATE INDEX idx_evaluation_cache_access ON evaluation_cache(last_accessed_at);
