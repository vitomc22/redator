CREATE TABLE IF NOT EXISTS tema (
    id INTEGER PRIMARY KEY,
    titulo TEXT NOT NULL,
    recorte TEXT NOT NULL,
    motivadores TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS essay (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tema_id INTEGER NOT NULL,
    text TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (tema_id) REFERENCES tema(id)
);

CREATE TABLE IF NOT EXISTS run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    essay_id INTEGER NOT NULL,
    profile TEXT NOT NULL,
    total INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (essay_id) REFERENCES essay(id)
);

CREATE TABLE IF NOT EXISTS competence_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    competencia TEXT NOT NULL,
    nota INTEGER NOT NULL,
    nivel_sugerido INTEGER NOT NULL,
    evidencias TEXT,
    problemas TEXT,
    needs_review INTEGER NOT NULL DEFAULT 0,
    motivos TEXT,
    FOREIGN KEY (run_id) REFERENCES run(id)
);

CREATE TABLE IF NOT EXISTS llm_call (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER,
    provider TEXT,
    model TEXT,
    prompt_hash TEXT,
    schema_hash TEXT,
    cost_usd REAL,
    latency_ms INTEGER,
    status TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS human_score (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    essay_id INTEGER NOT NULL,
    competencia TEXT NOT NULL,
    nota INTEGER NOT NULL,
    avaliador TEXT NOT NULL,
    fonte TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (essay_id) REFERENCES essay(id)
);
