# Gameplay

What a player can do, and the rules behind it. The exact numbers change as
the game gets tuned, so this page names the rules and points to where each
value lives in the code. For *why* things work this way, see
[decisions.md](decisions.md).

## The world

- A **world** is one independent save. Players pick a user and a world on
  the welcome screen.
- Each world has a **home island** at the center, where everyone starts,
  and a few smaller **outer islands** further out. The layout is made once,
  when the world is created (`WorldService.generateIslands`).
- Islands have a **sand ring** around **grass**. Ports go on sand;
  lumberjacks, trees, bushes, sticks and stones go on grass.
- The **minimap** in the top right shows the whole world. Tap it to look
  somewhere else, and use the recenter button to follow your character
  again.

## World settings

Chosen in a dialog when a world is created. **They can't be changed
afterwards.** Hover over a world on the welcome screen to see its
settings.

| Setting | Options | Default |
|---|---|---|
| Animal aggression | on: boars attack players / off: animals never attack | on |
| When you die, your bag is... | **kept** / **dropped** in a pile where you died, to go back for before it disappears / **lost** | dropped |

Your quick-access bar is always kept. (Animals are being built, so
*animal aggression* doesn't change anything yet.)

## Getting around

- **Walk:** tap a spot on the island you're on.
- **Boats:** tap your own boat to walk to the shore and board it. Tap the
  sea to sail (the boat goes around islands on its way). Tap an island to
  land on its nearest shore.
- Only a boat's **owner** can board it.
- After a reload you're back **where you left off**: on the same island,
  or aboard your boat if you were sailing.

## Resource spots

| Spot | Gather with | Gives | Afterwards |
|---|---|---|---|
| Berry bush | hands | berries | bare, then regrows |
| Sticks | hands | wood | gone; new sticks drop somewhere else |
| Stones | hands | stone | gone; new stones drop somewhere else |
| Tree | a **held axe** | wood | a stump, which grows back into a tree |

- Tap a spot to walk next to it. A ring fills above your head (a little
  longer for chopping), then you get the items. Walking away cancels it.
- Spots are **shared**: if someone else takes it first, it's gone for you
  too.
- **Sticks and stones** drop at random free spots while someone is playing,
  up to a limit per island. There are more on the outer islands. A world
  nobody played in catches up on the drops it missed when someone enters.
- Amounts, regrow times and limits are in
  `backend/.../service/ResourceNodeService.java` (the constants at the
  top). Gather and chop times are `gatherSeconds` and `chopSeconds` in
  `client/lib/island_game.dart`.

## Inventory

- **Quick-access bar** along the bottom (10 slots) plus a **bag** (40 slots).
- Basic resources stack to **50 per slot**. Tools take one slot each and
  don't wear out.
- **What you hold** is the selected quick-access slot. Select it by
  tapping, or with keys **1–9, 0**.
- **Bag** (🎒 button): tap an item, then tap where it should go. It moves,
  stacks onto the same item, or swaps with what's there. There's also
  **Throw away**.
- A full bag stops gathering; the spot is left as it was.
- Everyone starts **empty-handed**.
- Sizes are in `backend/.../service/InventoryService.java`, stack sizes in
  `backend/.../model/ItemType.java`.

## Crafting

- The 🛠 button lists what you can make, showing what each recipe needs
  against what you have.
- **Axe** and **hammer**, each from wood and stones
  (`backend/.../model/Recipe.java`).
- Crafting is instant and works anywhere. Missing ingredients or a full bag
  means nothing is used up.

## Tools

Tools only do something **while held**:

- **Axe:** chop trees.
- **Hammer:** build anything, including boats.
- **Spear:** planned, together with hunting (see [roadmap.md](roadmap.md)).

## Food, hunger and health

- A **hunger bar** at the top left goes from full to empty over about 15
  minutes of play. It pauses while you're away, so time away doesn't count.
- **Eat** by holding berries and pressing **Eat** (or **E**).
- At 0 you're **starving**: you can still move and pick berries, but you
  can't gather wood, chop or build until you eat, and you **lose health**.
- A **health bar** sits above it. Health slowly **comes back while you're
  well fed** (hunger above half) and **drops while you're starving**. Like
  hunger, it only changes while you're in the world.
- Values are in `backend/.../service/PlayerVitalsService.java`.

## Dying

- At 0 health you die and wake up in the middle of the **home island**,
  with full health and hunger at half.
- What happens to your **bag** depends on the world's setting (see *World
  settings*): kept, **dropped** in a sack where you died, or lost. Your
  **quick-access bar is always kept**.
- A **dropped sack** can only be picked up by you, and it disappears after
  about 10 minutes. If you died at sea, it's left on the nearest shore. Tap
  it to pick up as much as fits in your bag; anything left stays in the
  sack.
- A message tells you what happened, and everyone else sees you reappear
  at home.

## Building

Needs a **held hammer**. Open the **Build** button, place the preview (green
means allowed), and tap to confirm.

| Building | Where | Cost | What it does |
|---|---|---|---|
| Port | sand | free | lets you build boats there |
| Lumberjack | grass | wood | slowly fills its own storage with wood |
| Boat | from your port's menu | wood | sail to other islands |

- **1 lumberjack per player** per world.
- **Lumberjack storage:** a badge on your lumberjack shows the wood that's
  ready. Tap it and choose **Collect wood** to walk over and take it,
  as much as fits in your bag. Destroying a lumberjack loses what's
  stored.
- Only a building's **owner** can use or remove it.
- Costs and lumberjack rates are in `backend/.../service/WorldService.java`.

## Players

- Everyone in a world sees everyone else move, board, sail and land live.
- **One tab per user:** while someone plays as a user, the welcome screen
  shows them as "(playing)" and they can't be picked.
- Your inventory and hunger are private. Buildings, boats and resource
  spots are shared.
