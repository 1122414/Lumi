# Lumi AGENTS.md

## Working Style
- Resolve ambiguity before implementing. If requirements are incomplete, contradictory, or likely to cause rework, state the uncertainty first.
- Prefer the simplest solution that fully satisfies the request.
- Make focused, minimal changes. Do not refactor unrelated code or expand scope without a clear reason.
- For multi-step work, state a short plan before major changes.
- Verify non-trivial changes with the most relevant checks available (`./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, `./gradlew :app:lintDebug`, and on-device verification when a phone is attached).

## Git Commit Convention
- **Always use Conventional Commit prefixes** for every git commit:
  - `feat:` — new user-facing capability or feature
  - `fix:` — bug fix or behavior correction
  - `refactor:` — code restructuring without behavior change
  - `docs:` — documentation or planning updates (`plan_/`, `README.md`, `AGENTS.md`)
  - `test:` — adding or updating tests
  - `chore:` — build, dependency, or tooling maintenance
- Keep commit messages concise and specific to what changed and why.
- Never commit secrets or API keys (keep `local.properties` untracked/ignored).

## Boundaries & Validation
- Do not modify unrelated files just because they seem improvable.
- Do not claim something is fixed without verification.
- Summarize what changed, why it changed, and how it was validated.
