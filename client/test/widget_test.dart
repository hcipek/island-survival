import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:client/main.dart';

void main() {
  testWidgets('opens on the welcome screen', (WidgetTester tester) async {
    await tester.pumpWidget(const IslandApp());

    expect(find.text('Welcome'), findsOneWidget);

    // Close the screen, which stops its "who's playing" refresh timer.
    await tester.pumpWidget(const SizedBox());
  });
}
