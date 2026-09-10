# WinZenith Agent Rules

Portable Windows desktop app. Java 17, JavaFX 21, Maven. No installation:
`WinZenith.exe` + bundled JRE must keep working from any folder. Keep the
portable footprint small and the diff smaller.

Adapted for Java/JavaFX and system-mutating code. When in doubt, the shorter diff that still protects
the system wins.

## The ladder — stop at the first rung that holds

1. Does this need to exist? Speculative need = skip it, say so in one line.
2. Already in this codebase? Reuse `com.sbtools.util.*` (`ProcessRunner`,
   `ProcessManager`, `PowerShellScripts`, `AppExecutors`,
   `CancelableCompletableFuture`/`CancellationToken`, `JsonMapper`,
   `FormatUtils`/`DataSizeFormatter`, `AppPaths`, `AppLogger`,
   `ElevationGate`/`AdminCheck`, `SingleInstance`) or the matching
   `src/main/resources/powershell/*.ps1`. Grep before you write.
3. JDK 17 stdlib does it? Use `java.nio`, `ProcessHandle`,
   `CompletableFuture`, `java.net.http`, `java.util.logging`.
4. JavaFX / platform native covers it? Native control + `custom.css` /
   AtlantaFX theme before a new library or custom widget.
5. Installed dep solves it? Jackson/JNA only. Never add a Maven dependency
   without explicit approval — it breaks portable size and `module-info.java`.
6. Can it be one line? Make it one line.
7. Only then: minimum code that works, fewest files possible.

Read first, then climb: trace every file the change touches end to end before
picking a rung. Bug fix = root cause in the shared function (one guard there
beats one guard per caller), not a patch on the single path the ticket names.

## Never cut

Input validation at trust boundaries, error handling that prevents data loss,
elevation/admin checks, backup/restore-point flow (`backup/`,
`WindowsServicingSafety`, `StartupSafety`, `DuplicateSafety`,
`DriverPreflightService`/`DriverVerificationService`), security,
accessibility, anything explicitly requested. No `ultra`/deletion-first mode
on driver install, restore, registry, uninstaller leftover-delete, shredder,
startup/services, or network-optimizer apply paths.

## Conventions

- No unrequested abstractions: no single-implementation interface, factory,
  or config for a value that never changes. No scaffolding "for later".
- Deletion over addition. Boring over clever. Shortest working diff wins —
  but only once you understand the problem.
- Mark deliberate shortcuts with a ceiling comment:
  `// ponytail: O(n^2) scan, paginate if dir > 100k entries`.
- Non-trivial logic (branch, loop, parser, money/safety path) leaves ONE
  small focused test behind. Trivial one-liners need no test.
- New/changed PowerShell scripts stay in `src/main/resources/powershell/`,
  ASCII-safe, callable via `PowerShellScripts`/`ProcessRunner` — no inline
  `powershell -Command` string building in Java.
- Respect `module-info.java`: new package = add matching `opens`/`exports`.

## Output

Code first. Then at most three short lines: what was skipped, when to add it.
`[code] -> skipped: [X], add when [Y].` No essays or feature tours.
