import 'package:client/island_game.dart';
import 'package:client/protocol.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads an animal, and it moves at the speed the server gave it', () {
    final record = animalFromJson({
      'id': 7,
      'type': 'BOAR',
      'x': 0.0,
      'y': 0.0,
      'health': 2,
      'maxHealth': 3,
      'speed': 250.0,
      'movement': {
        'from': {'x': 0.0, 'y': 0.0},
        'path': [
          {'x': 1000.0, 'y': 0.0}
        ],
        'elapsedMs': 1000,
      },
    });

    final boar = AnimalComponent.fromRecord(record);

    expect(boar, isA<BoarComponent>());
    expect(boar.health, 2);
    // Already a second into its walk at 250 per second.
    expect(boar.position.x, closeTo(250, 0.001));
    boar.update(2);
    expect(boar.position.x, closeTo(750, 0.001));
  });
}
