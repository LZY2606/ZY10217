PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS spec_versions (
  version TEXT PRIMARY KEY,
  band_low REAL NOT NULL,
  band_high REAL NOT NULL,
  min_soak_sec INTEGER NOT NULL,
  max_ramp_c_min REAL NOT NULL,
  min_pressure_mpa REAL NOT NULL,
  max_vacuum_kpa REAL NOT NULL,
  required_tc_count INTEGER NOT NULL,
  description TEXT
);

CREATE TABLE IF NOT EXISTS parts (
  id TEXT PRIMARY KEY,
  code TEXT NOT NULL,
  name TEXT NOT NULL,
  spec_version TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS plies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  part_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  material_code TEXT NOT NULL,
  orientation_deg INTEGER NOT NULL,
  nominal_thickness_mm REAL NOT NULL,
  UNIQUE(part_id, seq)
);

CREATE TABLE IF NOT EXISTS material_lots (
  lot_code TEXT PRIMARY KEY,
  material_code TEXT NOT NULL,
  cert_no TEXT NOT NULL,
  received_ts INTEGER NOT NULL,
  expiry_ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS layup_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  part_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  lot_code TEXT NOT NULL,
  laid_ts INTEGER NOT NULL,
  operator TEXT NOT NULL,
  layup_serial TEXT NOT NULL,
  UNIQUE(part_id, seq)
);

CREATE TABLE IF NOT EXISTS cure_cycles (
  id TEXT PRIMARY KEY,
  part_id TEXT NOT NULL,
  spec_version TEXT NOT NULL,
  planned_start_ts INTEGER NOT NULL,
  planned_end_ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS thermocouples (
  id TEXT PRIMARY KEY,
  cycle_id TEXT NOT NULL,
  name TEXT NOT NULL,
  position_label TEXT NOT NULL,
  required INTEGER NOT NULL,
  is_control INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tc_replacements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  old_tc_id TEXT NOT NULL,
  new_tc_id TEXT NOT NULL,
  ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tc_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  tc_id TEXT NOT NULL,
  ord INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  temp REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS cycle_stages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  stage_order INTEGER NOT NULL,
  name TEXT NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS env_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  ord INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  value REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS review_actions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  type TEXT NOT NULL,
  target TEXT,
  payload TEXT,
  actor TEXT NOT NULL,
  created_ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS deviations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  rule_code TEXT NOT NULL,
  branch_no INTEGER NOT NULL,
  status TEXT NOT NULL,
  owner TEXT NOT NULL,
  reason TEXT,
  created_ts INTEGER NOT NULL,
  resolved_ts INTEGER,
  resolution TEXT
);

CREATE TABLE IF NOT EXISTS evaluation_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cycle_id TEXT NOT NULL,
  created_ts INTEGER NOT NULL,
  overall TEXT NOT NULL,
  spec_version TEXT NOT NULL,
  input_hash TEXT NOT NULL,
  longest_joint_sec INTEGER NOT NULL,
  joint_total_sec INTEGER NOT NULL,
  detail_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rule_results (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  rule_code TEXT NOT NULL,
  title TEXT NOT NULL,
  pass INTEGER NOT NULL,
  message TEXT NOT NULL,
  evidence_json TEXT NOT NULL,
  fail_ranges_json TEXT NOT NULL
);
