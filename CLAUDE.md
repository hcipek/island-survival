# Working on this repo

A multiplayer island survival game: a Spring Boot backend in `backend/` and
a Flutter web client in `client/`. Read [README.md](README.md) and the
`docs/` it links to. [docs/decisions.md](docs/decisions.md) explains why
things are the way they are; check it before changing a rule.

## Rules

- **The server decides.** Every game rule is checked on the backend first,
  with a test. Client checks are only for quick feedback (D1).
- **Keep the docs current in the same commit:** `docs/gameplay.md` for rules
  and features, `docs/decisions.md` for decisions (add an entry; mark
  replaced ones superseded), `docs/protocol.md` for messages,
  `docs/roadmap.md` for plans.
- **Game design choices belong to the user.** Ask before deciding how
  something plays, for example penalties, costs, or what a tool does.
  Sensible defaults are fine for technical details.

## Before committing

- Backend: `cd backend && ./gradlew test`
- Client: `flutter analyze`, `flutter test`, **and** `flutter test --platform chrome`.
  The game runs in the browser, and some bugs only happen there.
- Anything touching entities or data: run the new backend against a
  **copy** of `backend/data/testproject.mv.db` on another port first (see
  `docs/development.md`). Tests start from an empty database and can't see
  upgrade problems.
- If you start a backend yourself, check first that port 8080 isn't the
  user's own server, and confirm yours has stopped afterwards. Never stop
  the user's server.

## Conventions

- Commits: a summary line and a body explaining why. No `Co-Authored-By`
  lines. Use the repo's own git identity; don't change git config.
- Refactors go in their own commits, separate from behavior changes.
- Don't run `dart format` over existing files (it rewraps to 80 columns).
  Match the surrounding style.
- Enum-like database fields are stored as `String` with an enum getter,
  never `@Enumerated` (D18). New non-null columns get `@ColumnDefault`.
- In client code, avoid bit shifts and 64-bit integer tricks: on the web,
  `1 << 32 == 0`.
