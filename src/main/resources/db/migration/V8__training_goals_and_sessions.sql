CREATE TABLE training_goal (
    student_id TEXT PRIMARY KEY,
    daily_reviews INTEGER NOT NULL DEFAULT 5 CHECK (daily_reviews BETWEEN 1 AND 100),
    updated_at TEXT NOT NULL
);

CREATE TABLE training_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
    review_count INTEGER NOT NULL DEFAULT 0,
    started_at TEXT NOT NULL,
    completed_at TEXT
);

ALTER TABLE training_card_review ADD COLUMN session_id INTEGER REFERENCES training_session(id);

CREATE INDEX idx_training_session_student_started
    ON training_session(student_id, started_at);
