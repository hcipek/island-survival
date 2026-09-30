# Client–server protocol

The contract between the Flutter client and the backend. If you change a
message, change it here in the same commit.

Positions are world coordinates in plain numbers: `{"x": 550.0, "y": 0.0}`.
The home island is centered at `(0, 0)`. Types are sent as upper-case names
(`"PORT"`, `"BERRY_BUSH"`, `"WOOD"`).

## REST

Base URL: `http://<host>:8080`. Errors come back with a 4xx status and
`{"message": "why"}`.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/users` | | `[{id, name, color, playing}]`: `playing` means someone is in the game as that user |
| `POST /api/users` | `{name}` | the new user |
| `GET /api/worlds` | | `[{id, name, createdAt}]` |
| `POST /api/worlds` | `{name}` | the new world (its islands and resource spots are made right away) |
| `DELETE /api/worlds/{worldId}` | | deletes the world and everything in it |
| `GET /api/worlds/{worldId}/islands` | | `[{id, centerX, centerY, radius, isHome}]` (never changes) |
| `POST /api/worlds/{worldId}/buildings` | `{type, x, y, ownerId}` | the building. Needs a held hammer, the right ground and the cost |
| `DELETE /api/worlds/{worldId}/buildings/{id}?userId=` | | owner only |
| `POST /api/worlds/{worldId}/boats` | `{portId, ownerId}` | the boat. Needs your own port, a held hammer and the cost |

## WebSocket

`ws://<host>:8080/ws/worlds/{worldId}?userId={userId}&clientId={tab id}`

- `clientId` identifies the browser tab. The same tab reconnecting takes
  over its old connection, and a different tab is refused while that user
  is playing (see D19 in [decisions.md](decisions.md)). If it's left out,
  every connection counts as a different tab.
- A refused connection gets an `error` event and is then closed. The
  client goes back to the welcome screen.

### Server → client

Every message is `{"type": "...", ...fields}`.

**`snapshot`**: sent right after connecting, and again after each
reconnect.

| Field | Contents |
|---|---|
| `state` | your vitals: `{hunger, hungerDrainPerSecond}` (see `state` below) |
| `inventory` | your inventory (see `inventory` below) |
| `recipes` | `[{id, output, ingredients: [{item, quantity}]}]` |
| `you` | where you were when you last left: `{x, y, boatId}`, or `null` the first time |
| `buildings` | `[building]` (see below) |
| `boats` | `[{id, ownerId, x, y, movement}]` |
| `players` | other players in the world: `[{userId, username, color, x, y, boatId, movement}]` |
| `nodes` | resource spots: `[{id, type, x, y, regrowsInMs}]` (`regrowsInMs` is 0 when usable now) |

A **movement** is `{from, path: [points], elapsedMs}`, or `null`. Start at
`from`, visit each point at the mover's fixed speed, and skip ahead by
`elapsedMs`. `x`/`y` is where the movement ends.

A **building** is
`{id, type, x, y, ownerId, ownerName, ownerColor, storedWood, woodPerSecond, woodCapacity}`.
The last three are only non-zero for lumberjacks, and let the client count
the stored wood up between updates.

**Everyone in the world receives:**

| Type | Fields | When |
|---|---|---|
| `playerJoined` | `player` | someone sent `join` |
| `playerLeft` | `userId` | their last tab closed |
| `playerMoved` | `userId, movement` | someone started walking |
| `playerBoarded` | `userId, boatId` | someone stepped aboard |
| `boatMoved` | `boatId, movement` | someone started sailing |
| `playerLanded` | `userId, position` | someone stepped off onto an island |
| `buildingAdded` | `building` | built (by REST) |
| `buildingUpdated` | `building` | e.g. a lumberjack's wood was collected |
| `buildingRemoved` | `buildingId` | destroyed |
| `boatAdded` | `boat` | built (by REST) |
| `nodeDepleted` | `nodeId, regrowsInMs` | a bush was picked or a tree chopped |
| `nodeAdded` | `node` | sticks or stones dropped |
| `nodeRemoved` | `nodeId` | sticks or stones were picked up |

The player who caused a `playerJoined`, `playerMoved`, `playerBoarded`,
`boatMoved` or `playerLanded` event doesn't get it back. All the other
events go to everyone in the world, including whoever caused them.

**Only you receive:**

| Type | Fields | When |
|---|---|---|
| `state` | `state: {hunger, hungerDrainPerSecond}` | on joining, after eating. The client counts hunger down between updates |
| `inventory` | `inventory: {slots: [{slot, item, quantity}], selectedSlot, hotbarSize, bagSize}` | after anything changes it |
| `error` | `message` | something you sent was refused |

### Client → server

| Type | Fields | Does |
|---|---|---|
| `join` | `position` | makes you visible to others. Send after the snapshot |
| `move` | `from, path` | walk (on foot only) |
| `board` | `boatId` | step aboard your own boat |
| `sail` | `boatId, from, path` | sail the boat you're aboard |
| `land` | `boatId, position` | step off onto an island |
| `gather` | `nodeId` | pick berries, sticks or stones, or chop a tree (needs a held axe). You must be standing next to it |
| `select` | `slot` | hold what's in quick-access slot 0–9 |
| `moveItem` | `fromSlot, toSlot` | move, stack or swap in the inventory (slots 0–49) |
| `discard` | `slot` | throw away a slot |
| `use` | | use what you're holding: eat a held berry |
| `craft` | `recipe` | e.g. `"AXE"` |
| `collect` | `buildingId` | take the wood your lumberjack stored (stand next to it) |

After a reconnect, the client re-sends `join`, `select`, and `board` or
`sail` or `move` for whatever it was doing.
