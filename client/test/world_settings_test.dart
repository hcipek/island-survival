import 'package:client/api.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads world settings, with defaults for older worlds', () {
    final world = WorldData.fromJson({
      'id': 1,
      'name': 'Peaceful',
      'settings': {'animalAggression': false, 'inventoryOnDeath': 'KEEP'},
    });
    expect(world.settings.animalAggression, isFalse);
    expect(world.settings.inventoryOnDeath, InventoryOnDeath.keep);
    expect(world.settings.summary, 'Peaceful animals, keep everything on death');

    final older = WorldData.fromJson({'id': 2, 'name': 'Homeland'});
    expect(older.settings.animalAggression, isTrue);
    expect(older.settings.inventoryOnDeath, InventoryOnDeath.drop);
    // An older backend can't change settings, so editing isn't offered.
    expect(older.started, isTrue);
  });
}
