import 'dart:ui';

import 'package:flame/game.dart';

import 'island_game.dart';

// Turning what the server sends (over the world socket and the REST
// calls) into the game's own types, and back.

String buildingTypeToJson(BuildingType type) {
  switch (type) {
    case BuildingType.port:
      return 'PORT';
    case BuildingType.lumberjack:
      return 'LUMBERJACK';
  }
}

BuildingType buildingTypeFromJson(String value) {
  switch (value) {
    case 'PORT':
      return BuildingType.port;
    case 'LUMBERJACK':
      return BuildingType.lumberjack;
    default:
      throw Exception('Unknown building type: $value');
  }
}

Color colorFromHex(String hex) {
  final value = hex.replaceFirst('#', '');
  return Color(int.parse('FF$value', radix: 16));
}

/// How this player is doing in the current world. (What they carry is in
/// their [InventoryState].)
class PlayerState {
  static const double maxHunger = 100;

  /// 100 is full, 0 is starving.
  final double hunger;

  /// How fast [hunger] is dropping - zero while not in the world.
  final double hungerDrainPerSecond;

  PlayerState({required this.hunger, required this.hungerDrainPerSecond});

  bool get isStarving => hunger <= 0;
  bool get isFull => hunger >= maxHunger;

  factory PlayerState.fromJson(Map<String, dynamic> json) {
    return PlayerState(
      hunger: (json['hunger'] as num?)?.toDouble() ?? maxHunger,
      hungerDrainPerSecond: (json['hungerDrainPerSecond'] as num?)?.toDouble() ?? 0,
    );
  }
}

BuildingRecord buildingFromJson(Map<String, dynamic> json) {
  return BuildingRecord(
    id: json['id'] as int,
    type: buildingTypeFromJson(json['type'] as String),
    x: (json['x'] as num).toDouble(),
    y: (json['y'] as num).toDouble(),
    ownerId: json['ownerId'] as int,
    ownerColor: colorFromHex(json['ownerColor'] as String),
    storedWood: (json['storedWood'] as num?)?.toDouble() ?? 0,
    woodPerSecond: (json['woodPerSecond'] as num?)?.toDouble() ?? 0,
    woodCapacity: (json['woodCapacity'] as num?)?.toDouble() ?? 0,
  );
}

BoatRecord boatFromJson(Map<String, dynamic> json) {
  return BoatRecord(
    id: json['id'] as int,
    ownerId: json['ownerId'] as int,
    x: (json['x'] as num).toDouble(),
    y: (json['y'] as num).toDouble(),
    movement: movementFromJson(json['movement'] as Map<String, dynamic>?),
  );
}

RemotePlayerRecord playerFromJson(Map<String, dynamic> json) {
  return RemotePlayerRecord(
    userId: json['userId'] as int,
    username: json['username'] as String,
    color: colorFromHex(json['color'] as String),
    x: (json['x'] as num).toDouble(),
    y: (json['y'] as num).toDouble(),
    boatId: json['boatId'] as int?,
    movement: movementFromJson(json['movement'] as Map<String, dynamic>?),
  );
}

MovementRecord? movementFromJson(Map<String, dynamic>? json) {
  if (json == null) return null;
  return MovementRecord(
    from: pointFromJson(json['from'] as Map<String, dynamic>),
    path: (json['path'] as List<dynamic>)
        .map((point) => pointFromJson(point as Map<String, dynamic>))
        .toList(),
    elapsed: Duration(milliseconds: (json['elapsedMs'] as num?)?.toInt() ?? 0),
  );
}

ResourceNodeRecord nodeFromJson(Map<String, dynamic> json) {
  return ResourceNodeRecord(
    id: json['id'] as int,
    type: switch (json['type'] as String) {
      'TREE' => ResourceNodeType.tree,
      'BERRY_BUSH' => ResourceNodeType.berryBush,
      'STICKS' => ResourceNodeType.sticks,
      'STONES' => ResourceNodeType.stones,
      final other => throw Exception('Unknown resource node type: $other'),
    },
    x: (json['x'] as num).toDouble(),
    y: (json['y'] as num).toDouble(),
    regrowsIn: Duration(milliseconds: (json['regrowsInMs'] as num?)?.toInt() ?? 0),
  );
}

Vector2 pointFromJson(Map<String, dynamic> json) {
  return Vector2((json['x'] as num).toDouble(), (json['y'] as num).toDouble());
}

Map<String, double> pointToJson(Vector2 point) => {'x': point.x, 'y': point.y};

List<T> listFromJson<T>(Object? json, T Function(Map<String, dynamic>) parse) {
  return (json as List<dynamic>).map((item) => parse(item as Map<String, dynamic>)).toList();
}
