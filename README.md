# Island Survival

A small multiplayer survival game on islands. Gather sticks, stones and
berries by hand, craft an axe and a hammer, chop trees, build ports,
lumberjacks and boats, sail to other islands, and keep yourself fed, all in
a shared world with other players moving live.

- **Backend:** Spring Boot 4 (Java 25), REST plus WebSocket, H2 database, in `backend/`
- **Client:** Flutter web with the Flame game engine, in `client/`

## Quick start

```bash
# 1. backend (port 8080)
cd backend && ./gradlew bootRun

# 2. client (another terminal)
cd client && flutter run -d chrome --web-port 5173
```

Pick or create a user and a world, then press **Enter**. More detail,
including playing from a phone, is in [docs/development.md](docs/development.md).

## Documentation

| | |
|---|---|
| [docs/gameplay.md](docs/gameplay.md) | What players can do, and the rules |
| [docs/decisions.md](docs/decisions.md) | **Why** things are the way they are: start here |
| [docs/architecture.md](docs/architecture.md) | How the backend and client fit together |
| [docs/protocol.md](docs/protocol.md) | Every REST endpoint and WebSocket message |
| [docs/development.md](docs/development.md) | Running, testing, changing the database, known traps |
| [docs/roadmap.md](docs/roadmap.md) | What's done, what's next, what's later |
