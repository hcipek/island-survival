import 'package:flame/components.dart';
import 'package:flame/events.dart';

import '../island_game.dart';

/// Invisible full-world component whose only job is to translate taps and
/// pointer movement into world coordinates, taking advantage of Flame's
/// automatic camera-aware event coordinates for components mounted inside a
/// [World]. Buildings and units sit at a higher priority so they get first
/// refusal on a tap (and consume it via [TapDownEvent.continuePropagation])
/// before it reaches here.
class WorldInputLayer extends PositionComponent
    with
        TapCallbacks,
        PointerMoveCallbacks,
        DragCallbacks,
        ScaleCallbacks,
        HasGameReference<IslandGame> {
  final void Function(Vector2 worldPosition) onTapAt;
  final void Function(Vector2 worldPosition) onHoverAt;

  WorldInputLayer({required this.onTapAt, required this.onHoverAt})
      : super(
          anchor: Anchor.topLeft,
          position: Vector2.all(-50000),
          size: Vector2.all(100000),
          priority: -100,
        );

  @override
  void onTapDown(TapDownEvent event) {
    super.onTapDown(event);
    onTapAt(position + event.localPosition);
  }

  @override
  void onPointerMove(PointerMoveEvent event) {
    super.onPointerMove(event);
    onHoverAt(position + event.localPosition);
  }

  // Single-pointer (mouse or one-finger touch) panning goes through the
  // drag path - Flame's combined drag/scale recognizer only treats
  // multi-pointer gestures as "scale".
  @override
  void onDragUpdate(DragUpdateEvent event) {
    super.onDragUpdate(event);
    game.panCameraBy(event.localDelta);
  }

  // Two-finger pinch goes through the scale path, which also reports a
  // focal-point delta so panning-while-pinching still works.
  @override
  void onScaleStart(ScaleStartEvent event) {
    super.onScaleStart(event);
    game.handleScaleStart();
  }

  @override
  void onScaleUpdate(ScaleUpdateEvent event) {
    super.onScaleUpdate(event);
    game.handleScaleUpdate(event.scale, event.focalPointDelta);
  }
}
