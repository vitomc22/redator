CREATE TABLE training_card (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL,
    essay_id INTEGER NOT NULL,
    competence TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    source_run_id INTEGER,
    repetitions INTEGER NOT NULL DEFAULT 0,
    interval_days INTEGER NOT NULL DEFAULT 0,
    ease_factor REAL NOT NULL DEFAULT 2.5,
    due_at TEXT NOT NULL,
    last_reviewed_at TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (essay_id) REFERENCES essay(id),
    FOREIGN KEY (source_run_id) REFERENCES run(id),
    UNIQUE(student_id, essay_id, competence)
);

CREATE TABLE training_card_review (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_id INTEGER NOT NULL,
    quality INTEGER NOT NULL,
    previous_interval_days INTEGER NOT NULL,
    next_interval_days INTEGER NOT NULL,
    reviewed_at TEXT NOT NULL,
    FOREIGN KEY (card_id) REFERENCES training_card(id)
);

CREATE INDEX idx_training_card_due ON training_card(student_id, due_at);
