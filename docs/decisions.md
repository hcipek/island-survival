# Decision log

Why the game works the way it does. Each entry says what was decided, why,
what else was considered, and where it happened. The newest entries are at
the bottom. When a later decision replaces an earlier one, the old entry
stays and is marked **superseded**.

If you only read one document, read this one.

---

### D1. The server is the source of truth
*2026-09-29 · [8daaba1](https://github.com/hcipek/island-survival/commit/8daaba1)*

Every rule is checked on the server: where things can be built, who owns
what, what things cost, whether you're close enough to gather, and what
you're holding. The client only *shows* the same rules, so players get
quick feedback (a green or red build preview, "hold an axe" messages).

**Why:** a client can be modified. Rules that exist only in the client
aren't rules.
**Consequence:** every new rule needs a server check (and a test) first. The
client check is optional polish.

### D2. Boats live on the server
*2026-09-29 · [8796088](https://github.com/hcipek/island-survival/commit/8796088), [025ba78](https://github.com/hcipek/island-survival/commit/025ba78)*

Boats are stored on the server, cost wood, spawn next to the builder's own
port, and only their owner can board them.

**Why:** before this, a boat existed only in one browser. It was free,
invisible to others, and gone after a reload.

### D3. One live WebSocket per world instead of polling
*2026-09-29 · [1659f83](https://github.com/hcipek/island-survival/commit/1659f83), [79f1139](https://github.com/hcipek/island-survival/commit/79f1139), [f130c49](https://github.com/hcipek/island-survival/commit/f130c49)*

Each client keeps one connection to its world, `/ws/worlds/{id}`. It gets a
snapshot first, then the server pushes every change as it happens.

**Why:** polling every 0.8 s made other players' movement jumpy, and it
didn't feel like a live game.
**Considered:** STOMP over WebSocket. We chose plain WebSocket with JSON
messages because it's simpler to follow on both sides.
**Kept as REST:** commands that need a clear yes or no answer (create a
user or world, build, destroy, build a boat) and static data (island
layout). Their results are still pushed to everyone over the socket.

### D4. Movement is sent as intents, not positions
*2026-09-29 · [1659f83](https://github.com/hcipek/island-survival/commit/1659f83)*

A click sends one message: "walk this path" or "sail this path". Every
client simulates that path itself at the same fixed speed, with the shared
`PathFollower` in `client/lib/game/player.dart`. Snapshots include the
movement in progress and how long ago it started (`elapsedMs`), so a late
joiner can fast-forward to the right spot.

**Why:** it looks as smooth for everyone else as it does for the player
moving, and it's one message per click instead of several per second.
**Considered:** streaming positions several times a second. That would
still jitter and cost far more traffic.
**Trade-off:** the server trusts the path a client sends (it could cross
land). It can validate paths later, once there's login.

### D5. A rider is attached to their boat
*2026-09-29 · [1659f83](https://github.com/hcipek/island-survival/commit/1659f83)*

The player's presence records the `boatId` they're aboard. Every client
draws that player *on* the boat instead of moving them separately.

**Why:** before this, the rider and the boat were two objects with
different speeds, and on other screens the rider "flew" after their boat.

### D6. Resources are per player (with a fresh start)
*2026-09-30 · [9afbf1c](https://github.com/hcipek/island-survival/commit/9afbf1c)*

Each player has their own resources in each world, and the lumberjack
limit is **1 per player** instead of 1 per world.

**Why:** a world-wide limit meant the first player blocked everyone else.
**The old shared pool:** Homeland had built up 178,374 wood. We considered
splitting it evenly (about 59k each, which would make the economy
pointless) or splitting it with a cap. We chose a **fresh start**, because
the users are test data.
**Later:** the wood counter itself was replaced by the inventory (D14).

### D7. Trees, bushes and loose items are shared world objects
*2026-09-30 · [6795914](https://github.com/hcipek/island-survival/commit/6795914)*

The server places resource spots (`ResourceNode`) on every island: sparse on
the home island, richer on the outer islands so sailing pays off.
Everyone sees the same ones, and a used-up spot is used up for everyone.

**Why:** before this, each client scattered its own random trees, so
players saw different worlds.

### D8. Gathering: the client times it, the server checks it
*2026-09-30 · [c9919a4](https://github.com/hcipek/island-survival/commit/c9919a4)*

Tapping a spot walks the player next to it and fills a progress ring (2 s
to gather, 3 s to chop), then sends `gather`. The server checks that the
player is standing close enough, isn't aboard a boat, and isn't gathering
more often than a short cooldown allows.

**Why:** a server-side timer per gather would be more work for little gain
at this stage. The cooldown stops spam without making normal play feel
slower.

### D9. Hunger drains only while you're in the world; starving is mild for now
*2026-09-30 · [22cea14](https://github.com/hcipek/island-survival/commit/22cea14)*

Hunger goes from full to empty over 15 minutes of play and pauses while
you're away. At 0 you're **starving**: you can still move and pick
berries, but you can't gather wood, chop, or build until you eat.

**Why:** coming back starving after a night away would feel bad. Health and
death are deferred until hunting and animals exist, and the user plans to
revisit this rule then.
**Considered:** health loss and death at 0 hunger (see the roadmap), and
draining hunger while offline.

### D10. Loose items drop at random spots, up to a limit per island
*2026-09-30 · [aa21730](https://github.com/hcipek/island-survival/commit/aa21730)*

Sticks (and later stones) disappear when picked up. New ones drop at
random free spots on the grass, clear of other spots, buildings and the
spawn point, while someone is playing, up to a limit per island. Berry
bushes and trees stay in place and regrow instead.

**Why:** sticks mostly fall from trees and turn up somewhere else. They
shouldn't regrow in the same spot the way berries do.

### D11. Catching up on missed drops, instead of refilling on entry
*2026-09-30 · [67daece](https://github.com/hcipek/island-survival/commit/67daece)*

Each world remembers `lastActiveAt`. When someone enters an empty world,
the server runs the drop rolls for every 20-second tick it missed. The
simulation runs in memory, stops as soon as every island is full, and is
capped at 1,000 ticks.

**Why:** "refill everything when someone enters" let a solo player refill
every island just by reconnecting.
**Considered:** refilling only if the world had been empty for an hour or
more. We rejected it because 59 minutes away would give nothing, even
though the islands would normally have filled up within minutes.
**Cost:** a handful of queries however long the world was empty.

### D12. Only the background tick uses a timer
*2026-09-30 · extended by D23*

Everything that changes over time is worked out from timestamps when
someone looks: hunger, lumberjack storage, and regrowing bushes, trees and
stumps. The one scheduled job is `WorldTicker` (every 20 s, only for worlds
someone is playing in). It drops loose items and keeps `lastActiveAt`
current.

**Why:** it costs nothing while nobody is looking, and there's less that
can go out of sync.

### D13. Schema changes without a migration tool (for now)
*2026-09-30 · [9afbf1c](https://github.com/hcipek/island-survival/commit/9afbf1c), [fdc4b7b](https://github.com/hcipek/island-survival/commit/fdc4b7b)*

The database is a local H2 file, and Hibernate's `ddl-auto: update` adds
new tables and columns. What it can't do is handled by
`LegacySchemaCleanup`, which runs on every start and is safe to repeat:
dropping old columns, loosening old type columns, and converting old data.
New non-null columns get a `@ColumnDefault` so they can be added to
existing rows.

**Why:** a migration tool is heavier than needed for a local H2 file.
**Later:** switch to Postgres with Flyway once other people play.

### D14. Inventory: a 10-slot quick-access bar and a 40-slot bag
*2026-09-30 · [e982d27](https://github.com/hcipek/island-survival/commit/e982d27)*

Resources are items in an inventory: 10 quick-access slots plus a 40-slot
bag. Basic resources stack to 50 per slot and tools take one slot each.
Adding and paying are all-or-nothing, so a full bag leaves a bush alone
instead of wasting it. **Everyone starts empty-handed.** Tools don't wear
out.

**Why:** tools need items. The user chose the sizes, and the empty start
means players build up from sticks and stones.
**Later:** advanced resources may get different stack sizes
(`ItemType.Kind`).

### D15. Tools only work while held
*2026-09-30 · [631133b](https://github.com/hcipek/island-survival/commit/631133b), [fdc4b7b](https://github.com/hcipek/island-survival/commit/fdc4b7b)*

The selected quick-access slot is what you're holding. An **axe** must be
held to chop trees, and a **hammer** must be held to build anything,
including boats. An axe sitting in the bag does nothing.

**Why:** that's the user's rule: holding an axe lets you cut, holding a
hammer lets you build, and holding a spear lets you throw it (later).
**Considered:** construction time for buildings. It wasn't asked for, so
building is instant while holding a hammer.

### D16. Lumberjacks store their wood; owners collect it
*2026-09-30 · [e982d27](https://github.com/hcipek/island-survival/commit/e982d27)*

A lumberjack slowly fills its own storage, up to a limit. Its owner walks
over and collects it into their inventory whenever they like.

**Why:** once wood is an item, it can't just appear in an account. It also
gives a reason to walk back to your buildings.

### D17. Crafting recipes live on the server
*2026-09-30 · [e02be8c](https://github.com/hcipek/island-survival/commit/e02be8c)*

Recipes are defined on the server (`Recipe`) and sent in the snapshot, so
the client's craft sheet is built from them. Crafting is instant and
works anywhere for now.

**Why:** there's one place to change recipes, and the client can't make up
its own.

### D18. Type columns are stored as plain strings
*2026-09-30 · [fdc4b7b](https://github.com/hcipek/island-survival/commit/fdc4b7b)*

Node, building and item types are stored as `String` fields in the
entities, with the getters still returning the enum.

**Why:** a real bug. With `@Enumerated`, Hibernate restricted the column to
the values that existed when the table was created (first as an H2 enum,
later as a `CHECK (... IN (...))` constraint) and never updated it. Adding
stones would have crashed every existing world as soon as someone entered.
Converters didn't help, and there's no Hibernate setting to turn it off.
`TypeColumnsTest` guards against this coming back.

### D19. One tab per user, with takeover for reconnects
*2026-09-30 · [643e1fa](https://github.com/hcipek/island-survival/commit/643e1fa)*

While someone plays as a user, nobody else can pick that user. Each browser
tab sends a random `clientId`. The same tab reconnecting takes over its old
connection, and any other tab is refused with "*name* is already playing".

**Why:** real login was deliberately postponed by the user. This stops two
people from playing the same character in the meantime. The takeover makes
sure a dropped connection the server hasn't noticed yet never locks you
out of your own game.

### D20. You come back where you left off
*2026-09-30 · [52530fb](https://github.com/hcipek/island-survival/commit/52530fb)*

The snapshot includes `you`: where the player was and which boat they were
on. Leaving no longer takes you off your boat.

**Why:** reloading always put you back on the home island, which undid any
exploring.

### D21. Sticks give wood (the same resource from different sources)
*2026-09-30 · [899f3f7](https://github.com/hcipek/island-survival/commit/899f3f7)*

A pile of sticks on the ground gives **wood**, just like a chopped tree or a
lumberjack. There's no separate sticks item. Recipes use wood.

**Why:** it's the user's rule: different sources can give the same
resource, rather than each source having its own item.

### D22. World settings, chosen once when a world is created
*2026-10-07*

A world has settings picked when it's created: **animal aggression** (on or
off) and **what happens to your bag when you die** (kept, dropped as a
pile, or lost). Existing worlds get the defaults: aggression on, bag
dropped.

**Why:** it's the user's idea. Different worlds can play differently, and
more options can be added the same way later.
**Fixed after creation:** changing them mid-game would be odd (e.g.
switching to "kept" right after dying), and worlds have no owner yet who
could be allowed to change them. With login, "the creator can edit
settings" can be added.
**Keep the list short:** each setting multiplies the cases to test, so new
settings are added only when a feature really needs one.

### D23. Health, dying and dropped bags
*2026-10-07*

Health goes from 0 to 100. It comes back slowly while you're well fed
(hunger above half) and drops while you're starving. Like hunger, it's
worked out from time when needed: a stretch of time is split into its
well-fed, in-between and starving parts, and healing is capped at full
before any starving damage. A check every 2 s (`WorldTicker`) brings
everyone's vitals up to date and notices deaths.

On death you wake on the home island with full health and **hunger at
half**. The bag (slots 10–49) is handled by the world setting from D22.
**Dropped** bags become an `ITEM_PILE` where you died, or on the nearest
shore if you died at sea. **Only the owner** can pick a pile up, and it
**disappears after 10 minutes**. The quick-access bar is always kept.

**Why:** the user accepted health with regeneration and the death options.
The details marked in bold weren't specified, so these are defaults
chosen to avoid problems: respawning at hunger 0 would starve you again
straight away, a pile at sea couldn't be reached, and others taking your
things felt harsh for now. All of them are easy to change.
**Starving** still blocks building and wood gathering as before (D9), and
now it costs health as well.

### D24. Animals are controlled by the server and live in memory
*2026-10-07*

Rabbits and boars are placed on the islands, with none of the dangerous
ones on the home island, and the server decides what each one does twice
a second. Rabbits wander and run from players on foot. Boars wander, and
in worlds with animal aggression on they chase a player on foot on their
island, bite when they catch up (with a cooldown), and give up when the
player gets away or is in a boat. Their walks are sent as movement
intents with their speed, and clients simulate them (D4).

**Why server-side:** everyone has to see the same animal in the same
place, and a bite is a rule (D1).
**Why in memory:** animals change direction several times a second.
Writing that to the database would be a lot of churn for state nobody
needs to keep. After a server restart, animals are simply placed again
when someone enters the world.
**Why the speed is sent:** so the client doesn't need its own copy of
every animal's speed (players' and boats' speeds are still duplicated
on both sides; see `Movement.PLAYER_SPEED`).
**Balance:** players walk faster than boars, so you can always escape.
The numbers are in `AnimalService` and `AnimalType`.

### D25. Hunting with a spear, for meat
*2026-10-07*

A spear (3 wood) has to be held. Tapping an animal within reach strikes
it, at most about once a second, and the server checks the distance
between where the player and the animal are right now. Rabbits take one
hit and give 1 meat; boars take three and give 3. Meat is eaten like
berries and fills more hunger.

**Why:** the user accepted these suggestions. There's no throwing yet, so
the spear stays in your hand, as the user said.
**A killing blow is refused when there's no room for the meat**, the same
all-or-nothing rule as gathering (D14), so nothing is wasted.
**Hunting is allowed while starving**, like picking berries (D9), because
it's a way to get food.

