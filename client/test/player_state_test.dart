import 'package:client/protocol.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('health and hunger count on between updates, within their limits', () {
    final state = PlayerState.fromJson({
      'hunger': 10,
      'hungerDrainPerSecond': 0.5,
      'health': 98,
      'healthChangePerSecond': 1.0,
    });

    final later = state.after(30);

    expect(later.hunger, 0); // not below zero
    expect(later.health, 100); // not above full
  });

  test('older servers without health still work', () {
    final state = PlayerState.fromJson({'hunger': 80, 'hungerDrainPerSecond': 0.1});

    expect(state.health, PlayerState.maxHealth);
    expect(state.after(10).health, PlayerState.maxHealth);
  });
}
