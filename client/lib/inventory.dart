import 'dart:math';

import 'package:flutter/material.dart';

/// Everything that can go in an inventory slot. Mirrors the server's
/// `ItemType`.
enum ItemType { stick, stone, berry, wood, axe, hammer }

ItemType itemTypeFromJson(String value) => switch (value) {
      'STICK' => ItemType.stick,
      'STONE' => ItemType.stone,
      'BERRY' => ItemType.berry,
      'WOOD' => ItemType.wood,
      'AXE' => ItemType.axe,
      'HAMMER' => ItemType.hammer,
      _ => throw Exception('Unknown item: $value'),
    };

String itemName(ItemType item) => switch (item) {
      ItemType.stick => 'Sticks',
      ItemType.stone => 'Stones',
      ItemType.berry => 'Berries',
      ItemType.wood => 'Wood',
      ItemType.axe => 'Axe',
      ItemType.hammer => 'Hammer',
    };

class ItemStack {
  final ItemType item;
  final int quantity;

  const ItemStack(this.item, this.quantity);
}

/// The player's inventory as the server last described it. Slots 0-9 are
/// the quick-access bar - whatever's selected there is what they're
/// holding - and the rest is the bag.
class InventoryState {
  final Map<int, ItemStack> slots;
  final int selectedSlot;
  final int hotbarSize;
  final int bagSize;

  const InventoryState({
    required this.slots,
    required this.selectedSlot,
    required this.hotbarSize,
    required this.bagSize,
  });

  static const empty = InventoryState(slots: {}, selectedSlot: 0, hotbarSize: 10, bagSize: 40);

  factory InventoryState.fromJson(Map<String, dynamic> json) {
    return InventoryState(
      slots: {
        for (final slot in json['slots'] as List<dynamic>)
          (slot as Map<String, dynamic>)['slot'] as int: ItemStack(
            itemTypeFromJson(slot['item'] as String),
            slot['quantity'] as int,
          ),
      },
      selectedSlot: json['selectedSlot'] as int,
      hotbarSize: json['hotbarSize'] as int,
      bagSize: json['bagSize'] as int,
    );
  }

  /// What's in the player's hand, if anything.
  ItemStack? get held => slots[selectedSlot];

  int count(ItemType item) =>
      slots.values.where((s) => s.item == item).fold(0, (sum, s) => sum + s.quantity);

  InventoryState withSelected(int slot) =>
      InventoryState(slots: slots, selectedSlot: slot, hotbarSize: hotbarSize, bagSize: bagSize);
}

/// The quick-access bar along the bottom of the screen: tap a slot (or press
/// 1-9, 0) to hold what's in it. Ends with a button that opens the bag.
class Hotbar extends StatelessWidget {
  final InventoryState inventory;
  final ValueChanged<int> onSelect;
  final VoidCallback onOpenBag;
  final VoidCallback onOpenCraft;

  const Hotbar({
    super.key,
    required this.inventory,
    required this.onSelect,
    required this.onOpenBag,
    required this.onOpenCraft,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      // Shrinks to fit phone screens: 10 slots plus the bag and craft buttons.
      final slotSize = min(48.0, (constraints.maxWidth - 28) / (inventory.hotbarSize + 2) - 4);
      return Card(
        color: Colors.black.withValues(alpha: 0.55),
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < inventory.hotbarSize; i++)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                  child: ItemSlot(
                    stack: inventory.slots[i],
                    size: slotSize,
                    highlighted: i == inventory.selectedSlot,
                    label: '${(i + 1) % 10}',
                    onTap: () => onSelect(i),
                  ),
                ),
              for (final (tooltip, icon, onPressed) in [
                ('Open bag', Icons.backpack, onOpenBag),
                ('Craft', Icons.handyman, onOpenCraft),
              ])
                Padding(
                  padding: const EdgeInsets.only(left: 4),
                  child: SizedBox(
                    width: slotSize,
                    height: slotSize,
                    child: IconButton.filledTonal(
                      padding: EdgeInsets.zero,
                      tooltip: tooltip,
                      onPressed: onPressed,
                      icon: Icon(icon, size: slotSize * 0.55),
                    ),
                  ),
                ),
            ],
          ),
        ),
      );
    });
  }
}

/// One inventory slot: the item's icon with its amount in the corner.
class ItemSlot extends StatelessWidget {
  final ItemStack? stack;
  final double size;
  final bool highlighted;
  final String? label;
  final VoidCallback? onTap;

