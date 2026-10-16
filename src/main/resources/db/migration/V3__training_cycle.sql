ALTER TABLE essay ADD COLUMN student_id TEXT NOT NULL DEFAULT 'pilot-student';
ALTER TABLE essay ADD COLUMN title TEXT;
ALTER TABLE essay ADD COLUMN source_type TEXT NOT NULL DEFAULT 'text';
ALTER TABLE essay ADD COLUMN group_id INTEGER;
ALTER TABLE essay ADD COLUMN parent_essay_id INTEGER;
ALTER TABLE essay ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;
ALTER TABLE essay ADD COLUMN revision_hash TEXT;
ALTER TABLE essay ADD COLUMN approved_at TEXT;
ALTER TABLE essay ADD COLUMN frozen INTEGER NOT NULL DEFAULT 0;

UPDATE essay SET group_id = id WHERE group_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_essay_student_group ON essay(student_id, group_id, attempt_number);

CREATE TABLE human_review_request (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    essay_id INTEGER NOT NULL,
    essay_revision TEXT NOT NULL,
    reference_run_id INTEGER,
    status TEXT NOT NULL,
    reviewer_id TEXT,
    requested_at TEXT NOT NULL,
    claimed_at TEXT,
    completed_at TEXT,
    cancelled_at TEXT,
    FOREIGN KEY (essay_id) REFERENCES essay(id),
    FOREIGN KEY (reference_run_id) REFERENCES run(id)
);

CREATE UNIQUE INDEX uq_active_human_review_per_essay
ON human_review_request(essay_id)
WHERE status IN ('REQUESTED', 'IN_REVIEW');

CREATE TABLE human_evaluation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id INTEGER NOT NULL,
    version INTEGER NOT NULL,
    status TEXT NOT NULL,
    reviewer_id TEXT NOT NULL,
    scores_json TEXT,
    comments_json TEXT,
    main_guidance TEXT,
    total INTEGER,
    rubric_version TEXT NOT NULL,
    revision_reason TEXT,
    created_at TEXT NOT NULL,
    published_at TEXT,
    FOREIGN KEY (request_id) REFERENCES human_review_request(id),
    UNIQUE(request_id, version)
);

CREATE TABLE student_checklist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    essay_id INTEGER NOT NULL,
    competence TEXT NOT NULL,
    item_key TEXT NOT NULL,
    checked INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (essay_id) REFERENCES essay(id),
    UNIQUE(essay_id, competence, item_key)
);
