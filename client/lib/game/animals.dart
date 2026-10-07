import 'dart:ui';

import 'package:flame/components.dart';

import '../island_game.dart';

/// A rabbit or boar. It only goes where the server tells it to, along paths
/// it simulates like everything else that moves.
abstract class AnimalComponent extends PositionComponent with PathFollower {
  final int id;
  final AnimalType type;
  int health;
  final int maxHealth;

  @override
  final double speed;

  AnimalComponent({
    required this.id,
    required this.type,
    required this.health,
    required this.maxHealth,
    required this.speed,
    required Vector2 position,
    required Vector2 size,
  }) : super(position: position, size: size, anchor: Anchor.center, priority: 6);

  factory AnimalComponent.fromRecord(AnimalRecord record) {
    final position = Vector2(record.x, record.y);
    final animal = switch (record.type) {
      AnimalType.rabbit => RabbitComponent(record, position),
      AnimalType.boar => BoarComponent(record, position),
    };
    final movement = record.movement;
    if (movement != null) animal.follow(movement);
    return animal;
  }

  @override
  void update(double dt) {
    super.update(dt);
    advanceAlongPath(dt);
  }

  /// A small bar above it once it's been hurt.
  void renderHealth(Canvas canvas) {
    if (health >= maxHealth) return;
    final width = size.x * 0.9;
    final left = (size.x - width) / 2;
    final top = -size.y * 0.25;
    canvas.drawRect(Rect.fromLTWH(left, top, width, size.y * 0.1), Paint()..color = const Color(0x99000000));
    canvas.drawRect(
      Rect.fromLTWH(left, top, width * health / maxHealth, size.y * 0.1),
      Paint()..color = const Color(0xFFE53935),
    );
  }
}

class RabbitComponent extends AnimalComponent {
  RabbitComponent(AnimalRecord record, Vector2 position)
      : super(
          id: record.id,
          type: AnimalType.rabbit,
          health: record.health,
          maxHealth: record.maxHealth,
          speed: record.speed,
          position: position,
          size: Vector2(36, 30),
        );

  @override
  void render(Canvas canvas) {
    final fur = Paint()..color = const Color(0xFFD7CCC8);
    final inner = Paint()..color = const Color(0xFFF8BBD0);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.1, size.y * 0.35, size.x * 0.7, size.y * 0.6), fur); // body
    canvas.drawCircle(Offset(size.x * 0.78, size.y * 0.42), size.y * 0.22, fur); // head
    canvas.drawOval(Rect.fromLTWH(size.x * 0.68, 0, size.x * 0.12, size.y * 0.4), fur); // ears
    canvas.drawOval(Rect.fromLTWH(size.x * 0.82, 0, size.x * 0.12, size.y * 0.4), fur);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.705, size.y * 0.06, size.x * 0.05, size.y * 0.26), inner);
    canvas.drawCircle(Offset(size.x * 0.12, size.y * 0.62), size.y * 0.12, Paint()..color = const Color(0xFFFFFFFF)); // tail
    canvas.drawCircle(Offset(size.x * 0.84, size.y * 0.4), size.y * 0.05, Paint()..color = const Color(0xFF3E2723));
    renderHealth(canvas);
  }
}

class BoarComponent extends AnimalComponent {
  BoarComponent(AnimalRecord record, Vector2 position)
      : super(
          id: record.id,
          type: AnimalType.boar,
          health: record.health,
          maxHealth: record.maxHealth,
          speed: record.speed,
          position: position,
          size: Vector2(60, 40),
        );

  @override
  void render(Canvas canvas) {
    final hide = Paint()..color = const Color(0xFF5D4037);
    final dark = Paint()..color = const Color(0xFF3E2723);
    final tusk = Paint()..color = const Color(0xFFFFF8E1);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.05, size.y * 0.2, size.x * 0.7, size.y * 0.65), hide); // body
    canvas.drawOval(Rect.fromLTWH(size.x * 0.6, size.y * 0.25, size.x * 0.35, size.y * 0.5), hide); // head
    canvas.drawOval(Rect.fromLTWH(size.x * 0.86, size.y * 0.4, size.x * 0.12, size.y * 0.22), dark); // snout
    for (final x in [0.15, 0.3, 0.5, 0.62]) {
      canvas.drawRect(Rect.fromLTWH(size.x * x, size.y * 0.78, size.x * 0.07, size.y * 0.2), dark); // legs
    }
    final tuskPath = Path()
      ..moveTo(size.x * 0.86, size.y * 0.6)
      ..lineTo(size.x * 0.92, size.y * 0.42)
      ..lineTo(size.x * 0.9, size.y * 0.62)
      ..close();
    canvas.drawPath(tuskPath, tusk);
    canvas.drawCircle(Offset(size.x * 0.78, size.y * 0.38), size.y * 0.05, Paint()..color = const Color(0xFFFFCC80));
    renderHealth(canvas);
  }
}
