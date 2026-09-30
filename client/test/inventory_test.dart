import 'package:client/inventory.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final json = {
    'slots': [
      {'slot': 0, 'item': 'STICK', 'quantity': 12},
      {'slot': 1, 'item': 'BERRY', 'quantity': 3},
      {'slot': 14, 'item': 'STICK', 'quantity': 50},
    ],
    'selectedSlot': 1,
    'hotbarSize': 10,
    'bagSize': 40,
  };

  test('reads the inventory the server sends', () {
    final inventory = InventoryState.fromJson(json);

    expect(inventory.slots[14]!.item, ItemType.stick);
    expect(inventory.count(ItemType.stick), 62);
    expect(inventory.held!.item, ItemType.berry);
    expect(inventory.withSelected(5).held, isNull);
  });

  testWidgets('in the bag, tap an item then a slot to move it there', (tester) async {
    final moves = <(int, int)>[];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: BagSheet(
          inventory: ValueNotifier(InventoryState.fromJson(json)),
          onMove: (from, to) => moves.add((from, to)),
          onDiscard: (_) {},
        ),
      ),
    ));

    final slots = find.byType(ItemSlot);
    expect(slots, findsNWidgets(50));
    await tester.tap(slots.at(0));
    await tester.pump();
    expect(find.textContaining('Throw away sticks'), findsOneWidget);
    await tester.tap(slots.at(25));
    await tester.pump();

    expect(moves, [(0, 25)]);
    expect(find.textContaining('Throw away'), findsNothing);
  });
}
