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

## Next: hunting and health

Waiting on the user's decisions. Suggested answers are in **bold**:

1. **Animals:** **rabbits** (flee, one hit, meat) and **boars** (fight
   back, three hits, more meat, and what hurts you), mostly on the outer
   islands. There's a limit per island, and new ones appear over time.
2. **Spear:** made from **wood**. Hold it and tap an animal within range.
   There's no real throwing yet, so the spear stays in your hand.
3. **Meat:** **fills hunger more than berries.** Cooking at a campfire
   could come later.
4. **Health:** **100, slowly regenerating while well fed.** You lose it from
   boar attacks and from starving, which would then replace starving only
   blocking building.
5. **Death:** respawn on the home island. For items: **A** keep everything,
   **B** drop the bag as a pile you can go back for (it disappears after
   about 10 minutes) and keep the quick-access bar, **C** lose the bag's
   contents. The suggestion is **B**.

## Later

**Gameplay**
- **Starting islands for each player:** fixes the crowded home island and
  "all lumberjacks on one island"
- **Fog of war:** the minimap only shows places you've been
- **More buildings,** e.g. a quarry, or a warehouse for extra storage
- **Advanced resources,** possibly with different stack sizes
- **Construction time** for buildings (not asked for yet)
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
