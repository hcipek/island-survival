import 'dart:ui';

import 'package:flame/components.dart';

class IslandComponent extends PositionComponent {
  static const double _sandBandFraction = 0.08;

  IslandComponent({required Vector2 center, required double islandRadius})
      : super(
          anchor: Anchor.center,
          position: center,
          size: Vector2.all(islandRadius * 2),
        );

  double get radius => size.x / 2;

  /// Matches the deflate amount used in [render] for the grass oval, so the
  /// visible sand ring and the buildable area are always the same region.
  double get grassRadius => radius - size.x * _sandBandFraction;

  @override
  void render(Canvas canvas) {
    final sandPaint = Paint()..color = const Color(0xFFE8D6A0);
    final grassPaint = Paint()..color = const Color(0xFF7CB342);
    final outer = Rect.fromLTWH(0, 0, size.x, size.y);
    canvas.drawOval(outer, sandPaint);
    final inner = outer.deflate(size.x * _sandBandFraction);
    canvas.drawOval(inner, grassPaint);
  }

  /// Clamps [worldPosition] so it stays within this island's sand oval.
  Vector2 clampToIsland(Vector2 worldPosition) {
    final offset = worldPosition - position;
    if (offset.length <= radius) {
      return worldPosition;
    }
    return position + offset.normalized() * radius;
  }

  /// Whether [worldPosition] falls on the visible sand ring - the only place
  /// a port is allowed to be built.
  bool isOnSand(Vector2 worldPosition) {
    final distance = (worldPosition - position).length;
    return distance >= grassRadius && distance <= radius;
  }

  /// Whether [worldPosition] falls on the inland grass - the only place a
  /// lumberjack is allowed to be built.
  bool isOnGrass(Vector2 worldPosition) {
    final distance = (worldPosition - position).length;
    return distance <= grassRadius;
  }
}

/// Debug-only outline marking the edge of the sailable sea, hidden by
/// default and toggled from the Flutter UI.
class MapBoundaryComponent extends PositionComponent {
  bool visible = false;

  MapBoundaryComponent({required double boundaryRadius})
      : super(
          anchor: Anchor.center,
          position: Vector2.zero(),
          size: Vector2.all(boundaryRadius * 2),
        );

  @override
  void render(Canvas canvas) {
    if (!visible) return;
    final paint = Paint()
      ..color = const Color(0xFFFF00FF)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 24;
    canvas.drawOval(Rect.fromLTWH(0, 0, size.x, size.y), paint);
  }
}
