import 'dart:convert';
import 'dart:ui';

import 'package:http/http.dart' as http;

import 'island_game.dart';
import 'protocol.dart';

class UserData {
  final int id;
  final String name;
  final Color color;

  UserData({required this.id, required this.name, required this.color});

  factory UserData.fromJson(Map<String, dynamic> json) {
    return UserData(
      id: json['id'] as int,
      name: json['name'] as String,
      color: colorFromHex(json['color'] as String),
    );
  }
}

class WorldData {
  final int id;
  final String name;

  WorldData({required this.id, required this.name});

  factory WorldData.fromJson(Map<String, dynamic> json) {
    return WorldData(id: json['id'] as int, name: json['name'] as String);
  }
}

class WorldIslandData {
  final double centerX;
  final double centerY;
  final double radius;
  final bool isHome;

  WorldIslandData({
    required this.centerX,
    required this.centerY,
    required this.radius,
    required this.isHome,
  });

  factory WorldIslandData.fromJson(Map<String, dynamic> json) {
    return WorldIslandData(
      centerX: (json['centerX'] as num).toDouble(),
      centerY: (json['centerY'] as num).toDouble(),
      radius: (json['radius'] as num).toDouble(),
      isHome: json['isHome'] as bool,
    );
  }
}

// Parsers for what the server sends over the world socket (and the REST
// build calls), straight into the game's record types.

class IslandApi {
  // Derived from whatever host served this page, so the same build works
  // both from localhost and from another device on the LAN (e.g. a phone
  // opening http://<mac-lan-ip>:5173) without needing a hardcoded address.
  static final _baseUrl = 'http://${Uri.base.host}:8080';

  /// The live connection for [userId] in [worldId] - see [LiveConnection].
  Uri liveUrl(int worldId, int userId) =>
      Uri.parse('ws://${Uri.base.host}:8080/ws/worlds/$worldId?userId=$userId');

  Future<List<UserData>> fetchUsers() async {
    final response = await http.get(Uri.parse('$_baseUrl/api/users'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load users: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items.map((item) => UserData.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<UserData> createUser(String name) async {
    final response = await http.post(
      Uri.parse('$_baseUrl/api/users'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'name': name}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
    return UserData.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
  }

  Future<List<WorldData>> fetchWorlds() async {
    final response = await http.get(Uri.parse('$_baseUrl/api/worlds'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load worlds: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items.map((item) => WorldData.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<WorldData> createWorld(String name) async {
    final response = await http.post(
      Uri.parse('$_baseUrl/api/worlds'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'name': name}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
    return WorldData.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
  }

  Future<void> deleteWorld(int worldId) async {
    final response = await http.delete(Uri.parse('$_baseUrl/api/worlds/$worldId'));
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
  }

  Future<List<WorldIslandData>> fetchWorldIslands(int worldId) async {
    final response = await http.get(Uri.parse('$_baseUrl/api/worlds/$worldId/islands'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load world: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items
        .map((item) => WorldIslandData.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<int> createBuilding(
    int worldId,
    BuildingType type,
    double x,
    double y,
    int ownerId,
  ) async {
    final response = await http.post(
      Uri.parse('$_baseUrl/api/worlds/$worldId/buildings'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'type': buildingTypeToJson(type),
        'x': x,
        'y': y,
        'ownerId': ownerId,
      }),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    return body['id'] as int;
  }

  Future<void> deleteBuilding(int worldId, int id, int userId) async {
    final response = await http.delete(
      Uri.parse('$_baseUrl/api/worlds/$worldId/buildings/$id?userId=$userId'),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
  }

  Future<BoatRecord> createBoat(int worldId, int portId, int ownerId) async {
    final response = await http.post(
      Uri.parse('$_baseUrl/api/worlds/$worldId/boats'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'portId': portId, 'ownerId': ownerId}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
    return boatFromJson(jsonDecode(response.body) as Map<String, dynamic>);
  }

  String _errorMessage(http.Response response) {
    try {
      final body = jsonDecode(response.body) as Map<String, dynamic>;
      return body['message'] as String? ?? 'Request failed (${response.statusCode})';
    } catch (_) {
      return 'Request failed (${response.statusCode})';
    }
  }
}
