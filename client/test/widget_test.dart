import 'package:flutter_test/flutter_test.dart';

import 'package:client/main.dart';

void main() {
  testWidgets('opens on the welcome screen', (WidgetTester tester) async {
    await tester.pumpWidget(const IslandApp());

    expect(find.text('Welcome'), findsOneWidget);
  });
}