  const ItemSlot({
    super.key,
    required this.stack,
    required this.size,
    this.highlighted = false,
    this.label,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final stack = this.stack;
    return Tooltip(
      message: stack == null ? '' : itemName(stack.item),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: highlighted ? 0.28 : 0.12),
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: highlighted ? Colors.amberAccent : Colors.white24,
              width: highlighted ? 2.5 : 1,
            ),
          ),
          child: Stack(
            children: [
              if (stack != null)
                Center(child: ItemIcon(item: stack.item, size: size * 0.7)),
              if (label != null)
                Positioned(
                  left: 3,
                  top: 1,
                  child: Text(label!, style: TextStyle(color: Colors.white54, fontSize: size * 0.2)),
                ),
              if (stack != null && stack.quantity > 1)
                Positioned(
                  right: 3,
                  bottom: 1,
                  child: Text(
                    '${stack.quantity}',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: size * 0.26,
                      fontWeight: FontWeight.bold,
                      shadows: const [Shadow(blurRadius: 2)],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The whole inventory: quick-access bar on top, bag below. Tap an item to
/// pick it up, then tap another slot to move it there (swapping or stacking
/// as needed). Stays in sync with [inventory] while open.
class BagSheet extends StatefulWidget {
  final ValueNotifier<InventoryState> inventory;
  final void Function(int from, int to) onMove;
  final ValueChanged<int> onDiscard;

  const BagSheet({super.key, required this.inventory, required this.onMove, required this.onDiscard});

  @override
  State<BagSheet> createState() => _BagSheetState();
}

class _BagSheetState extends State<BagSheet> {
  int? _picked;

  void _tap(InventoryState inventory, int slot) {
    final picked = _picked;
    if (picked == null) {
      if (inventory.slots.containsKey(slot)) setState(() => _picked = slot);
      return;
    }
    if (picked != slot) widget.onMove(picked, slot);
    setState(() => _picked = null);
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<InventoryState>(
      valueListenable: widget.inventory,
      builder: (context, inventory, _) {
        final picked = _picked;
        final pickedStack = picked == null ? null : inventory.slots[picked];
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            child: LayoutBuilder(builder: (context, constraints) {
              final slotSize = min(52.0, (constraints.maxWidth - 9 * 6) / 10);
              Widget row(int first) => Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      for (var i = first; i < first + 10; i++)
                        Padding(
                          padding: const EdgeInsets.all(3),
                          child: ItemSlot(
                            stack: inventory.slots[i],
                            size: slotSize,
                            highlighted: i == picked,
                            onTap: () => _tap(inventory, i),
                          ),
                        ),
                    ],
                  );
              final total = inventory.hotbarSize + inventory.bagSize;
              return Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text('Inventory', style: Theme.of(context).textTheme.titleLarge),
                      const Spacer(),
                      if (pickedStack != null)
                        TextButton.icon(
                          onPressed: () {
                            widget.onDiscard(picked!);
                            setState(() => _picked = null);
                          },
                          icon: const Icon(Icons.delete_outline, color: Colors.red),
                          label: Text('Throw away ${itemName(pickedStack.item).toLowerCase()}'),
                        ),
                      IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
                    ],
                  ),
                  Text(
                    picked == null ? 'Tap an item to move it' : 'Now tap where it should go',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 8),
                  Text('Quick access', style: Theme.of(context).textTheme.labelLarge),
                  row(0),
                  const SizedBox(height: 8),
                  Text('Bag', style: Theme.of(context).textTheme.labelLarge),
                  for (var first = inventory.hotbarSize; first < total; first += 10) row(first),
                ],
              );
            }),
          ),
        );
      },
    );
  }
}

/// A small drawing of an item, in the same simple style as the world.
class ItemIcon extends StatelessWidget {
  final ItemType item;
  final double size;

  const ItemIcon({super.key, required this.item, required this.size});

  @override
  Widget build(BuildContext context) {
    return CustomPaint(size: Size.square(size), painter: _ItemPainter(item));
  }
}

class _ItemPainter extends CustomPainter {
  final ItemType item;

  _ItemPainter(this.item);

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    Paint stroke(Color color, double width) => Paint()
      ..color = color
      ..strokeWidth = w * width
      ..strokeCap = StrokeCap.round;
    const brown = Color(0xFF8D6E63);
    const darkBrown = Color(0xFF5D4037);
    const grey = Color(0xFF9E9E9E);

