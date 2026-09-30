import 'dart:math';
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/events.dart';

import '../island_game.dart';

/// Draws a small pole-and-pennant above a building in its owner's color, so
/// it's clear at a glance who built what.
void _drawOwnerFlag(Canvas canvas, Vector2 size, Color ownerColor) {
  final poleTop = Offset(size.x * 0.5, -size.y * 0.28);
  final poleBottom = Offset(size.x * 0.5, size.y * 0.05);
  final polePaint = Paint()
    ..color = const Color(0xFF4E342E)
    ..strokeWidth = size.x * 0.03;
  canvas.drawLine(poleBottom, poleTop, polePaint);

  final flagPath = Path()
    ..moveTo(poleTop.dx, poleTop.dy)
    ..lineTo(poleTop.dx + size.x * 0.28, poleTop.dy + size.y * 0.09)
    ..lineTo(poleTop.dx, poleTop.dy + size.y * 0.18)
    ..close();
  canvas.drawPath(flagPath, Paint()..color = ownerColor);
}

class PortComponent extends PositionComponent
    with TapCallbacks, HasGameReference<IslandGame> {
  /// The backing row on the server, once the build call succeeds (or set
  /// directly for buildings loaded from the backend on startup).
  int? backendId;

  final int ownerId;
  final Color ownerColor;

  PortComponent({
    required Vector2 worldPosition,
    required double islandRadius,
    required this.ownerId,
    required this.ownerColor,
  }) : super(
          anchor: Anchor.center,
          position: worldPosition,
          size: Vector2.all(islandRadius * 0.22),
          priority: 5,
        );

  @override
  void render(Canvas canvas) {
    final basePaint = Paint()..color = const Color(0xFF8D6E63);
    final roofPaint = Paint()..color = const Color(0xFFD84315);

    final baseRect = Rect.fromLTWH(size.x * 0.15, size.y * 0.35, size.x * 0.7, size.y * 0.55);
    canvas.drawRect(baseRect, basePaint);

    final roofPath = Path()
      ..moveTo(size.x * 0.08, size.y * 0.35)
      ..lineTo(size.x * 0.5, size.y * 0.02)
      ..lineTo(size.x * 0.92, size.y * 0.35)
      ..close();
    canvas.drawPath(roofPath, roofPaint);

    _drawOwnerFlag(canvas, size, ownerColor);
  }

  @override
  void onTapDown(TapDownEvent event) {
    super.onTapDown(event);
    event.continuePropagation = false;
    game.onPortTapped?.call(this);
  }
}

class LumberjackComponent extends PositionComponent
    with TapCallbacks, HasGameReference<IslandGame> {
  /// The backing row on the server, once the build call succeeds (or set
  /// directly for buildings loaded from the backend on startup).
  int? backendId;

  final int ownerId;
  final Color ownerColor;

  double _storedWood = 0;
  double _woodPerSecond = 0;
  double _woodCapacity = 0;
  DateTime _storedAt = DateTime.now();

  void applyStorage(BuildingRecord record) {
    _storedWood = record.storedWood;
    _woodPerSecond = record.woodPerSecond;
    _woodCapacity = record.woodCapacity;
    _storedAt = DateTime.now();
  }

  /// The wood it's holding right now, counting up from the last update.
  double get storedWood {
    final seconds = DateTime.now().difference(_storedAt).inMilliseconds / 1000;
    return min(_woodCapacity, _storedWood + _woodPerSecond * seconds);
  }

  double get woodCapacity => _woodCapacity;

  LumberjackComponent({
    required Vector2 worldPosition,
    required double islandRadius,
    required this.ownerId,
    required this.ownerColor,
  }) : super(
          anchor: Anchor.center,
          position: worldPosition,
          size: Vector2.all(islandRadius * 0.2),
          priority: 5,
        );

  @override
  void render(Canvas canvas) {
    final cabinPaint = Paint()..color = const Color(0xFF5D4037);
    final roofPaint = Paint()..color = const Color(0xFF3E2723);
    final logPaint = Paint()..color = const Color(0xFFA1887F);
    final logEndPaint = Paint()..color = const Color(0xFFD7B899);

    final cabinRect = Rect.fromLTWH(size.x * 0.1, size.y * 0.4, size.x * 0.55, size.y * 0.5);
    canvas.drawRect(cabinRect, cabinPaint);

    final roofPath = Path()
      ..moveTo(size.x * 0.04, size.y * 0.4)
      ..lineTo(size.x * 0.37, size.y * 0.12)
      ..lineTo(size.x * 0.7, size.y * 0.4)
      ..close();
    canvas.drawPath(roofPath, roofPaint);

    // A small stack of cut logs beside the cabin.
    final logRadius = size.x * 0.09;
    final logCenters = [
      Offset(size.x * 0.78, size.y * 0.78),
      Offset(size.x * 0.93, size.y * 0.78),
      Offset(size.x * 0.855, size.y * 0.63),
    ];
    for (final center in logCenters) {
      canvas.drawCircle(center, logRadius, logPaint);
      canvas.drawCircle(center, logRadius * 0.55, logEndPaint);
    }

    if (game.isOwnedByMe(ownerId)) _renderReadyBadge(canvas);

    _drawOwnerFlag(canvas, size, ownerColor);
  }

  /// On your own lumberjack: how much wood is ready to collect.
  void _renderReadyBadge(Canvas canvas) {
    final ready = storedWood.floor();
    if (ready < 1) return;
    final full = ready >= _woodCapacity;
    final builder = ParagraphBuilder(ParagraphStyle(fontWeight: FontWeight.bold))
      ..pushStyle(TextStyle(color: const Color(0xFFFFFFFF), fontSize: size.y * 0.16, fontWeight: FontWeight.bold))
      ..addText(full ? '$ready full' : '$ready');
    final paragraph = builder.build()..layout(const ParagraphConstraints(width: double.infinity));
    final textWidth = paragraph.maxIntrinsicWidth;
    final badge = Rect.fromCenter(
      center: Offset(size.x * 0.4, -size.y * 0.12),
      width: textWidth + size.x * 0.16,
      height: paragraph.height + size.y * 0.04,
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(badge, Radius.circular(badge.height / 2)),
      Paint()..color = full ? const Color(0xFFE65100) : const Color(0xFF5D4037),
    );
    canvas.drawParagraph(paragraph, Offset(badge.left + size.x * 0.08, badge.top + size.y * 0.02));
  }

  @override
  void onTapDown(TapDownEvent event) {
    super.onTapDown(event);
    event.continuePropagation = false;
    game.onLumberjackTapped?.call(this);
  }
}
