import 'package:client/live_connection.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // Also run in Chrome (flutter test --platform chrome): that's where the
  // game runs, and number handling differs there.
  test('each tab gets its own id', () {
    final ids = List.generate(50, (_) => newClientId());

    expect(ids.toSet(), hasLength(50));
    expect(ids.every((id) => RegExp(r'^[0-9a-f]{16}$').hasMatch(id)), isTrue);
  });
}
