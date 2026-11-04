#Requires -Version 5.1
<#
.SYNOPSIS
    Seed (or clean) the idempotent M1 closed-loop demo dataset in the local PostgreSQL.

.DESCRIPTION
    The script talks to PostgreSQL directly with psql (not through the application API),
    because master-data writes need explicit tenant_id / version / deleted values and
    fixed UUIDs so the demo stays reproducible and cleanable. Every column used here is
    taken from db/migration/V1__init.sql.

    Dataset:
      material      : 1 finished good (DEMO-FG-100), 1 semi (DEMO-SA-200),
                      2 raw materials, 1 finished good without BOM (DEMO-FG-NOBOM)
      bom           : 2 levels (FG -> SA -> RAW2), both ACTIVE
      calendar      : CAL-DEMO-7X24 (FACTORY, 00:00-24:00 every day)
      resource      : 6 resources covering UNIT_PER_HOUR / SECONDS_PER_UNIT / BATCH_SECONDS
      operation     : 3 templates + 1 ACTIVE routing (6 nodes, FS edges, 30 min lag edge,
                      auxiliary-resource overrides on node 40 and node 50)
      strategy      : DEMO-M1-STRATEGY (used to isolate demo solve_task rows)
      orders        : 8 DRAFT orders + 1 DRAFT order without BOM (negative case)

    Idempotent: cleanup by demo code prefixes + fixed UUIDs, then re-insert.

.PARAMETER Clean
    Only remove demo data, do not re-insert.

.PARAMETER SkipDbStart
    Do not call scripts/local-db-up.ps1 when the port is not listening.

.EXAMPLE
    .\scripts\seed-m1-demo.ps1
.EXAMPLE
    .\scripts\seed-m1-demo.ps1 -Clean
#>
[CmdletBinding()]
param(
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 55432 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'aps_local' }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [switch]$Clean,
    [switch]$SkipDbStart
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

if (-not (Test-Path $psql)) {
    throw "psql not found: $psql (use -Toolchain to point at the portable PostgreSQL root)"
}

$listening = Test-NetConnection -ComputerName $DbHost -Port $DbPort -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $listening) {
    if ($SkipDbStart) {
        throw "PostgreSQL is not listening on $DbHost`:$DbPort (SkipDbStart is set)"
    }
    Write-Step "Port $DbHost`:$DbPort is closed, starting local PostgreSQL"
    & (Join-Path $PSScriptRoot 'local-db-up.ps1')
}

$env:PGPASSWORD = $DbPassword
$tableCount = (& $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A `
        -c "select count(*) from information_schema.tables where table_schema = 'public';").Trim()
if ([int]$tableCount -eq 0) {
    Write-Step "Database $DbName is empty, applying db/migration/V1__init.sql"
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 `
        -f (Join-Path $repoRoot 'db\migration\V1__init.sql')
    if ($LASTEXITCODE -ne 0) { throw "V1__init.sql failed with exit code $LASTEXITCODE" }
}

# ---------------------------------------------------------------------------
# Demo dataset definition (all UUIDs are fixed so cleanup is deterministic)
# ---------------------------------------------------------------------------
$tenant = $TenantId
$sandbox = '00000000-0000-0000-0000-000000000401'

$mFg = 'd0000000-0000-0000-0000-000000000101'
$mSa = 'd0000000-0000-0000-0000-000000000102'
$mR1 = 'd0000000-0000-0000-0000-000000000103'
$mR2 = 'd0000000-0000-0000-0000-000000000104'
$mNoBom = 'd0000000-0000-0000-0000-000000000105'

$cal = 'd0000000-0000-0000-0000-000000000201'

$rCnc1 = 'd0000000-0000-0000-0000-000000000301'
$rCnc2 = 'd0000000-0000-0000-0000-000000000302'
$rAsm = 'd0000000-0000-0000-0000-000000000303'
$rBatch = 'd0000000-0000-0000-0000-000000000304'
$rQa = 'd0000000-0000-0000-0000-000000000305'
$rHuman = 'd0000000-0000-0000-0000-000000000306'

$tCnc = 'd0000000-0000-0000-0000-000000000401'
$tAsm = 'd0000000-0000-0000-0000-000000000402'
$tQa = 'd0000000-0000-0000-0000-000000000403'

