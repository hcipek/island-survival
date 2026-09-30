import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';

import '../island_game.dart';

/// Translucent, glowing preview of a building shown while in placement mode.
class BuildGhostComponent extends PositionComponent
    with DragCallbacks, HasGameReference<IslandGame> {
  bool isValid = false;

  BuildGhostComponent({required double islandRadius})
      : super(
          anchor: Anchor.center,
          size: Vector2.all(islandRadius * 0.22),
          priority: 8,
        );

  @override
  void onDragUpdate(DragUpdateEvent event) {
    super.onDragUpdate(event);
    game.dragGhostBy(event.localDelta / game.cameraComponent.viewfinder.zoom);
  }

  @override
  void onDragEnd(DragEndEvent event) {
    super.onDragEnd(event);
    game.dropGhost();
  }

  @override
  void render(Canvas canvas) {
    final glowColor = isValid ? const Color(0xFF4CAF50) : const Color(0xFFF44336);
    final glowPaint = Paint()
      ..color = glowColor.withAlpha(160)
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.x * 0.1
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6);
    canvas.drawCircle(Offset(size.x / 2, size.y / 2), size.x / 2, glowPaint);

    final fillPaint = Paint()..color = glowColor.withAlpha(50);
    canvas.drawCircle(Offset(size.x / 2, size.y / 2), size.x / 2 * 0.85, fillPaint);

    final silhouettePaint = Paint()..color = const Color(0xFF8D6E63).withAlpha(180);
    final rect = Rect.fromLTWH(size.x * 0.25, size.y * 0.4, size.x * 0.5, size.y * 0.4);
    canvas.drawRect(rect, silhouettePaint);
  }
}
