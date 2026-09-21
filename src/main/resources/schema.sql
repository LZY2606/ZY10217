PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY,
    part_name TEXT NOT NULL,
    cure_spec_version TEXT NOT NULL,
    fixture_version TEXT NOT NULL,
    fixture_sha256 TEXT,
    imported_at TEXT NOT NULL,
    input_summary TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS control_points (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    code TEXT NOT NULL,
    label TEXT NOT NULL,
    required INTEGER NOT NULL,
    selected INTEGER NOT NULL DEFAULT 0,
    UNIQUE(run_id, code)
);

CREATE TABLE IF NOT EXISTS material_batches (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    material_code TEXT NOT NULL,
    batch_no TEXT NOT NULL,
    out_time_minutes INTEGER NOT NULL,
    out_time_limit_minutes INTEGER NOT NULL,
    certificate_valid INTEGER NOT NULL,
    UNIQUE(run_id, batch_no)
);

CREATE TABLE IF NOT EXISTS design_plies (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL,
    material_code TEXT NOT NULL,
    design_angle INTEGER NOT NULL,
    nominal_weight_gpm2 INTEGER NOT NULL,
    UNIQUE(run_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS actual_plies (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    design_ply_id TEXT NOT NULL REFERENCES design_plies(id) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL,
    material_batch_id TEXT REFERENCES material_batches(id) ON DELETE SET NULL,
    laid_angle INTEGER NOT NULL,
    operator_id TEXT NOT NULL,
    laid_at TEXT NOT NULL,
    UNIQUE(run_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS cure_stages (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    code TEXT NOT NULL,
    label TEXT NOT NULL,
    sequence_no INTEGER NOT NULL,
    start_at TEXT,
    end_at TEXT,
    min_c INTEGER,
    max_c INTEGER,
    required_hold_seconds INTEGER,
    min_vacuum_kpa INTEGER,
    min_pressure_kpa INTEGER,
    UNIQUE(run_id, code)
);

CREATE TABLE IF NOT EXISTS sensors (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    channel TEXT NOT NULL,
    location TEXT NOT NULL,
    role TEXT NOT NULL,
    required INTEGER NOT NULL,
    active INTEGER NOT NULL,
    replaces_sensor_id TEXT,
    UNIQUE(run_id, channel)
);

CREATE TABLE IF NOT EXISTS temperature_samples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    sensor_id TEXT NOT NULL REFERENCES sensors(id) ON DELETE CASCADE,
    sample_at TEXT NOT NULL,
    temperature_c REAL NOT NULL,
    quality_flag TEXT NOT NULL DEFAULT 'OK',
    sequence_no INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sensor_events (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    sensor_id TEXT REFERENCES sensors(id) ON DELETE CASCADE,
    event_at TEXT NOT NULL,
    detail TEXT NOT NULL,
    confirmed INTEGER NOT NULL DEFAULT 0,
    confirmed_by TEXT,
    confirmed_at TEXT
);

CREATE TABLE IF NOT EXISTS process_samples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    sample_at TEXT NOT NULL,
    vacuum_kpa INTEGER NOT NULL,
    pressure_kpa INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS evaluations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    spec_version TEXT NOT NULL,
    overall_status TEXT NOT NULL,
    input_summary TEXT NOT NULL,
    evaluated_at TEXT NOT NULL,
    UNIQUE(run_id)
);

CREATE TABLE IF NOT EXISTS rule_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    evaluation_id INTEGER NOT NULL REFERENCES evaluations(id) ON DELETE CASCADE,
    rule_id TEXT NOT NULL,
    label TEXT NOT NULL,
    status TEXT NOT NULL,
    evidence_start TEXT,
    evidence_end TEXT,
    detail TEXT NOT NULL,
    failure_ranges_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_result_id INTEGER NOT NULL REFERENCES rule_results(id) ON DELETE CASCADE,
    segment_type TEXT NOT NULL,
    start_at TEXT NOT NULL,
    end_at TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
    label TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    action_type TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    note TEXT NOT NULL,
    actor TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS deviations (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    rule_id TEXT,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    status TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_temp_samples_lookup ON temperature_samples(run_id, sensor_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_process_samples_lookup ON process_samples(run_id, sequence_no);
