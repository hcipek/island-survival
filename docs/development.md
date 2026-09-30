# Development

## Running it

**Backend** (Spring Boot 4, Java 25, H2 database file):

```bash
cd backend
./gradlew bootRun
```

- It's ready at `Tomcat started on port 8080`. The terminal keeps showing
  `80% EXECUTING` while it runs; that's normal, because the last Gradle
  step *is* the running server. Stop it with **Ctrl+C**.
- Use `./gradlew` (the wrapper), not `gradle`, and run it inside `backend/`.
  The project root is an empty Gradle project with no `bootRun`.
- `BUILD FAILED` right at start-up usually means **port 8080 is already in
  use**: an older backend is still running. Check with
  `lsof -nP -iTCP:8080 -sTCP:LISTEN`.
- Data lives in `backend/data/testproject.mv.db`. The H2 console is at
  http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/testproject`).

**Client** (Flutter web):

```bash
cd client
flutter run -d chrome --web-port 5173
```

- It has to run as a **web** app from a `localhost` or `192.168.x.x`
  address: it finds the backend from the page's own host, and those are
  the only origins the backend allows.
- **On a phone** on the same Wi-Fi:
  `flutter run -d web-server --web-port 5173 --web-hostname 0.0.0.0`, then
  open `http://<your-mac-ip>:5173`.
- `r` hot reloads, `R` hot restarts. **After moving classes to new files,
  or adding packages, stop with `q` and run again.** Hot restart can end up
  with old and new code mixed together (we saw a strange
  `setRegrowsIn` null error from exactly that).

## Testing

```bash
cd backend && ./gradlew test                  # everything on the server
cd client && flutter test                     # client, on the Dart VM
cd client && flutter test --platform chrome   # client, in the browser - run this too
```

- **Run the client tests in Chrome as well.** The game runs in the browser,
  where numbers behave differently: `1 << 32` is `0` there, which once
  crashed the game on entering a world while every VM test passed.
- Backend tests use an **in-memory database** (`src/test/resources/application.yaml`),
  never your save file, and switch off the background tick so random drops
  can't interfere.
- `LiveWorldTest` starts a real server on a random port and plays it with
  real WebSocket clients. Use it for anything involving messages between
  players.
- **Flaky tests are bugs.** Rerun the suite several times after changing
  anything random (placement, drops). Tests about random placement should
  check limits ("at most"), not exact counts, because a crowded small
  island may not fit everything.

## Changing the database

Hibernate (`ddl-auto: update`) adds new tables and columns on start-up. It
**never** changes or removes existing ones. So:

- **New non-null column** (e.g. a `double` or `int` field): add
  `@ColumnDefault("...")`, otherwise it can't be added to rows that already
  exist.
- **Removed field:** if its column was `NOT NULL`, inserts will fail. Drop
  the column in `LegacySchemaCleanup`.
- **Enum-like fields** (types, items): store them as a `String` field with
  an enum getter, *not* `@Enumerated`. Otherwise the column only accepts
  the values that existed when the table was created (D18).
  `TypeColumnsTest` checks this.
- **Renamed or merged values** (e.g. sticks → wood): convert the data in
  `LegacySchemaCleanup`. It runs on every start, so make each step safe to
  repeat.

**Before committing a database change, try it on a copy of the real save
file.** The tests always start from an empty database, so they can't see
upgrade problems:

```bash
mkdir -p /tmp/dbcopy
cp backend/data/testproject.mv.db /tmp/dbcopy/        # a copy is fine even while your server runs
cd backend
./gradlew bootRun --args="--server.port=8091 --spring.datasource.url=jdbc:h2:file:/tmp/dbcopy/testproject"
```

Then use it: create a world, open a world (a WebSocket snapshot), try the
new feature. Stop that server afterwards and check that it's really gone.
This caught the enum-column bug (D18) before it reached the real save.

## Git and GitHub

- Private repo: https://github.com/hcipek/island-survival, branch `main`.
- The repo uses its **own** git identity (personal GitHub account, noreply
  email), set with `git config` inside the repo. Don't commit with the
  global work identity.
- Commit messages: a short summary line, then a body explaining *why*. No
  `Co-Authored-By` lines.
- One commit per step that works on its own. Refactors (moving code) go in
  their own commits, separate from behavior changes.
- **Don't run `dart format` over existing files.** It rewraps everything to
  80 columns, while the code uses longer lines, and a formatting-only diff
  hides real changes.

## Keeping the docs current

When a change affects how the game plays, the protocol, or a decision,
update the matching file in `docs/` **in the same commit**:
- a new or changed rule or feature → [gameplay.md](gameplay.md)
- a new decision, or one replaced → [decisions.md](decisions.md) (add an
  entry; mark replaced ones **superseded**)
- a new or changed message → [protocol.md](protocol.md)
- something finished or added to the plan → [roadmap.md](roadmap.md)