$routing = 'd0000000-0000-0000-0000-000000000501'
$n10 = 'd0000000-0000-0000-0000-000000000511'
$n20 = 'd0000000-0000-0000-0000-000000000512'
$n30 = 'd0000000-0000-0000-0000-000000000513'
$n40 = 'd0000000-0000-0000-0000-000000000514'
$n50 = 'd0000000-0000-0000-0000-000000000515'
$n60 = 'd0000000-0000-0000-0000-000000000516'
$e1 = 'd0000000-0000-0000-0000-000000000521'
$e2 = 'd0000000-0000-0000-0000-000000000522'
$e3 = 'd0000000-0000-0000-0000-000000000523'
$e4 = 'd0000000-0000-0000-0000-000000000524'
$e5 = 'd0000000-0000-0000-0000-000000000525'

$bomFg = 'd0000000-0000-0000-0000-000000000601'
$bomSa = 'd0000000-0000-0000-0000-000000000602'
$bi1 = 'd0000000-0000-0000-0000-000000000611'
$bi2 = 'd0000000-0000-0000-0000-000000000612'
$bi3 = 'd0000000-0000-0000-0000-000000000613'
$bi4 = 'd0000000-0000-0000-0000-000000000614'

$strategy = 'd0000000-0000-0000-0000-000000000801'

$cleanupSql = @"
BEGIN;
-- 1) constraint_justification rows that point at demo task ids
DELETE FROM constraint_justification cj
WHERE cj.tenant_id = '$tenant'
  AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(cj.implicated_facts) AS fact
      WHERE (fact ->> 'id') ~ '^[0-9]+$'
        AND (fact ->> 'id')::bigint IN (
            SELECT t.id FROM task t
            JOIN orders o ON o.id = t.order_id
            WHERE t.tenant_id = '$tenant'
              AND o.tenant_id = '$tenant'
              AND o.order_no LIKE 'DEMO-ORD-%'
        )
  );
-- 2) demo tasks and orders
DELETE FROM task t
USING orders o
WHERE t.order_id = o.id
  AND t.tenant_id = '$tenant'
  AND o.tenant_id = '$tenant'
  AND o.order_no LIKE 'DEMO-ORD-%';
DELETE FROM orders WHERE tenant_id = '$tenant' AND order_no LIKE 'DEMO-ORD-%';
-- 3) process graph
DELETE FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.tenant_id = '$tenant' AND m.code LIKE 'DEMO-%'
);
DELETE FROM routing_node WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.tenant_id = '$tenant' AND m.code LIKE 'DEMO-%'
);
DELETE FROM routing WHERE tenant_id = '$tenant' AND product_id IN (
    SELECT id FROM material WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-%'
);
-- 4) BOM
DELETE FROM bom_item WHERE tenant_id = '$tenant' AND bom_id IN (
    SELECT b.id FROM bom b JOIN material m ON m.id = b.parent_material_id
    WHERE b.tenant_id = '$tenant' AND m.tenant_id = '$tenant' AND m.code LIKE 'DEMO-%'
);
DELETE FROM bom WHERE tenant_id = '$tenant' AND parent_material_id IN (
    SELECT id FROM material WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-%'
);
-- 5) templates, resources, calendar, materials
DELETE FROM operation_template WHERE tenant_id = '$tenant' AND code LIKE 'OP-DEMO-%';
DELETE FROM resource WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-RES-%';
DELETE FROM calendar WHERE tenant_id = '$tenant' AND code = 'CAL-DEMO-7X24';
DELETE FROM material WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-%';
-- 6) demo strategy and its solve tasks
DELETE FROM solve_task WHERE tenant_id = '$tenant' AND strategy_id = '$strategy';
DELETE FROM strategy WHERE id = '$strategy';
COMMIT;
"@

$seedSql = @"
BEGIN;
INSERT INTO material (id, tenant_id, code, name, type, unit, spec, ext_attrs)
VALUES
 ('$mFg',   '$tenant', 'DEMO-FG-100',   'Demo Finished Good',        'FINISHED', 'PCS', 'FG-100',  '{}'::jsonb),
 ('$mSa',   '$tenant', 'DEMO-SA-200',   'Demo Semi Assembly',        'SEMI',     'PCS', 'SA-200',  '{}'::jsonb),
 ('$mR1',   '$tenant', 'DEMO-RM-301',   'Demo Raw Material A',       'RAW',      'KG',  'RM-301',  '{}'::jsonb),
 ('$mR2',   '$tenant', 'DEMO-RM-302',   'Demo Raw Material B',       'RAW',      'KG',  'RM-302',  '{}'::jsonb),
 ('$mNoBom','$tenant', 'DEMO-FG-NOBOM', 'Demo Product Without BOM',  'FINISHED', 'PCS', 'NOBOM',   '{}'::jsonb);

INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays, calendar_version, version)
VALUES ('$cal', '$tenant', 'CAL-DEMO-7X24', 'Demo Factory Calendar 7x24', 'FACTORY', DATE '2026-01-01',
        '[{"startTime": "00:00", "endTime": "24:00", "daysOfWeek": [1,2,3,4,5,6,7]}]'::jsonb,
        '[]'::jsonb, 1, 1);

INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity,
                      efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs)
VALUES
 ('$rCnc1',  '$tenant', 'DEMO-RES-CNC-01',   'CNC 01 (6 pcs/hour)',        'PRIMARY',   'UNIT_PER_HOUR',    6,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rCnc2',  '$tenant', 'DEMO-RES-CNC-02',   'CNC 02 (4 pcs/hour)',        'PRIMARY',   'UNIT_PER_HOUR',    4,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rAsm',   '$tenant', 'DEMO-RES-ASM-01',   'Assembly 01 (seconds/unit)', 'PRIMARY',   'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rBatch', '$tenant', 'DEMO-RES-BATCH-01', 'Batch Oven 01 (600s/batch)','PRIMARY',   'BATCH_SECONDS',    600,  20,   1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rQa',    '$tenant', 'DEMO-RES-QA-01',    'QA Inspector (auxiliary)',   'SECONDARY', 'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rHuman', '$tenant', 'DEMO-RES-HUMAN-01', 'Operator (auxiliary)',       'HUMAN',     'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb);

INSERT INTO operation_template (id, tenant_id, code, name, primary_resource_id, primary_resource_group_id,
                                secondary_resource_ids, input_material_ids, output_material_id,
                                standard_seconds, setup_seconds, teardown_seconds, template_version, version)
VALUES
 ('$tCnc', '$tenant', 'OP-DEMO-CNC', 'Demo CNC Machining', '$rCnc1', NULL, '[]'::jsonb, '[]'::jsonb, NULL, 60, 30, 0, 1, 1),
 ('$tAsm', '$tenant', 'OP-DEMO-ASM', 'Demo Assembly',      '$rAsm',  NULL, '[]'::jsonb, '[]'::jsonb, NULL, 30, 10, 0, 1, 1),
 ('$tQa',  '$tenant', 'OP-DEMO-QA',  'Demo Inspection',    '$rAsm',  NULL, '[]'::jsonb, '[]'::jsonb, NULL, 20, 10, 0, 1, 1);

INSERT INTO routing (id, tenant_id, product_id, routing_version, effective_date, expiry_date, status,
                     is_panel, panel_size, nodes, edges, version)
VALUES ('$routing', '$tenant', '$mFg', 'RT-DEMO-1', DATE '2026-01-01', NULL, 'ACTIVE', false, NULL,
        '[{"id":"n10","templateId":"$tCnc","seqNo":10,"overrides":{}},
          {"id":"n20","templateId":"$tCnc","seqNo":20,"overrides":{"primaryResourceId":"$rCnc2"}},
          {"id":"n30","templateId":"$tCnc","seqNo":30,"overrides":{"primaryResourceId":"$rBatch"}},
          {"id":"n40","templateId":"$tAsm","seqNo":40,"overrides":{"secondaryResourceIds":["$rHuman"]}},
          {"id":"n50","templateId":"$tQa","seqNo":50,"overrides":{"secondaryResourceIds":["$rQa"]}},
          {"id":"n60","templateId":"$tAsm","seqNo":60,"overrides":{}}]'::jsonb,
        '[{"from":"n10","to":"n20","type":"FS","lagMinutes":0},
          {"from":"n20","to":"n30","type":"FS","lagMinutes":30},
          {"from":"n30","to":"n40","type":"FS","lagMinutes":0},
          {"from":"n40","to":"n50","type":"FS","lagMinutes":0},
          {"from":"n50","to":"n60","type":"FS","lagMinutes":0}]'::jsonb,
        1);

