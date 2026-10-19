CREATE TABLE review_package (
    package_id TEXT PRIMARY KEY,
    request_id INTEGER NOT NULL,
    student_id TEXT NOT NULL,
    revision_hash TEXT NOT NULL,
    secret_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    response_hash TEXT UNIQUE,
    created_at TEXT NOT NULL,
    imported_at TEXT,
    FOREIGN KEY (request_id) REFERENCES human_review_request(id)
);

CREATE UNIQUE INDEX uq_review_package_active_request
ON review_package(request_id)
WHERE status = 'EXPORTED';

CREATE INDEX idx_review_package_student_status
ON review_package(student_id, status, created_at);