    switch (item) {
      case ItemType.stick:
        canvas.drawLine(Offset(w * 0.15, w * 0.8), Offset(w * 0.85, w * 0.25), stroke(brown, 0.1));
        canvas.drawLine(Offset(w * 0.2, w * 0.3), Offset(w * 0.8, w * 0.85), stroke(brown, 0.1));
      case ItemType.stone:
        final path = Path()
          ..moveTo(w * 0.2, w * 0.65)
          ..lineTo(w * 0.3, w * 0.35)
          ..lineTo(w * 0.55, w * 0.25)
          ..lineTo(w * 0.8, w * 0.4)
          ..lineTo(w * 0.85, w * 0.7)
          ..lineTo(w * 0.5, w * 0.82)
          ..close();
        canvas.drawPath(path, Paint()..color = grey);
        canvas.drawLine(Offset(w * 0.4, w * 0.4), Offset(w * 0.55, w * 0.35), stroke(Colors.white38, 0.05));
      case ItemType.berry:
        final red = Paint()..color = const Color(0xFFD32F2F);
        canvas.drawCircle(Offset(w * 0.38, w * 0.6), w * 0.17, red);
        canvas.drawCircle(Offset(w * 0.64, w * 0.62), w * 0.17, red);
        canvas.drawCircle(Offset(w * 0.5, w * 0.38), w * 0.17, red);
        canvas.drawLine(Offset(w * 0.5, w * 0.22), Offset(w * 0.68, w * 0.12), stroke(const Color(0xFF388E3C), 0.08));
      case ItemType.wood:
        final log = RRect.fromRectAndRadius(Rect.fromLTWH(w * 0.1, w * 0.35, w * 0.7, w * 0.32), Radius.circular(w * 0.08));
        canvas.drawRRect(log, Paint()..color = brown);
        canvas.drawOval(Rect.fromLTWH(w * 0.66, w * 0.33, w * 0.22, w * 0.36), Paint()..color = const Color(0xFFD7B899));
        canvas.drawOval(Rect.fromLTWH(w * 0.72, w * 0.43, w * 0.1, w * 0.16), Paint()..color = darkBrown);
      case ItemType.axe:
        canvas.drawLine(Offset(w * 0.3, w * 0.88), Offset(w * 0.62, w * 0.15), stroke(brown, 0.1));
        final blade = Path()
          ..moveTo(w * 0.55, w * 0.18)
          ..lineTo(w * 0.88, w * 0.22)
          ..lineTo(w * 0.82, w * 0.5)
          ..lineTo(w * 0.5, w * 0.35)
          ..close();
        canvas.drawPath(blade, Paint()..color = grey);
      case ItemType.hammer:
        canvas.drawLine(Offset(w * 0.5, w * 0.9), Offset(w * 0.5, w * 0.3), stroke(brown, 0.1));
        final head = RRect.fromRectAndRadius(Rect.fromLTWH(w * 0.2, w * 0.12, w * 0.6, w * 0.24), Radius.circular(w * 0.05));
        canvas.drawRRect(head, Paint()..color = grey);
    }
  }

  @override
  bool shouldRepaint(_ItemPainter oldDelegate) => oldDelegate.item != item;
}

/// Something the player can make, as the server describes it.
class Recipe {
  final String id;
  final ItemType output;
  final List<ItemStack> ingredients;

  const Recipe({required this.id, required this.output, required this.ingredients});

  factory Recipe.fromJson(Map<String, dynamic> json) {
    return Recipe(
      id: json['id'] as String,
      output: itemTypeFromJson(json['output'] as String),
      ingredients: [
        for (final ingredient in json['ingredients'] as List<dynamic>)
          ItemStack(
            itemTypeFromJson((ingredient as Map<String, dynamic>)['item'] as String),
            ingredient['quantity'] as int,
          ),
      ],
    );
  }

  bool canMake(InventoryState inventory) =>
      ingredients.every((i) => inventory.count(i.item) >= i.quantity);
}

/// The list of recipes, each showing what it needs against what you have.
/// Stays in sync with [inventory] while open, so you can craft several.
class CraftSheet extends StatelessWidget {
  final List<Recipe> recipes;
  final ValueNotifier<InventoryState> inventory;
  final ValueChanged<Recipe> onCraft;

  const CraftSheet({super.key, required this.recipes, required this.inventory, required this.onCraft});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<InventoryState>(
      valueListenable: inventory,
      builder: (context, inventory, _) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('Craft', style: Theme.of(context).textTheme.titleLarge),
                  const Spacer(),
                  IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
                ],
              ),
              for (final recipe in recipes)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: ItemIcon(item: recipe.output, size: 36),
                  title: Text(itemName(recipe.output)),
                  subtitle: Wrap(
                    spacing: 12,
                    children: [
                      for (final ingredient in recipe.ingredients)
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            ItemIcon(item: ingredient.item, size: 18),
                            const SizedBox(width: 4),
                            Text(
                              '${inventory.count(ingredient.item)}/${ingredient.quantity}',
                              style: TextStyle(
                                color: inventory.count(ingredient.item) >= ingredient.quantity
                                    ? null
                                    : Colors.redAccent,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                    ],
                  ),
                  trailing: FilledButton(
                    onPressed: recipe.canMake(inventory) ? () => onCraft(recipe) : null,
                    child: const Text('Craft'),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
