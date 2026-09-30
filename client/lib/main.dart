import 'package:flutter/material.dart';

import 'screens/welcome_screen.dart';

void main() {
  runApp(const IslandApp());
}

class IslandApp extends StatelessWidget {
  const IslandApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Island',
      theme: ThemeData(colorSchemeSeed: Colors.green, useMaterial3: true),
      home: const WelcomeScreen(),
    );
  }
}
