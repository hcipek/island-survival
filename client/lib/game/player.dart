import 'dart:math';
import 'dart:ui';

import 'package:flame/components.dart';

import '../island_game.dart';

/// Moves a component along a list of waypoints at a fixed [speed]. Shared
/// by players and boats, so every client that's given the same path puts
/// them in the same place at the same time - which is what lets the server
/// send one "walk this path" message instead of a stream of positions.
mixin PathFollower on PositionComponent {
  double get speed;

  final List<Vector2> _waypoints = [];
  VoidCallback? _onArrived;

  /// Waypoints still ahead, e.g. to re-send after a reconnect.
  List<Vector2> get remainingPath => [for (final point in _waypoints) point.clone()];

  /// Follows [path] in order, calling [onArrived] once the last point is
  /// reached.
  void followPath(List<Vector2> path, {VoidCallback? onArrived}) {
    _waypoints
      ..clear()
      ..addAll(path.map((point) => point.clone()));
    _onArrived = onArrived;
  }

  /// Jumps to where [movement] started and follows it, skipping ahead by
  /// however long ago it began - so late joiners see it where it is now.
  void follow(MovementRecord movement) {
    position = movement.from;
    followPath(movement.path);
    advanceAlongPath(movement.elapsed.inMicroseconds / Duration.microsecondsPerSecond);
  }

  void stop() {
    _waypoints.clear();
    _onArrived = null;
  }

  /// Moves [seconds] worth of distance along the path, across as many
  /// waypoints as that covers.
  void advanceAlongPath(double seconds) {
    if (_waypoints.isEmpty) return;
    var budget = speed * seconds;
    while (_waypoints.isNotEmpty && budget > 0) {
      final target = _waypoints.first;
      final distance = position.distanceTo(target);
      if (distance <= budget) {
        position = target;
        budget -= distance;
        _waypoints.removeAt(0);
      } else {
        position += (target - position).normalized() * budget;
        budget = 0;
      }
    }
    if (_waypoints.isEmpty) {
      final callback = _onArrived;
      _onArrived = null;
      callback?.call();
    }
  }
}

class PlayerComponent extends PositionComponent with PathFollower {
  @override
  final double speed;

  /// The boat currently carrying the player, if any. While set, the player
  /// rides along with the boat instead of moving independently.
  BoatComponent? boardedBoat;

  final String username;
  final Color ownerColor;

  /// 0 to 1 while gathering, drawn as a ring above the player's head.
  double? gatherProgress;

  PlayerComponent({
    required double islandRadius,
    required this.username,
    required this.ownerColor,
  })  : speed = islandRadius * 0.5,
        super(
          anchor: Anchor.bottomCenter,
          position: Vector2.zero(),
          size: Vector2(islandRadius * 0.06, islandRadius * 0.12),
          priority: 10,
        );

  void moveTo(Vector2 worldTarget, {VoidCallback? onArrived}) {
    followPath([worldTarget], onArrived: onArrived);
  }

  @override
  void update(double dt) {
    super.update(dt);

    final boat = boardedBoat;
    if (boat != null) {
      stop();
      position = boat.position + Vector2(0, -boat.size.y * 0.15);
      return;
    }
    advanceAlongPath(dt);
  }

  @override
  void render(Canvas canvas) {
    final bodyPaint = Paint()..color = const Color(0xFF3F51B5);
    final headPaint = Paint()..color = const Color(0xFFFFCC80);

    final bodyRect = Rect.fromLTWH(size.x * 0.15, size.y * 0.35, size.x * 0.7, size.y * 0.65);
    canvas.drawRRect(
      RRect.fromRectAndRadius(bodyRect, Radius.circular(size.x * 0.2)),
      bodyPaint,
    );

    final headRadius = size.x * 0.32;
    final headCenter = Offset(size.x / 2, size.y * 0.28);
    canvas.drawCircle(headCenter, headRadius, headPaint);

    _renderNameTag(canvas);
    _renderGatherProgress(canvas);
  }

  void _renderGatherProgress(Canvas canvas) {
    final progress = gatherProgress;
    if (progress == null) return;
    final center = Offset(size.x / 2, -size.y * 0.75);
    final radius = size.x * 0.45;
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = const Color(0x66000000)
        ..style = PaintingStyle.stroke
        ..strokeWidth = size.x * 0.14,
    );
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      -pi / 2,
      2 * pi * progress,
      false,
      Paint()
        ..color = const Color(0xFFFFFFFF)
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeWidth = size.x * 0.14,
    );
  }

  void _renderNameTag(Canvas canvas) {
    const constraintWidth = 300.0;
    final fontSize = size.x * 0.9;

    final builder = ParagraphBuilder(
      ParagraphStyle(textAlign: TextAlign.center, fontWeight: FontWeight.bold),
    )
      ..pushStyle(TextStyle(
        color: ownerColor,
        fontSize: fontSize,
        fontWeight: FontWeight.bold,
        shadows: const [Shadow(color: Color(0xFF000000), blurRadius: 3, offset: Offset(1, 1))],
      ))
      ..addText(username);
    final paragraph = builder.build()
      ..layout(const ParagraphConstraints(width: constraintWidth));

    canvas.drawParagraph(
      paragraph,
      Offset(size.x / 2 - constraintWidth / 2, -size.y * 0.35 - paragraph.height),
    );
  }
}
