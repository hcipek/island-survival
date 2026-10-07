import 'package:client/api.dart';
import 'package:client/screens/welcome_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Answers with fixed users and worlds instead of calling the backend.
class _FakeApi extends IslandApi {
  final alice = UserData(id: 1, name: 'Alice', color: const Color(0xFFD94C7F));
  final bob = UserData(id: 2, name: 'Bob', color: const Color(0xFFC7D94C), playing: true);
  final carol = UserData(id: 3, name: 'Carol', color: const Color(0xFF4CD9BB), playing: true);

  @override
  Future<List<UserData>> fetchUsers() async => [alice, bob, carol];

  @override
  Future<List<WorldData>> fetchWorlds() async => [
        WorldData(id: 1, name: 'Homeland', started: true, playing: [bob, carol]),
        WorldData(id: 2, name: 'Fresh', started: false),
        WorldData(id: 3, name: 'Quiet', started: true),
      ];
}

void main() {
  Future<void> open(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 1;
    await tester.pumpWidget(MaterialApp(home: WelcomeScreen(api: _FakeApi())));
    await tester.pumpAndSettle();
  }

  Future<void> close(WidgetTester tester) async {
    // Stops the screen's refresh timer.
    await tester.pumpWidget(const SizedBox());
  }

  testWidgets('first you pick who you are; people already playing are greyed out', (tester) async {
    await open(tester);

    expect(find.text('Who are you?'), findsOneWidget);
    expect(find.text('Bob (playing)'), findsOneWidget);
    expect(tester.widget<InputChip>(find.widgetWithText(InputChip, 'Bob (playing)')).isEnabled, isFalse);
    expect(find.text('Homeland'), findsNothing); // worlds come in step 2

    await tester.tap(find.text('Alice'));
    await tester.pumpAndSettle();

    expect(find.text('Playing as Alice'), findsOneWidget);
    await close(tester);
  });

  testWidgets('then each world shows its status and what you can do with it', (tester) async {
    await open(tester);
    await tester.tap(find.text('Alice'));
    await tester.pumpAndSettle();

    // Homeland: started, two playing, can't be deleted or edited.
    final homeland = find.ancestor(of: find.text('Homeland'), matching: find.byType(Card));
    expect(find.descendant(of: homeland, matching: find.text('2 playing')), findsOneWidget);
    expect(find.descendant(of: homeland, matching: find.text('Bob')), findsOneWidget);
    expect(find.descendant(of: homeland, matching: find.text('Edit settings')), findsNothing);
    final deleteHomeland = find.descendant(of: homeland, matching: find.byIcon(Icons.delete_outline));
    expect(tester.widget<IconButton>(find.ancestor(of: deleteHomeland, matching: find.byType(IconButton))).onPressed,
        isNull);

    // Fresh: not started, so its settings can still change.
    final fresh = find.ancestor(of: find.text('Fresh'), matching: find.byType(Card));
    expect(find.descendant(of: fresh, matching: find.text('Not started yet')), findsOneWidget);
    expect(find.descendant(of: fresh, matching: find.text('Edit settings')), findsOneWidget);

    // Quiet: started, nobody there.
    final quiet = find.ancestor(of: find.text('Quiet'), matching: find.byType(Card));
    expect(find.descendant(of: quiet, matching: find.text('Nobody playing')), findsOneWidget);

    // And back to picking someone else.
    await tester.tap(find.text('Change'));
    await tester.pumpAndSettle();
    expect(find.text('Who are you?'), findsOneWidget);
    await close(tester);
  });
}
