import 'dart:math';

import 'package:flame/components.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'island_game.dart';

/// A small circular overview of the whole world: islands, buildings, boats,
/// players and the area currently on screen. Tapping or dragging on it moves
/// the camera to that spot.
class Minimap extends StatefulWidget {
  final IslandGame game;
  final double size;

  const Minimap({super.key, required this.game, this.size = 160});

  @override
  State<Minimap> createState() => _MinimapState();
}

class _MinimapState extends State<Minimap> with SingleTickerProviderStateMixin {
  // Bumped every frame so only the painter repaints, instead of rebuilding
  // the whole widget tree at 60fps.
  final ValueNotifier<int> _frame = ValueNotifier(0);
  late final Ticker _ticker;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker((_) => _frame.value++)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _frame.dispose();
    super.dispose();
  }

  void _lookAtLocal(Offset localPosition) {
    final extent = _MinimapPainter.mapExtent(widget.game);
    final scale = widget.size / (extent * 2);
    final center = widget.size / 2;
    widget.game.lookAt(Vector2(
      (localPosition.dx - center) / scale,
      (localPosition.dy - center) / scale,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (details) => _lookAtLocal(details.localPosition),
      onPanUpdate: (details) => _lookAtLocal(details.localPosition),
      child: Container(
        width: widget.size,
        height: widget.size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: Colors.white70, width: 2),
          boxShadow: const [BoxShadow(color: Colors.black45, blurRadius: 6)],
        ),
        child: ClipOval(
          child: CustomPaint(
            painter: _MinimapPainter(game: widget.game, repaint: _frame),
          ),
        ),
      ),
    );
  }
}

class _MinimapPainter extends CustomPainter {
  final IslandGame game;

  _MinimapPainter({required this.game, required Listenable repaint})
      : super(repaint: repaint);

  /// World-space radius the minimap covers: the sea boundary, widened if
  /// any island happens to reach past it.
  static double mapExtent(IslandGame game) {
    var extent = game.worldSeaBoundary;
    for (final island in game.islands) {
      extent = max(extent, island.position.length + island.radius);
    }
    return extent;
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (!game.isLoaded) return;

    final extent = mapExtent(game);
    final scale = size.width / (extent * 2);
    final center = Offset(size.width / 2, size.height / 2);
    Offset toMap(Vector2 world) => center + Offset(world.x, world.y) * scale;

    canvas.drawCircle(center, size.width / 2, Paint()..color = const Color(0xFF4FA8D8));

    final sandPaint = Paint()..color = const Color(0xFFE8D6A0);
    final grassPaint = Paint()..color = const Color(0xFF7CB342);
    for (final island in game.islands) {
      final mapCenter = toMap(island.position);
      canvas.drawCircle(mapCenter, max(island.radius * scale, 2), sandPaint);
      canvas.drawCircle(mapCenter, max(island.grassRadius * scale, 1.5), grassPaint);
    }

    for (final port in game.ports) {
      _drawBuilding(canvas, toMap(port.position), port.ownerColor);
    }
    for (final lumberjack in game.lumberjacks) {
      _drawBuilding(canvas, toMap(lumberjack.position), lumberjack.ownerColor);
    }

    final boatPaint = Paint()..color = const Color(0xFF5D4037);
    for (final boat in game.boats) {
      canvas.drawCircle(toMap(boat.position), 2, boatPaint);
    }

    for (final remote in game.remotePlayers) {
      _drawPlayer(canvas, toMap(remote.position), remote.ownerColor, 3);
    }
    _drawPlayer(canvas, toMap(game.player.position), game.player.ownerColor, 4);

    final visible = game.visibleWorldRect;
    canvas.drawRect(
      Rect.fromPoints(
        toMap(Vector2(visible.left, visible.top)),
        toMap(Vector2(visible.right, visible.bottom)),
      ),
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );
  }

  void _drawBuilding(Canvas canvas, Offset at, Color ownerColor) {
    canvas.drawRect(Rect.fromCenter(center: at, width: 4, height: 4), Paint()..color = ownerColor);
  }

  void _drawPlayer(Canvas canvas, Offset at, Color color, double radius) {
    canvas.drawCircle(at, radius + 1.5, Paint()..color = Colors.white);
    canvas.drawCircle(at, radius, Paint()..color = color);
  }

  @override
  bool shouldRepaint(_MinimapPainter oldDelegate) => oldDelegate.game != game;
}
