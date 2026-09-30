import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';

import '../island_game.dart';

/// A tree, berry bush or pile of sticks on an island. Shared by everyone:
/// once it's been gathered from, it looks bare until [regrowsAt].
abstract class ResourceNodeComponent extends PositionComponent
    with TapCallbacks, HasGameReference<IslandGame> {
  final int id;
  final ResourceNodeType type;

  /// When it can be gathered from again, or null if it can be right now.
  DateTime? regrowsAt;

  ResourceNodeComponent({
    required this.id,
    required this.type,
    required Vector2 position,
    required Vector2 size,
    Anchor anchor = Anchor.center,
  }) : super(position: position, size: size, anchor: anchor, priority: 4);

  bool get isAvailable {
    final at = regrowsAt;
    return at == null || !DateTime.now().isBefore(at);
  }

  void setRegrowsIn(Duration regrowsIn) {
    regrowsAt = regrowsIn <= Duration.zero ? null : DateTime.now().add(regrowsIn);
  }

  @override
  void onTapDown(TapDownEvent event) {
    super.onTapDown(event);
    event.continuePropagation = false;
    game.requestGather(this);
  }
}

class TreeComponent extends ResourceNodeComponent {
  TreeComponent({required super.id, required super.position, required double islandRadius})
      : super(
          type: ResourceNodeType.tree,
          anchor: Anchor.bottomCenter,
          size: Vector2(islandRadius * 0.1, islandRadius * 0.16),
        );

  @override
  void render(Canvas canvas) {
    final trunkPaint = Paint()..color = const Color(0xFF6D4C29);
    final canopyPaint = Paint()..color = const Color(0xFF2E7D32);

    if (!isAvailable) {
      // Chopped: a stump with a lighter cut top, until it grows back.
      final stump = Rect.fromLTWH(size.x * 0.3, size.y * 0.8, size.x * 0.4, size.y * 0.2);
      canvas.drawRect(stump, trunkPaint);
      canvas.drawOval(
        Rect.fromLTWH(size.x * 0.3, size.y * 0.76, size.x * 0.4, size.y * 0.08),
        Paint()..color = const Color(0xFFD7B899),
      );
      return;
    }

    final trunkWidth = size.x * 0.25;
    final trunkHeight = size.y * 0.4;
    final trunkRect = Rect.fromLTWH(
      (size.x - trunkWidth) / 2,
      size.y - trunkHeight,
      trunkWidth,
      trunkHeight,
    );
    canvas.drawRect(trunkRect, trunkPaint);

    final canopyRadius = size.x * 0.5;
    final canopyCenter = Offset(size.x / 2, size.y - trunkHeight - canopyRadius * 0.6);
    canvas.drawCircle(canopyCenter, canopyRadius, canopyPaint);
  }
}

/// A round bush dotted with red berries - just leaves once it's been picked.
class BerryBushComponent extends ResourceNodeComponent {
  static const _berrySpots = [
    Offset(0.30, 0.35),
    Offset(0.62, 0.30),
    Offset(0.45, 0.58),
    Offset(0.72, 0.60),
    Offset(0.25, 0.65),
  ];

  BerryBushComponent({required super.id, required super.position, required double islandRadius})
      : super(type: ResourceNodeType.berryBush, size: Vector2.all(islandRadius * 0.09));

  @override
  void render(Canvas canvas) {
    final leafPaint = Paint()..color = const Color(0xFF388E3C);
    final darkLeafPaint = Paint()..color = const Color(0xFF2E7D32);
    canvas.drawCircle(Offset(size.x * 0.35, size.y * 0.55), size.x * 0.32, darkLeafPaint);
    canvas.drawCircle(Offset(size.x * 0.65, size.y * 0.55), size.x * 0.32, darkLeafPaint);
    canvas.drawCircle(Offset(size.x * 0.5, size.y * 0.4), size.x * 0.36, leafPaint);

    if (!isAvailable) return;
    final berryPaint = Paint()..color = const Color(0xFFD32F2F);
    for (final spot in _berrySpots) {
      canvas.drawCircle(Offset(spot.dx * size.x, spot.dy * size.y), size.x * 0.07, berryPaint);
    }
  }
}

/// A few crossed sticks on the ground - a faint outline once they've been
/// taken.
class SticksComponent extends ResourceNodeComponent {
  SticksComponent({required super.id, required super.position, required double islandRadius})
      : super(type: ResourceNodeType.sticks, size: Vector2.all(islandRadius * 0.08));

  @override
  void render(Canvas canvas) {
    final paint = Paint()
      ..color = isAvailable ? const Color(0xFF8D6E63) : const Color(0x33000000)
      ..strokeWidth = size.x * 0.1
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(Offset(size.x * 0.1, size.y * 0.75), Offset(size.x * 0.9, size.y * 0.35), paint);
    canvas.drawLine(Offset(size.x * 0.15, size.y * 0.3), Offset(size.x * 0.85, size.y * 0.8), paint);
    canvas.drawLine(Offset(size.x * 0.3, size.y * 0.9), Offset(size.x * 0.6, size.y * 0.15), paint);
  }
}

/// A few grey stones on the ground - gone once they've been picked up.
class StonesComponent extends ResourceNodeComponent {
  StonesComponent({required super.id, required super.position, required double islandRadius})
      : super(type: ResourceNodeType.stones, size: Vector2.all(islandRadius * 0.08));

  @override
  void render(Canvas canvas) {
    final light = Paint()..color = const Color(0xFFBDBDBD);
    final dark = Paint()..color = const Color(0xFF8E8E8E);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.05, size.y * 0.45, size.x * 0.5, size.y * 0.38), dark);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.4, size.y * 0.3, size.x * 0.5, size.y * 0.42), light);
    canvas.drawOval(Rect.fromLTWH(size.x * 0.3, size.y * 0.62, size.x * 0.36, size.y * 0.3), light);
  }
}