INSERT INTO routing_node (id, tenant_id, routing_id, node_key, operation_template_id, operation_code,
                          operation_name, standard_seconds, setup_seconds, teardown_seconds,
                          is_optional, overrides, position_x, position_y, seq_no, version)
VALUES
 ('$n10','$tenant','$routing','n10','$tCnc','OP-DEMO-CNC','Demo CNC Machining', NULL, NULL, NULL, false, '{}'::jsonb, 100, 100, 10, 1),
 ('$n20','$tenant','$routing','n20','$tCnc','OP-DEMO-CNC','Demo CNC Machining', NULL, NULL, NULL, false, '{"primaryResourceId":"$rCnc2"}'::jsonb, 220, 100, 20, 1),
 ('$n30','$tenant','$routing','n30','$tCnc','OP-DEMO-CNC','Demo CNC Machining', NULL, NULL, NULL, false, '{"primaryResourceId":"$rBatch"}'::jsonb, 340, 100, 30, 1),
 ('$n40','$tenant','$routing','n40','$tAsm','OP-DEMO-ASM','Demo Assembly',      NULL, NULL, NULL, false, '{"secondaryResourceIds":["$rHuman"]}'::jsonb, 460, 100, 40, 1),
 ('$n50','$tenant','$routing','n50','$tQa', 'OP-DEMO-QA', 'Demo Inspection',    NULL, NULL, NULL, false, '{"secondaryResourceIds":["$rQa"]}'::jsonb, 580, 100, 50, 1),
 ('$n60','$tenant','$routing','n60','$tAsm','OP-DEMO-ASM','Demo Assembly',      NULL, NULL, NULL, false, '{}'::jsonb, 700, 100, 60, 1);

INSERT INTO routing_edge (id, tenant_id, routing_id, from_node_id, to_node_id, depend_type, lag_minutes, version)
VALUES
 ('$e1','$tenant','$routing','$n10','$n20','FS',0,1),
 ('$e2','$tenant','$routing','$n20','$n30','FS',30,1),
 ('$e3','$tenant','$routing','$n30','$n40','FS',0,1),
 ('$e4','$tenant','$routing','$n40','$n50','FS',0,1),
 ('$e5','$tenant','$routing','$n50','$n60','FS',0,1);

INSERT INTO bom (id, tenant_id, parent_material_id, bom_version, effective_date, expiry_date, status,
                 is_panel, panel_size, items, version)
VALUES
 ('$bomFg','$tenant','$mFg','BOM-DEMO-FG-1',DATE '2026-01-01',NULL,'ACTIVE',false,NULL,
  '[{"childMaterialId":"$mSa","materialCode":"DEMO-SA-200","qtyPerUnit":1,"scrapRate":0,"seqNo":1},
    {"childMaterialId":"$mR1","materialCode":"DEMO-RM-301","qtyPerUnit":2,"scrapRate":0,"seqNo":2}]'::jsonb,1),
 ('$bomSa','$tenant','$mSa','BOM-DEMO-SA-1',DATE '2026-01-01',NULL,'ACTIVE',false,NULL,
  '[{"childMaterialId":"$mR2","materialCode":"DEMO-RM-302","qtyPerUnit":1,"scrapRate":0,"seqNo":1},
    {"childMaterialId":"$mR1","materialCode":"DEMO-RM-301","qtyPerUnit":0.5,"scrapRate":0,"seqNo":2}]'::jsonb,1);

INSERT INTO bom_item (id, tenant_id, bom_id, child_material_id, qty_per_unit, scrap_rate, position,
                      substitutes, is_phantom, level, seq_no, version)
VALUES
 ('$bi1','$tenant','$bomFg','$mSa',1.000000,0,NULL,'[]'::jsonb,false,1,1,1),
 ('$bi2','$tenant','$bomFg','$mR1',2.000000,0,NULL,'[]'::jsonb,false,1,2,1),
 ('$bi3','$tenant','$bomSa','$mR2',1.000000,0,NULL,'[]'::jsonb,false,2,1,1),
 ('$bi4','$tenant','$bomSa','$mR1',0.500000,0,NULL,'[]'::jsonb,false,2,2,1);

INSERT INTO strategy (id, tenant_id, name, direction, strategy_version, initial_rules, optimization_rules,
                      effective_from, effective_to, apply_condition, version)
