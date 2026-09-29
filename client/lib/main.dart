import 'dart:async';
import 'dart:convert';

import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'island_game.dart';
import 'minimap.dart';

String _buildingTypeToJson(BuildingType type) {
  switch (type) {
    case BuildingType.port:
      return 'PORT';
    case BuildingType.lumberjack:
      return 'LUMBERJACK';
  }
}

BuildingType _buildingTypeFromJson(String value) {
  switch (value) {
    case 'PORT':
      return BuildingType.port;
    case 'LUMBERJACK':
      return BuildingType.lumberjack;
    default:
      throw Exception('Unknown building type: $value');
  }
}

Color _colorFromHex(String hex) {
  final value = hex.replaceFirst('#', '');
  return Color(int.parse('FF$value', radix: 16));
}

void main() {
  runApp(const IslandApp());
}

class IslandApp extends StatelessWidget {
  const IslandApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Island',
      theme: ThemeData(colorSchemeSeed: Colors.green, useMaterial3: true),
      home: const WelcomeScreen(),
    );
  }
}

class UserData {
  final int id;
  final String name;
  final Color color;

  UserData({required this.id, required this.name, required this.color});

  factory UserData.fromJson(Map<String, dynamic> json) {
    return UserData(
      id: json['id'] as int,
      name: json['name'] as String,
      color: _colorFromHex(json['color'] as String),
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

class IslandState {
  final double wood;
  final double woodPerSecond;

  IslandState({required this.wood, required this.woodPerSecond});

  factory IslandState.fromJson(Map<String, dynamic> json) {
    return IslandState(
      wood: (json['wood'] as num).toDouble(),
      woodPerSecond: (json['woodPerSecond'] as num).toDouble(),
    );
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

class WorldBuildingData {
  final int id;
  final BuildingType type;
  final double x;
  final double y;
  final int ownerId;
  final String ownerName;
  final Color ownerColor;

  WorldBuildingData({
    required this.id,
    required this.type,
    required this.x,
    required this.y,
    required this.ownerId,
    required this.ownerName,
    required this.ownerColor,
  });

  factory WorldBuildingData.fromJson(Map<String, dynamic> json) {
    return WorldBuildingData(
      id: json['id'] as int,
      type: _buildingTypeFromJson(json['type'] as String),
      x: (json['x'] as num).toDouble(),
      y: (json['y'] as num).toDouble(),
      ownerId: json['ownerId'] as int,
      ownerName: json['ownerName'] as String,
      ownerColor: _colorFromHex(json['ownerColor'] as String),
    );
  }
}

class BoatData {
  final int id;
  final int ownerId;
  final double x;
  final double y;

  BoatData({required this.id, required this.ownerId, required this.x, required this.y});

  factory BoatData.fromJson(Map<String, dynamic> json) {
    return BoatData(
      id: json['id'] as int,
      ownerId: json['ownerId'] as int,
      x: (json['x'] as num).toDouble(),
      y: (json['y'] as num).toDouble(),
    );
  }

  BoatRecord toRecord() => BoatRecord(id: id, ownerId: ownerId, x: x, y: y);
}

class RemotePlayerData {
  final int userId;
  final String username;
  final Color color;
  final double x;
  final double y;

  RemotePlayerData({
    required this.userId,
    required this.username,
    required this.color,
    required this.x,
    required this.y,
  });

  factory RemotePlayerData.fromJson(Map<String, dynamic> json) {
    return RemotePlayerData(
      userId: json['userId'] as int,
      username: json['username'] as String,
      color: _colorFromHex(json['color'] as String),
      x: (json['x'] as num).toDouble(),
      y: (json['y'] as num).toDouble(),
    );
  }
}

class IslandApi {
  // Derived from whatever host served this page, so the same build works
  // both from localhost and from another device on the LAN (e.g. a phone
  // opening http://<mac-lan-ip>:5173) without needing a hardcoded address.
  static final _baseUrl = 'http://${Uri.base.host}:8080';

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

  Future<List<WorldBuildingData>> fetchBuildings(int worldId) async {
    final response = await http.get(Uri.parse('$_baseUrl/api/worlds/$worldId/buildings'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load buildings: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items
        .map((item) => WorldBuildingData.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<IslandState> fetchIsland(int worldId) async {
    final response = await http.get(Uri.parse('$_baseUrl/api/worlds/$worldId/state'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load island: ${response.statusCode}');
    }
    return IslandState.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
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
        'type': _buildingTypeToJson(type),
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

  Future<List<BoatData>> fetchBoats(int worldId) async {
    final response = await http.get(Uri.parse('$_baseUrl/api/worlds/$worldId/boats'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load boats: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items.map((item) => BoatData.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<BoatData> createBoat(int worldId, int portId, int ownerId) async {
    final response = await http.post(
      Uri.parse('$_baseUrl/api/worlds/$worldId/boats'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'portId': portId, 'ownerId': ownerId}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
    return BoatData.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
  }

  Future<void> updateBoatPosition(int worldId, int boatId, int userId, double x, double y) async {
    final response = await http.put(
      Uri.parse('$_baseUrl/api/worlds/$worldId/boats/$boatId/position?userId=$userId'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'x': x, 'y': y}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
  }

  Future<void> updatePlayerPosition(int worldId, int userId, double x, double y) async {
    final response = await http.put(
      Uri.parse('$_baseUrl/api/worlds/$worldId/players/$userId/position'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'x': x, 'y': y}),
    );
    if (response.statusCode != 200) {
      throw Exception(_errorMessage(response));
    }
  }

  Future<List<RemotePlayerData>> fetchPlayers(int worldId, int excludeUserId) async {
    final response = await http.get(
      Uri.parse('$_baseUrl/api/worlds/$worldId/players?excludeUserId=$excludeUserId'),
    );
    if (response.statusCode != 200) {
      throw Exception('Failed to load players: ${response.statusCode}');
    }
    final items = jsonDecode(response.body) as List<dynamic>;
    return items
        .map((item) => RemotePlayerData.fromJson(item as Map<String, dynamic>))
        .toList();
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

class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  final IslandApi _api = IslandApi();
  final TextEditingController _newUserController = TextEditingController();
  final TextEditingController _newWorldController = TextEditingController();

  List<UserData>? _users;
  List<WorldData>? _worlds;
  String? _error;

  UserData? _selectedUser;
  WorldData? _selectedWorld;

  bool _creatingUser = false;
  bool _creatingWorld = false;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _newUserController.dispose();
    _newWorldController.dispose();
    super.dispose();
  }

  Future<void> _loadAll() async {
    try {
      final users = await _api.fetchUsers();
      final worlds = await _api.fetchWorlds();
      setState(() {
        _users = users;
        _worlds = worlds;
        _error = null;
      });
    } catch (e) {
      setState(() => _error = 'Could not reach backend: $e');
    }
  }

  Future<void> _createUser() async {
    final name = _newUserController.text.trim();
    if (name.isEmpty) return;
    setState(() => _creatingUser = true);
    try {
      final user = await _api.createUser(name);
      _newUserController.clear();
      setState(() {
        _users = [...?_users, user];
        _selectedUser = user;
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not create user: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _creatingUser = false);
    }
  }

  Future<void> _createWorld() async {
    final name = _newWorldController.text.trim();
    if (name.isEmpty) return;
    setState(() => _creatingWorld = true);
    try {
      final world = await _api.createWorld(name);
      _newWorldController.clear();
      setState(() {
        _worlds = [...?_worlds, world];
        _selectedWorld = world;
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not create world: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _creatingWorld = false);
    }
  }

  Future<void> _confirmDeleteWorld(WorldData world) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Delete world?'),
        content: Text(
          'This permanently deletes "${world.name}" and everything built there. This cannot be undone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Delete', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await _api.deleteWorld(world.id);
      setState(() {
        _worlds = _worlds?.where((w) => w.id != world.id).toList();
        if (_selectedWorld?.id == world.id) {
          _selectedWorld = null;
        }
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not delete world: $e')),
        );
      }
    }
  }

  void _enter() {
    final user = _selectedUser;
    final world = _selectedWorld;
    if (user == null || world == null) return;
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => IslandScreen(
          userId: user.id,
          username: user.name,
          userColor: user.color,
          worldId: world.id,
          worldName: world.name,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Welcome')),
      body: _users == null || _worlds == null
          ? Center(
              child: _error != null
                  ? Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(_error!, textAlign: TextAlign.center),
                    )
                  : const CircularProgressIndicator(),
            )
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _PickerSection<UserData>(
                        title: 'Who are you?',
                        items: _users!,
                        selected: _selectedUser,
                        labelOf: (u) => u.name,
                        colorOf: (u) => u.color,
                        onSelect: (u) => setState(() => _selectedUser = u),
                        newItemController: _newUserController,
                        newItemHint: 'New username',
                        creating: _creatingUser,
                        onCreate: _createUser,
                      ),
                      const SizedBox(height: 24),
                      _PickerSection<WorldData>(
                        title: 'Which world?',
                        items: _worlds!,
                        selected: _selectedWorld,
                        labelOf: (w) => w.name,
                        onSelect: (w) => setState(() => _selectedWorld = w),
                        onDelete: _confirmDeleteWorld,
                        newItemController: _newWorldController,
                        newItemHint: 'New world name',
                        creating: _creatingWorld,
                        onCreate: _createWorld,
                      ),
                      const SizedBox(height: 24),
                      FilledButton(
                        onPressed:
                            _selectedUser != null && _selectedWorld != null ? _enter : null,
                        child: const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Text('Enter'),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }
}

class _PickerSection<T> extends StatelessWidget {
  final String title;
  final List<T> items;
  final T? selected;
  final String Function(T) labelOf;
  final ValueChanged<T> onSelect;
  final void Function(T)? onDelete;
  final Color Function(T)? colorOf;
  final TextEditingController newItemController;
  final String newItemHint;
  final bool creating;
  final VoidCallback onCreate;

  const _PickerSection({
    required this.title,
    required this.items,
    required this.selected,
    required this.labelOf,
    required this.onSelect,
    this.onDelete,
    this.colorOf,
    required this.newItemController,
    required this.newItemHint,
    required this.creating,
    required this.onCreate,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: Text('None yet - create one below.'),
              )
            else
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: items.map((item) {
                  final isSelected = selected == item;
                  return InputChip(
                    avatar: colorOf != null
                        ? CircleAvatar(backgroundColor: colorOf!(item))
                        : null,
                    label: Text(labelOf(item)),
                    selected: isSelected,
                    onPressed: () => onSelect(item),
                    onDeleted: onDelete != null ? () => onDelete!(item) : null,
                    deleteIcon: onDelete != null
                        ? const Icon(Icons.delete_outline, size: 18)
                        : null,
                  );
                }).toList(),
              ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: newItemController,
                    decoration: InputDecoration(
                      hintText: newItemHint,
                      isDense: true,
                      border: const OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => onCreate(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: creating ? null : onCreate,
                  icon: creating
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.add),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class IslandScreen extends StatefulWidget {
  final int userId;
  final String username;
  final Color userColor;
  final int worldId;
  final String worldName;

  const IslandScreen({
    super.key,
    required this.userId,
    required this.username,
    required this.userColor,
    required this.worldId,
    required this.worldName,
  });

  @override
  State<IslandScreen> createState() => _IslandScreenState();
}

class _IslandScreenState extends State<IslandScreen> {
  final IslandApi _api = IslandApi();
  IslandGame? _game;
  Timer? _pollTimer;
  Timer? _coordTimer;
  IslandState? _island;
  String? _error;
  bool _showMapBorder = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final List<WorldIslandData> layout;
    final List<WorldBuildingData> buildings;
    final List<BoatData> boats;
    try {
      layout = await _api.fetchWorldIslands(widget.worldId);
      buildings = await _api.fetchBuildings(widget.worldId);
      boats = await _api.fetchBoats(widget.worldId);
    } catch (e) {
      setState(() => _error = 'Could not reach backend: $e');
      return;
    }

    final game = IslandGame(
      islandLayout: layout
          .map((island) => IslandLayout(
                centerX: island.centerX,
                centerY: island.centerY,
                radius: island.radius,
                isHome: island.isHome,
              ))
          .toList(),
      existingBuildings: buildings
          .map((b) => BuildingRecord(
                id: b.id,
                type: b.type,
                x: b.x,
                y: b.y,
                ownerId: b.ownerId,
                ownerColor: b.ownerColor,
              ))
          .toList(),
      existingBoats: boats.map((b) => b.toRecord()).toList(),
      userId: widget.userId,
      username: widget.username,
      playerColor: widget.userColor,
    )
      ..onPortTapped = _showPortMenu
      ..onLumberjackTapped = _showLumberjackMenu
      ..onPortBuildRequested = _buildPort
      ..onPortDestroyRequested = _destroyPortBackend
      ..onLumberjackBuildRequested = _buildLumberjack
      ..onLumberjackDestroyRequested = _destroyLumberjackBackend
      ..onBoatBuildRequested = _buildBoat
      ..onNotice = _showNotice
      ..onPlacementStateChanged = () => setState(() {});

    setState(() => _game = game);

    _refresh();
    // Kept fairly tight since this tick is also what drives other players'
    // and buildings' visibility - combined with the movement interpolation
    // in IslandGame.syncRemotePlayers, this is what makes other players'
    // motion look smooth rather than a teleport every couple of seconds.
    _pollTimer = Timer.periodic(const Duration(milliseconds: 800), (_) => _refresh());
    // Player position is purely local Flame state (not from the backend), so
    // it needs its own faster-ticking refresh to feel live while moving.
    _coordTimer = Timer.periodic(const Duration(milliseconds: 200), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _coordTimer?.cancel();
    super.dispose();
  }

  bool _refreshing = false;

  Future<void> _refresh() async {
    // Guards against overlapping ticks if a request happens to run long -
    // otherwise two in-flight polls could resolve out of order.
    if (_refreshing) return;
    _refreshing = true;
    try {
      await _refreshOnce();
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _refreshOnce() async {
    try {
      final island = await _api.fetchIsland(widget.worldId);
      setState(() {
        _island = island;
        _error = null;
      });
    } catch (e) {
      setState(() {
        _error = 'Could not reach backend: $e';
      });
      return;
    }

    final game = _game;
    if (game == null) return;

    // Push our own position and pull everyone else's, plus anything built
    // since the last tick - best-effort, since a single missed tick just
    // means a couple of seconds of staleness rather than a broken game.
    unawaited(
      _api
          .updatePlayerPosition(
            widget.worldId,
            widget.userId,
            game.player.position.x,
            game.player.position.y,
          )
          .catchError((_) {}),
    );

    try {
      final buildings = await _api.fetchBuildings(widget.worldId);
      await game.syncBuildings(
        buildings
            .map((b) => BuildingRecord(
                  id: b.id,
                  type: b.type,
                  x: b.x,
                  y: b.y,
                  ownerId: b.ownerId,
                  ownerColor: b.ownerColor,
                ))
            .toList(),
      );
    } catch (_) {
      // Best-effort - a stale building list for one tick isn't worth
      // surfacing an error over.
    }

    _pushOwnBoatPositions(game);

    try {
      final boats = await _api.fetchBoats(widget.worldId);
      await game.syncBoats(boats.map((b) => b.toRecord()).toList());
    } catch (_) {
      // Best-effort, same reasoning as above.
    }

    try {
      final players = await _api.fetchPlayers(widget.worldId, widget.userId);
      await game.syncRemotePlayers(
        players
            .map((p) => RemotePlayerRecord(
                  userId: p.userId,
                  username: p.username,
                  color: p.color,
                  x: p.x,
                  y: p.y,
                ))
            .toList(),
      );
    } catch (_) {
      // Best-effort, same reasoning as above.
    }
  }

  void _showBuildMenu() {
    final game = _game!;
    final messenger = ScaffoldMessenger.of(context);
    showModalBottomSheet(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.holiday_village),
              title: const Text('Port'),
              subtitle: const Text('Lets you build boats. Placed near the coastline.'),
              onTap: () {
                Navigator.pop(sheetContext);
                game.startPlacing(BuildingType.port);
                messenger.showSnackBar(
                  const SnackBar(
                    content: Text("Tap near the island's edge to place your port"),
                  ),
                );
              },
            ),
            ListTile(
              leading: const Icon(Icons.forest),
              title: const Text('Lumberjack'),
              subtitle: Text(
                game.lumberjacks.isEmpty
                    ? 'Produces wood over time. Costs 20 wood. Placed inland.'
                    : 'Already built (limit 1 for now)',
              ),
              enabled: game.lumberjacks.isEmpty,
              onTap: game.lumberjacks.isEmpty
                  ? () {
                      Navigator.pop(sheetContext);
                      game.startPlacing(BuildingType.lumberjack);
                      messenger.showSnackBar(
                        const SnackBar(
                          content: Text('Tap the grass inland to place your lumberjack'),
                        ),
                      );
                    }
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  /// Where each of our boats was when we last told the backend, so only
  /// boats that actually moved get sent.
  final Map<int, Vector2> _lastSentBoatPositions = {};

  void _pushOwnBoatPositions(IslandGame game) {
    for (final boat in game.ownBoats) {
      final id = boat.backendId!;
      final lastSent = _lastSentBoatPositions[id];
      if (lastSent != null && lastSent.distanceTo(boat.position) < 1) continue;
      _lastSentBoatPositions[id] = boat.position.clone();
      unawaited(
        _api
            .updateBoatPosition(
              widget.worldId,
              id,
              widget.userId,
              boat.position.x,
              boat.position.y,
            )
            // Forget it so the next tick retries.
            .catchError((_) {
              _lastSentBoatPositions.remove(id);
            }),
      );
    }
  }

  Future<BoatRecord?> _buildBoat(int portId) async {
    try {
      final boat = await _api.createBoat(widget.worldId, portId, widget.userId);
      await _refresh(); // wood was just spent - reflect it immediately
      return boat.toRecord();
    } catch (e) {
      _showNotice('Could not build boat: $e');
      return null;
    }
  }

  void _showNotice(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<int?> _buildPort(Vector2 position) async {
    try {
      return await _api.createBuilding(
        widget.worldId,
        BuildingType.port,
        position.x,
        position.y,
        widget.userId,
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not build port: $e')),
        );
      }
      return null;
    }
  }

  Future<bool> _destroyPortBackend(int id) async {
    try {
      await _api.deleteBuilding(widget.worldId, id, widget.userId);
      return true;
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not remove port: $e')),
        );
      }
      return false;
    }
  }

  Future<int?> _buildLumberjack(Vector2 position) async {
    try {
      final id = await _api.createBuilding(
        widget.worldId,
        BuildingType.lumberjack,
        position.x,
        position.y,
        widget.userId,
      );
      await _refresh(); // wood was just spent - reflect it immediately
      return id;
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not build lumberjack: $e')),
        );
      }
      return null;
    }
  }

  Future<bool> _destroyLumberjackBackend(int id) async {
    try {
      await _api.deleteBuilding(widget.worldId, id, widget.userId);
      await _refresh(); // production rate just changed - reflect it immediately
      return true;
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not remove lumberjack: $e')),
        );
      }
      return false;
    }
  }

  void _showLumberjackMenu(LumberjackComponent lumberjack) {
    showModalBottomSheet(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: _game!.isOwnedByMe(lumberjack.ownerId)
            ? ListTile(
                leading: const Icon(Icons.delete_outline, color: Colors.red),
                title: const Text('Destroy'),
                subtitle: const Text('Remove this lumberjack (no refund)'),
                onTap: () {
                  Navigator.pop(sheetContext);
                  _game!.destroyLumberjack(lumberjack);
                },
              )
            : const _NotYoursTile(building: 'lumberjack'),
      ),
    );
  }

  void _showPortMenu(PortComponent port) {
    if (!_game!.isOwnedByMe(port.ownerId)) {
      showModalBottomSheet(
        context: context,
        builder: (_) => const SafeArea(child: _NotYoursTile(building: 'port')),
      );
      return;
    }
    // A port that was just placed has no backend id until the create call
    // returns, and the server needs that id to build a boat there.
    final isSaved = port.backendId != null;
    showModalBottomSheet(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.sailing),
              title: const Text('Boat'),
              subtitle: Text(
                isSaved ? 'Build a boat at this port. Costs 10 wood.' : 'Port is still being built...',
              ),
              enabled: isSaved,
              onTap: () {
                Navigator.pop(sheetContext);
                _game!.buildBoatFromPort(port);
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline, color: Colors.red),
              title: const Text('Destroy'),
              subtitle: const Text('Remove this port'),
              onTap: () {
                Navigator.pop(sheetContext);
                _game!.destroyPort(port);
              },
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final game = _game;
    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.worldName} - ${widget.username}'),
        actions: [
          if (game != null)
            Tooltip(
              message: _showMapBorder ? 'Hide map border (debug)' : 'Show map border (debug)',
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Checkbox(
                    value: _showMapBorder,
                    onChanged: game.isLoaded
                        ? (value) {
                            setState(() {
                              _showMapBorder = value ?? false;
                              game.setDebugBoundaryVisible(_showMapBorder);
                            });
                          }
                        : null,
                  ),
                  const Text('Map border'),
                  const SizedBox(width: 8),
                ],
              ),
            ),
        ],
      ),
      body: game == null
          ? Center(
              child: _error != null
                  ? Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(_error!, textAlign: TextAlign.center),
                    )
                  : const CircularProgressIndicator(),
            )
          : Stack(
              children: [
                Positioned.fill(child: GameWidget(game: game)),
                if (_error != null)
                  Positioned.fill(
                    child: Container(
                      color: Colors.black54,
                      child: Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            _error!,
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: Colors.white),
                          ),
                        ),
                      ),
                    ),
                  )
                else if (_island != null)
                  Positioned(
                    top: 16,
                    left: 16,
                    child: _WoodHud(island: _island!),
                  ),
                if (game.isLoaded)
                  Positioned(
                    top: 16,
                    right: 16,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        _CoordinatesHud(
                          x: game.player.position.x,
                          y: game.player.position.y,
                        ),
                        const SizedBox(height: 8),
                        Minimap(game: game),
                      ],
                    ),
                  ),
                Positioned(
                  bottom: 24,
                  right: 16,
                  child: _ZoomControls(game: game),
                ),
                Positioned(
                  bottom: 24,
                  left: 16,
                  child: FloatingActionButton.extended(
                    heroTag: 'build',
                    onPressed: game.isPlacingBuilding ? game.cancelPlacing : _showBuildMenu,
                    icon: Icon(game.isPlacingBuilding ? Icons.close : Icons.construction),
                    label: Text(game.isPlacingBuilding ? 'Cancel' : 'Build'),
                  ),
                ),
              ],
            ),
    );
  }
}

/// Shown instead of a building's actions when it belongs to someone else.
class _NotYoursTile extends StatelessWidget {
  final String building;

  const _NotYoursTile({required this.building});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.lock_outline),
      title: Text("Another player's $building"),
      subtitle: const Text('Only its owner can use or remove it'),
    );
  }
}

class _ZoomControls extends StatelessWidget {
  final IslandGame game;

  const _ZoomControls({required this.game});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        FloatingActionButton.small(
          heroTag: 'recenter',
          tooltip: 'Recenter on character',
          onPressed: game.recenterOnPlayer,
          child: const Icon(Icons.center_focus_strong),
        ),
        const SizedBox(height: 8),
        FloatingActionButton.small(
          heroTag: 'zoomIn',
          onPressed: () => game.zoomBy(1.25),
          child: const Icon(Icons.add),
        ),
        const SizedBox(height: 8),
        FloatingActionButton.small(
          heroTag: 'zoomOut',
          onPressed: () => game.zoomBy(1 / 1.25),
          child: const Icon(Icons.remove),
        ),
      ],
    );
  }
}

class _CoordinatesHud extends StatelessWidget {
  final double x;
  final double y;

  const _CoordinatesHud({required this.x, required this.y});

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Colors.black.withValues(alpha: 0.55),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.explore, color: Colors.white70, size: 18),
            const SizedBox(width: 6),
            Text(
              'X ${x.round()}, Y ${y.round()}',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _WoodHud extends StatelessWidget {
  final IslandState island;

  const _WoodHud({required this.island});

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Colors.black.withValues(alpha: 0.55),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.forest, color: Colors.brown, size: 28),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  island.wood.toStringAsFixed(2),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  '+${island.woodPerSecond} / sec',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
