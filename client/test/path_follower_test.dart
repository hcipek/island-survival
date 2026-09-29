import 'package:client/island_game.dart';
import 'package:flame/components.dart';
import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';

/// Other players are drawn by simulating the paths they send, so these check
/// that a path always plays out the same way - however it's stepped through.
void main() {
  // islandRadius 600 gives players a speed of 300 units per second.
  PlayerComponent newPlayer() => PlayerComponent(
        islandRadius: 600,
        username: 'test',
        ownerColor: const Color(0xFF000000),
      );

  test('walks at a fixed speed toward the target', () {
    final player = newPlayer()..position = Vector2.zero();
    player.moveTo(Vector2(600, 0));

    player.update(1);

    expect(player.position.x, closeTo(300, 0.001));
    expect(player.position.y, closeTo(0, 0.001));
  });

  test('crosses several waypoints in one step if it has the distance', () {
    final player = newPlayer()..position = Vector2.zero();
    player.followPath([Vector2(100, 0), Vector2(100, 100), Vector2(0, 100)]);

    // 1 second = 300 units: exactly to the last waypoint.
    player.update(1);

    expect(player.position.x, closeTo(0, 0.001));
    expect(player.position.y, closeTo(100, 0.001));
    expect(player.remainingPath, isEmpty);
  });

  test('many small frames end up where one big step does', () {
    final path = [Vector2(250, 0), Vector2(250, 400), Vector2(-100, 400)];
    final smooth = newPlayer()..position = Vector2.zero();
    final jumpy = newPlayer()..position = Vector2.zero();
    smooth.followPath(path);
    jumpy.followPath(path);

    for (var i = 0; i < 150; i++) {
      smooth.update(1 / 60);
    }
    jumpy.update(2.5);

    expect(smooth.position.x, closeTo(jumpy.position.x, 0.001));
    expect(smooth.position.y, closeTo(jumpy.position.y, 0.001));
  });

  test('a late joiner fast-forwards to where the movement is now', () {
    final player = newPlayer();

    player.follow(MovementRecord(
      from: Vector2(1000, 1000),
      path: [Vector2(1600, 1000)],
      elapsed: const Duration(seconds: 1),
    ));

    expect(player.position.x, closeTo(1300, 0.001));
    expect(player.remainingPath, hasLength(1));
  });

  test('fast-forwarding past the end stops at the destination', () {
    final player = newPlayer();
    var arrived = false;

    player.followPath([Vector2(50, 0)], onArrived: () => arrived = true);
    player.advanceAlongPath(10);

    expect(player.position, Vector2(50, 0));
    expect(arrived, isTrue);
  });

  test('a player aboard a boat rides along with it', () {
    final boat = BoatComponent(startPosition: Vector2(0, 0), islandRadius: 600, ownerId: 1);
    final rider = newPlayer()..boardedBoat = boat;
    boat.moveAlongPath([Vector2(3600, 0)]);

    boat.update(1);
    rider.update(1);

    // Boats move at 360 units per second; the rider sits just above the deck.
    expect(boat.position.x, closeTo(360, 0.001));
    expect(rider.position.x, closeTo(boat.position.x, 0.001));
  });
}
