import 'package:flutter_test/flutter_test.dart';

import 'package:client/main.dart';

void main() {
  testWidgets('renders the island app bar', (WidgetTester tester) async {
    await tester.pumpWidget(const IslandApp());

    expect(find.text('My Island'), findsOneWidget);
  });
}
