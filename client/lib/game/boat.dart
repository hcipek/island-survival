import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';

import '../island_game.dart';

class BoatComponent extends PositionComponent
    with TapCallbacks, HasGameReference<IslandGame>, PathFollower {
  @override
  final double speed;
  bool isSelected = false;

  /// The backing row on the server.
  int? backendId;

  final int ownerId;

  BoatComponent({
    required Vector2 startPosition,
    required double islandRadius,
    required this.ownerId,
  })  : speed = islandRadius * 0.6,
        super(
          anchor: Anchor.center,
          position: startPosition,
          size: Vector2(islandRadius * 0.16, islandRadius * 0.1),
          priority: 5,
        );

  /// Follows a sequence of waypoints in order (see [IslandGame._seaPathTo]),
  /// calling [onArrived] once the final one is reached.
  void moveAlongPath(List<Vector2> waypoints, {VoidCallback? onArrived}) {
    followPath(waypoints, onArrived: onArrived);
  }

  @override
  void update(double dt) {
    super.update(dt);
    advanceAlongPath(dt);
  }

  @override
  void render(Canvas canvas) {
    if (isSelected) {
      final ringPaint = Paint()
        ..color = const Color(0xFFFFFFFF)
        ..style = PaintingStyle.stroke
        ..strokeWidth = size.y * 0.08;
      canvas.drawOval(Rect.fromLTWH(0, 0, size.x, size.y).inflate(size.y * 0.15), ringPaint);
    }

    final hullPaint = Paint()..color = const Color(0xFF5D4037);
    final sailPaint = Paint()..color = const Color(0xFFECEFF1);

    final hullRect = Rect.fromLTWH(0, size.y * 0.5, size.x, size.y * 0.5);
    canvas.drawRRect(
      RRect.fromRectAndRadius(hullRect, Radius.circular(size.y * 0.2)),
      hullPaint,
    );

    final sailPath = Path()
      ..moveTo(size.x * 0.5, 0)
      ..lineTo(size.x * 0.5, size.y * 0.55)
      ..lineTo(size.x * 0.15, size.y * 0.55)
      ..close();
    canvas.drawPath(sailPath, sailPaint);
  }

  @override
  void onTapDown(TapDownEvent event) {
    super.onTapDown(event);
    event.continuePropagation = false;
    game.requestBoard(this);
  }
}