VALUES ('$strategy', '$tenant', 'DEMO-M1-STRATEGY', 'FORWARD', 1, '{}'::jsonb, '{}'::jsonb,
        DATE '2026-01-01', NULL, '{}'::jsonb, 1);

INSERT INTO orders (id, tenant_id, order_no, source, product_id, qty, due_date, priority, status,
                    freeze_status, remark, ext_attrs, version)
VALUES
 ('d0000000-0000-0000-0000-000000000701','$tenant','DEMO-ORD-001','MANUAL','$mFg', 8, CURRENT_DATE + 3, 'HIGH',   'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000702','$tenant','DEMO-ORD-002','MANUAL','$mFg',10, CURRENT_DATE + 4, 'NORMAL', 'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000703','$tenant','DEMO-ORD-003','MANUAL','$mFg',12, CURRENT_DATE + 5, 'URGENT', 'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000704','$tenant','DEMO-ORD-004','MANUAL','$mFg', 6, CURRENT_DATE + 6, 'LOW',    'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000705','$tenant','DEMO-ORD-005','MANUAL','$mFg',14, CURRENT_DATE + 7, 'HIGH',   'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000706','$tenant','DEMO-ORD-006','MANUAL','$mFg', 9, CURRENT_DATE + 8, 'NORMAL', 'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000707','$tenant','DEMO-ORD-007','MANUAL','$mFg',11, CURRENT_DATE + 9, 'HIGH',   'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000708','$tenant','DEMO-ORD-008','MANUAL','$mFg', 7, CURRENT_DATE +10, 'NORMAL', 'DRAFT','UNFROZEN','M1 closed-loop demo', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000709','$tenant','DEMO-ORD-NOBOM','MANUAL','$mNoBom',5, CURRENT_DATE + 5, 'NORMAL','DRAFT','UNFROZEN','negative case: no ACTIVE BOM','{}'::jsonb,1);
COMMIT;
"@

$tempSql = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-m1-seed-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

try {
    Write-Step 'Removing previous demo data (idempotent cleanup)'
    [System.IO.File]::WriteAllText($tempSql, $cleanupSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "cleanup SQL failed with exit code $LASTEXITCODE" }

    if ($Clean) {
        Write-Ok 'Demo data removed (Clean mode)'
        return
    }

    Write-Step 'Inserting M1 demo dataset'
    [System.IO.File]::WriteAllText($tempSql, $seedSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "seed SQL failed with exit code $LASTEXITCODE" }
}
finally {
    Remove-Item $tempSql -Force -ErrorAction SilentlyContinue
}

$summary = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -F '|' -c @"
SELECT 'materials', count(*) FROM material WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-%'
UNION ALL SELECT 'boms', count(*) FROM bom b JOIN material m ON m.id = b.parent_material_id
    WHERE b.tenant_id = '$tenant' AND m.code LIKE 'DEMO-%'
UNION ALL SELECT 'resources', count(*) FROM resource WHERE tenant_id = '$tenant' AND code LIKE 'DEMO-RES-%'
UNION ALL SELECT 'templates', count(*) FROM operation_template WHERE tenant_id = '$tenant' AND code LIKE 'OP-DEMO-%'
UNION ALL SELECT 'routing_nodes', count(*) FROM routing_node WHERE tenant_id = '$tenant' AND routing_id = '$routing'
UNION ALL SELECT 'routing_edges', count(*) FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id = '$routing'
UNION ALL SELECT 'orders', count(*) FROM orders WHERE tenant_id = '$tenant' AND order_no LIKE 'DEMO-ORD-%'
UNION ALL SELECT 'orders_draft', count(*) FROM orders WHERE tenant_id = '$tenant' AND order_no LIKE 'DEMO-ORD-%' AND status = 'DRAFT'
ORDER BY 1;
"@
if ($LASTEXITCODE -ne 0) { throw "summary query failed with exit code $LASTEXITCODE" }

Write-Host ''
foreach ($line in @($summary)) {
    if ($line) { Write-Host "    $line" }
}
Write-Host ''
Write-Ok "Seed ready: 8 valid DRAFT orders + 1 no-BOM order, sandbox $sandbox"
Write-Host "    Cleanup:  .\\scripts\\seed-m1-demo.ps1 -Clean"
