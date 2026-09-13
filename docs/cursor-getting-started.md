# Cursor on WinZenith — quick map

Use this repo root (`pom.xml` + `AGENTS.md`) as the Cursor workspace. Search/indexing is automatic; `AGENTS.md` is already applied to Agent chats.

## Entry point

- **Main:** `src/main/java/com/sbtools/App.java` — `main()` handles single-instance, elevation gate, then `Application.launch`.
- **JavaFX UI:** `App.start(Stage)` loads settings, EULA, AtlantaFX theme, and tab views (`DriversTabView`, etc.).

## Driver update flow (UI → install)

1. **UI:** `DriversTabView` — scan lists candidates; install runs on `DriverInstallService.install(...)`.
2. **Safety:** `DriverInstallService` — admin check, pre-release/untrusted blocks, then optional restore point + backup per settings.
3. **Install paths:** Windows Update via `PowerShellScripts.resolve("wu-install.ps1")`; direct downloads via trusted URLs; INF via `pnputil.exe /add-driver`.
4. **Scripts:** bundled under `src/main/resources/powershell/` (e.g. `wu-search-drivers.ps1`), resolved through `PowerShellScripts`.

## Starter prompts in Cursor

- `@AGENTS.md` — project rules for the agent.
- `@src/main/java/com/sbtools/App.java` — “Summarize startup and tab layout.”
- `@src/main/java/com/sbtools/ui/DriversTabView.java` — “Trace install button to `DriverInstallService`.”

After agent edits: `mvn -q compile` (or your IntelliJ build).
