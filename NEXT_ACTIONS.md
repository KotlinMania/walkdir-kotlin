# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 4/7 (57.1%)
- **Function parity:** 47/127 matched (target 67) — 37.0%
- **Class/type parity:** 9/16 matched (target 13) — 56.2%
- **Combined symbol parity:** 56/143 matched (target 80) — 39.2%
- **Average inline-code cosine:** 0.45 (function body across 4 matched files)
- **Average documentation cosine:** 0.47 (doc text across 4 matched files)
- **Cheat-zeroed Files:** 0
- **Critical Issues:** 3 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. error

- **Target:** `walkdir.Error`
- **Similarity:** 0.52
- **Dependents:** 3
- **Priority Score:** 3041604.8
- **Functions:** 10/14 matched (target 12)
- **Missing functions:** `description`, `source`, `fmt`, `from`
- **Types:** 2/2 matched (target 4)
- **Missing types:** _none_

### 2. dent

- **Target:** `walkdir.DirEntry`
- **Similarity:** 0.44
- **Dependents:** 0
- **Priority Score:** 41605.6
- **Functions:** 11/14 matched (target 13)
- **Missing functions:** `from_entry`, `clone`, `fmt`
- **Types:** 1/2 matched (target 1)
- **Missing types:** `DirEntryExt`

### 3. lib

- **Target:** `walkdir.DirList`
- **Similarity:** 0.68
- **Dependents:** 0
- **Priority Score:** 33403.2
- **Functions:** 25/26 matched (target 41)
- **Missing functions:** `fmt`
- **Types:** 6/8 matched
- **Missing types:** `Result`, `Item`

### 4. util

- **Target:** `walkdir.Util`
- **Similarity:** 0.14
- **Dependents:** 0
- **Priority Score:** 108.6
- **Functions:** 1/1 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `tests.mod` | `tests.Mod` | 0 | `tests/mod.rs` | `tests/Mod.kt` |

