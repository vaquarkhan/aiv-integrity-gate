-- Ad hoc analytics over an exported AIV audit log.
--
--   java -jar aiv-cli.jar report --audit-log .aiv/audit/aiv.log --export-jsonl out/
--   duckdb -c ".read docs/reports/aiv-audit.duckdb.sql"        (run from the directory that holds out/)
--
-- DuckDB is optional and never in the verdict path: the hash-chained log is the record, the JSONL files are
-- a derived, disposable view. Re-export after every run you want to include; run `aiv audit verify` first if
-- the numbers matter.

create or replace view findings as select * from read_json_auto('out/findings.jsonl');
create or replace view records  as select * from read_json_auto('out/records.jsonl');

-- 1. Which rules fire most, and on how many distinct files.
select rule_id, count(*) as findings, count(distinct file) as files,
       sum(case when blocks_ci then 1 else 0 end) as blocking
from findings group by rule_id order by findings desc;

-- 2. Findings per day per gate (trend).
select cast(ts as date) as day, gate, count(*) as findings
from findings where ts is not null group by all order by day, gate;

-- 3. Override evidence over time: PRs that edited .aiv/, refused and honoured /aiv skip, suppressions.
select cast(ts as date) as day,
       sum(case when overrides.config_change then 1 else 0 end) as config_changes,
       sum(case when overrides.skip_ignored then 1 else 0 end)  as skips_refused,
       sum(case when overrides.skip_honored then 1 else 0 end)  as skips_honoured,
       sum(len(overrides.suppressed))                           as suppressions
from records where schema = 'aiv-gate-run/1' group by all order by day;

-- 4. Proposal checks: latest record per proposal and the rules it tripped.
select id, arg_max(title, ts) as title, arg_max(state, ts) as state, arg_max(version, ts) as version,
       arg_max(rules, ts) as rules
from records where schema = 'aiv-proposal/1' group by id order by id;
