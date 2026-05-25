Catalog Schema Proposal

Summary
Prefer metadata- and signature-first matching. Avoid filename-based rules. Require multi-factor matching to reduce false positives and prevent recommending explicit/hardcoded binaries.

Fields (recommended)
- id: string (unique)
- provider: string
- component: string (graphics, network, audio, etc.)
- platform: string (windows)
- arch: string (x86|x64|arm64)
- hardware_ids: [string]  # e.g., "PCI\\VEN_10DE&DEV_2484"
- match_method: enum("vendor_deviceid","signature","hash","inf_metadata","regex","package_id")
- match_value: string | [string]
- version / version_min / version_max
- hash_sha256: string
- signed: boolean
- cert_thumbprint: string
- source_url: string
- last_verified: ISO-8601 datetime
- confidence: number (0..1)
- tags: [string]
- test_only: boolean (true for examples)

Matching policy
- Require at least two independent matching factors before recommending an update (e.g., vendor_deviceid + signature/hash).
- Avoid relying on exact filename or absolute path for production entries.
- Prefer INF metadata and SetupAPI-derived hardware IDs on Windows, and verify Authenticode signatures.

Migration guidance
- Move explicit filename/path entries into test_catalog.json or mark test_only=true.
- Provide a migration script to extract INF/PE metadata and produce vendor_deviceid or hash-based entries.

Operational notes
- Use Microsoft Update Catalog and vendor APIs as authoritative sources.
- Exclude Microsoft-signed OS components by default.
- Log confidence and rationale for each recommendation.
