# Roadmap

## Done

- **Foundations:** git and a private GitHub repo, tests, cleanup
- **Server as the source of truth:** build rules, ownership, boats stored on
  the server
- **Live multiplayer:** one WebSocket per world, movement sent as intents,
  riders attached to their boats, automatic reconnect
- **Resources:** per player; shared trees, berry bushes, sticks and stones;
  gathering by hand; random drops with catch-up
- **Survival basics:** food, hunger and starving
- **Inventory and tools:** quick-access bar and bag, crafting, axe (chop
  trees), hammer (build), lumberjack storage and collecting
- **Quality:** one tab per user, coming back where you left off, the client
  split into smaller files
- **Hunting and health:** world settings, health and dying (with the bag
  kept, dropped or lost), rabbits and boars, spear and meat

## Recently finished: hunting and health

World settings (D22), health and dying (D23), animals (D24), and the spear
with meat (D25).

## Later

**Gameplay**
- **Starting islands for each player:** fixes the crowded home island and
  "all lumberjacks on one island"
- **Fog of war:** the minimap only shows places you've been
- **More buildings,** e.g. a quarry, or a warehouse for extra storage
- **Advanced resources,** possibly with different stack sizes
- **Construction time** for buildings (not asked for yet)
- **Throwing the spear** (it's only held for now)
- **Campfire and cooking**
- **Removing boats**
- **Tuning** numbers (costs, rates, times) after more play

**Technical**
- **Login/auth.** Anyone can still claim to be any user. It was postponed
  on purpose; the one-tab-per-user rule covers the meantime. Needed before
  anyone outside your own machine plays.
- **Postgres + Flyway,** replacing H2 and `LegacySchemaCleanup`, once it runs
  somewhere other than your machine.
- **Checking movement paths** on the server (they could cross land), once
  there's login.
