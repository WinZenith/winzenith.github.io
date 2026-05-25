# Migration helper: convert filename-based entries into metadata-first catalog entries
# Usage: .\scripts\migrate_catalog.ps1 -InputDir .\catalog -OutputFile .\catalog\migrated_catalog.json
param(
  [string]$InputDir = "catalog",
  [string]$OutputFile = "catalog\migrated_catalog.json"
)

# This is a stub. Recommended steps:
# 1) Enumerate catalog files; for each entry that uses filename/path, attempt to locate the binary/INF if available.
# 2) Extract hardware IDs from INF files or PE Authenticode signer and file hashes (sha256).
# 3) Build new entries with match_method vendor_deviceid or hash and set test_only=false when confident.
# 4) Write the normalized catalog JSON to $OutputFile for review.

Write-Output "Migration script stub created. Implement metadata extraction per README or CATALOG_SCHEMA.md."