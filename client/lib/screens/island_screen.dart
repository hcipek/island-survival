import 'dart:async';

import 'package:flame/game.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api.dart';
import '../inventory.dart';
import '../island_game.dart';
import '../live_connection.dart';
import '../minimap.dart';
import '../protocol.dart';
import 'hud.dart';

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
  /// Identifies this tab to the server - the same one is used when
  /// reconnecting, so it can take over its own old connection.
  final String _clientId = newClientId();
  final IslandApi _api = IslandApi();
  IslandGame? _game;
  LiveConnection? _live;
  List<IslandLayout>? _layout;
  Timer? _hudTimer;
  String? _error;
  bool _showMapBorder = false;
  bool _connected = false;

  /// Latest wood numbers from the server, and when they arrived - the HUD
  /// counts up from there by itself between updates.
  PlayerState? _island;
  DateTime _islandReceivedAt = DateTime.now();

  /// The player's inventory, shared with the bag sheet so it stays up to
  /// date while open.
  final ValueNotifier<InventoryState> _inventory = ValueNotifier(InventoryState.empty);

  /// What can be crafted, as the server listed it in the snapshot.
  List<Recipe> _recipes = const [];

  /// Events are applied strictly one after another, since applying one can
  /// take a moment (e.g. spawning components) and order matters.
  Future<void> _eventQueue = Future.value();

  /// Events that arrive while the game is still loading, applied once it's
  /// ready.
  final List<Map<String, dynamic>> _eventsBeforeLoad = [];

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
    _inventory.addListener(_syncHeldItem);
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    // Islands never change, so they come from a plain request; everything
    // else arrives over the live connection, starting with a snapshot.
    try {
      final islands = await _api.fetchWorldIslands(widget.worldId);
      _layout = islands
          .map((island) => IslandLayout(
                centerX: island.centerX,
                centerY: island.centerY,
                radius: island.radius,
                isHome: island.isHome,
              ))
          .toList();
    } catch (e) {
      setState(() => _error = 'Could not reach backend: $e');
      return;
    }

    _live = LiveConnection(
      url: _api.liveUrl(widget.worldId, widget.userId, _clientId),
      onEvent: _onLiveEvent,
      onStatusChanged: (connected) {
        if (mounted) setState(() => _connected = connected);
      },
    )..connect();

    // Your own position, and the wood counter, change every frame without
    // any message from the server - redraw the HUD regularly.
    _hudTimer = Timer.periodic(const Duration(milliseconds: 200), (_) {
      setState(() {
        final game = _game;
        if (game != null) game.isStarving = _currentIsland?.isStarving ?? false;
      });
    });
  }

  @override
  void dispose() {
    _live?.close();
    _hudTimer?.cancel();
    HardwareKeyboard.instance.removeHandler(_onKey);
    _inventory.dispose();
    super.dispose();
  }

  /// 1-9 and 0 pick a quick-access slot, E uses what you're holding.
  bool _onKey(KeyEvent event) {
    if (event is! KeyDownEvent || _game == null) return false;
    final character = event.character;
    if (character != null && character.length == 1) {
      final digit = int.tryParse(character);
      if (digit != null) {
        _selectSlot(digit == 0 ? 9 : digit - 1);
        return true;
      }
      if (character.toLowerCase() == 'e') {
        _useHeld();
        return true;
      }
    }
    return false;
  }

  void _selectSlot(int slot) {
    _inventory.value = _inventory.value.withSelected(slot);
    _send({'type': 'select', 'slot': slot});
  }

  void _useHeld() {
    final held = _inventory.value.held;
    if (held == null) return;
    if (isFood(held.item)) {
      if (_currentIsland?.isFull ?? false) {
        _showNotice("You're not hungry");
        return;
      }
      _send({'type': 'use'});
    }
  }

  void _openCraft() {
    showModalBottomSheet(
      context: context,
      backgroundColor: InventoryColors.panel,
      builder: (_) => CraftSheet(
        recipes: _recipes,
        inventory: _inventory,
        onCraft: (recipe) => _send({'type': 'craft', 'recipe': recipe.id}),
      ),
    );
  }

  void _openBag() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: InventoryColors.panel,
      builder: (_) => BagSheet(
        inventory: _inventory,
        onMove: (from, to) => _send({'type': 'moveItem', 'fromSlot': from, 'toSlot': to}),
        onDiscard: (slot) => _send({'type': 'discard', 'slot': slot}),
      ),
    );
  }

  void _onLiveEvent(Map<String, dynamic> event) {
    final game = _game;
    if (game == null) {
      if (event['type'] == 'snapshot') {
        _startGame(event);
      } else if (event['type'] == 'error') {
        _leaveBecause(event['message'] as String? ?? 'Could not join this world');
      }
      return;
    }
    if (!game.isLoaded) {
      _eventsBeforeLoad.add(event);
      return;
    }
    _eventQueue = _eventQueue.then((_) => _applyEvent(game, event)).catchError((_) {});
  }

  void _startGame(Map<String, dynamic> snapshot) {
    final lastPosition = lastPositionFromJson(snapshot['you']);
    _setPlayerState(snapshot['state']);
    _setInventory(snapshot['inventory']);
    _recipes = listFromJson(snapshot['recipes'], Recipe.fromJson);
    final game = IslandGame(
      islandLayout: _layout!,
      existingBuildings: listFromJson(snapshot['buildings'], buildingFromJson),
      existingBoats: listFromJson(snapshot['boats'], boatFromJson),
      existingPlayers: listFromJson(snapshot['players'], playerFromJson),
      existingNodes: listFromJson(snapshot['nodes'], nodeFromJson),
      existingAnimals: listFromJson(snapshot['animals'] ?? const [], animalFromJson),
      startPosition: lastPosition?.position,
      startBoatId: lastPosition?.boatId,
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
      // Closures in a cascade need parentheses - otherwise the next `..`
      // would apply to the closure's result instead of to the game.
      ..onPlacementStateChanged = (() => setState(() {}))
      ..onLocalWalk = ((from, path) => _send({
            'type': 'move',
            'from': pointToJson(from),
            'path': path.map(pointToJson).toList(),
          }))
      ..onLocalBoard = ((boatId) => _send({'type': 'board', 'boatId': boatId}))
      ..onLocalSail = ((boatId, from, path) => _send({
            'type': 'sail',
            'boatId': boatId,
            'from': pointToJson(from),
            'path': path.map(pointToJson).toList(),
          }))
      ..onLocalLand = ((boatId, position) => _send({
            'type': 'land',
            'boatId': boatId,
            'position': pointToJson(position),
          }))
      ..onLocalGather = ((nodeId) => _send({'type': 'gather', 'nodeId': nodeId}))
      ..onLocalCollect = ((buildingId) => _send({'type': 'collect', 'buildingId': buildingId}))
      ..onLocalAttack = ((animalId) => _send({'type': 'attack', 'animalId': animalId}));

    setState(() => _game = game);
    _syncHeldItem();

    game.loaded.then((_) {
      _join(game);
      for (final event in _eventsBeforeLoad) {
        _eventQueue = _eventQueue.then((_) => _applyEvent(game, event)).catchError((_) {});
      }
      _eventsBeforeLoad.clear();
    });
  }

  /// The server refused to let us in (e.g. someone is already playing as
  /// this user): stop trying and go back to the welcome screen.
  void _leaveBecause(String message) {
    _live?.close();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    Navigator.of(context).pop();
  }

  /// Briefly tints the screen red when you get hurt.
  bool _hurtFlash = false;

  void _flashHurt() {
    setState(() => _hurtFlash = true);
    Future.delayed(const Duration(milliseconds: 250), () {
      if (mounted) setState(() => _hurtFlash = false);
    });
  }

  void _showDeath(String cause, String bag) {
    if (!mounted) return;
    final bagText = switch (bag) {
      'KEPT' => 'You kept everything.',
      'DROPPED' => 'Your bag was left where you died - go back for it before it disappears (about 10 minutes).',
      'LOST' => 'The things in your bag are gone.',
      _ => 'Your bag was empty.',
    };
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        icon: const Icon(Icons.heart_broken, color: Colors.redAccent, size: 40),
        title: Text(cause),
        content: Text('You woke up on the home island.\n\n$bagText Your quick-access bar is always kept.'),
        actions: [FilledButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('OK'))],
      ),
    );
  }

  void _send(Map<String, dynamic> message) {
    _live?.send(message);
  }

  /// Makes our player visible to everyone else, including whatever they're
  /// in the middle of doing (after a reconnect).
  void _join(IslandGame game) {
    _send({'type': 'join', 'position': pointToJson(game.player.position)});
    // The server doesn't remember what you were holding across reconnects.
    _send({'type': 'select', 'slot': _inventory.value.selectedSlot});
    game.reportCurrentActivity();
  }

  Future<void> _applyEvent(IslandGame game, Map<String, dynamic> event) async {
    switch (event['type']) {
      case 'snapshot':
        // We reconnected: catch up on everything, then rejoin.
        _setPlayerState(event['state']);
        _setInventory(event['inventory']);
        await game.syncBuildings(listFromJson(event['buildings'], buildingFromJson));
        await game.applyBoats(listFromJson(event['boats'], boatFromJson));
        await game.applyRemotePlayers(listFromJson(event['players'], playerFromJson));
        await game.applyNodes(listFromJson(event['nodes'], nodeFromJson));
        await game.applyAnimals(listFromJson(event['animals'] ?? const [], animalFromJson));
        _join(game);
      case 'state':
        _setPlayerState(event['state']);
      case 'inventory':
        _setInventory(event['inventory']);
      case 'buildingUpdated':
        game.updateBuilding(buildingFromJson(event['building'] as Map<String, dynamic>));
      case 'buildingAdded':
        await game.addBuilding(buildingFromJson(event['building'] as Map<String, dynamic>));
      case 'buildingRemoved':
        game.removeBuilding(event['buildingId'] as int);
      case 'boatAdded':
        await game.addBoat(boatFromJson(event['boat'] as Map<String, dynamic>));
      case 'playerJoined':
        await game.addRemotePlayer(playerFromJson(event['player'] as Map<String, dynamic>));
      case 'playerLeft':
        game.removeRemotePlayer(event['userId'] as int);
      case 'playerMoved':
        game.moveRemotePlayer(
          event['userId'] as int,
          movementFromJson(event['movement'] as Map<String, dynamic>)!,
        );
      case 'playerBoarded':
        game.boardRemotePlayer(event['userId'] as int, event['boatId'] as int);
      case 'boatMoved':
        game.moveRemoteBoat(
          event['boatId'] as int,
          movementFromJson(event['movement'] as Map<String, dynamic>)!,
        );
      case 'playerRespawned':
        final position = pointFromJson(event['position'] as Map<String, dynamic>);
        if (event['userId'] == widget.userId) {
          game.respawnLocalPlayer(position);
        } else {
          game.respawnRemotePlayer(event['userId'] as int, position);
        }
      case 'youDied':
        _showDeath(event['cause'] as String? ?? 'You died', event['bag'] as String? ?? 'EMPTY');
      case 'playerLanded':
        game.landRemotePlayer(
          event['userId'] as int,
          pointFromJson(event['position'] as Map<String, dynamic>),
        );
      case 'nodeDepleted':
        game.depleteNode(
          event['nodeId'] as int,
          Duration(milliseconds: (event['regrowsInMs'] as num).toInt()),
        );
      case 'animalAdded':
        await game.addAnimal(animalFromJson(event['animal'] as Map<String, dynamic>));
      case 'animalMoved':
        game.moveAnimal(
          event['animalId'] as int,
          movementFromJson(event['movement'] as Map<String, dynamic>?),
          pointFromJson(event['restingAt'] as Map<String, dynamic>),
        );
      case 'hurt':
        _flashHurt();
      case 'animalHurt':
        game.hurtAnimal(event['animalId'] as int, event['health'] as int);
      case 'animalKilled':
        game.removeAnimal(event['animalId'] as int);
      case 'nodeAdded':
        await game.addNode(nodeFromJson(event['node'] as Map<String, dynamic>));
      case 'nodeRemoved':
        game.removeNode(event['nodeId'] as int);
      case 'error':
        _showNotice(event['message'] as String? ?? 'Something went wrong');
    }
  }

  void _setInventory(Object? json) {
    if (json is! Map<String, dynamic>) return;
    _inventory.value = InventoryState.fromJson(json);
  }

  /// Tells the game what's in the player's hand whenever it changes.
  void _syncHeldItem() {
    _game?.heldItem = _inventory.value.held?.item;
  }

  void _setPlayerState(Object? json) {
    if (json is! Map<String, dynamic>) return;
    setState(() {
      _island = PlayerState.fromJson(json);
      _islandReceivedAt = DateTime.now();
    });
  }

  /// [_island] with the hunger lost since it arrived taken off.
  PlayerState? get _currentIsland {
    final island = _island;
    if (island == null) return null;
    final seconds = DateTime.now().difference(_islandReceivedAt).inMilliseconds / 1000;
    return island.after(seconds);
  }

  void _showBuildMenu() {
    final game = _game!;
    final messenger = ScaffoldMessenger.of(context);
    if (_currentIsland?.isStarving ?? false) {
      _showNotice("You're too hungry to build - pick some berries and eat first");
      return;
    }
    if (_inventory.value.held?.item != ItemType.hammer) {
      _showNotice(_inventory.value.count(ItemType.hammer) > 0
          ? 'Hold your hammer to build - pick it in the quick-access bar'
          : 'You need a hammer to build - craft one from 2 wood and 3 stones');
      return;
    }
    // The limit is per player, so only our own lumberjacks count.
    final canBuildLumberjack = !game.lumberjacks.any((l) => game.isOwnedByMe(l.ownerId));
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
                canBuildLumberjack
                    ? 'Stores wood for you to collect. Costs 20 wood (you have ${_inventory.value.count(ItemType.wood)}). Placed inland.'
                    : 'You already have one (limit 1 per player for now)',
              ),
              enabled: canBuildLumberjack,
              onTap: canBuildLumberjack
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

  Future<BoatRecord?> _buildBoat(int portId) async {
    try {
      // The server pushes the new wood total (and the boat) to everyone.
      return await _api.createBoat(widget.worldId, portId, widget.userId);
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
    if (!_game!.isOwnedByMe(lumberjack.ownerId)) {
      showModalBottomSheet(
        context: context,
        builder: (_) => const SafeArea(child: NotYoursTile(building: 'lumberjack')),
      );
      return;
    }
    final ready = lumberjack.storedWood.floor();
    showModalBottomSheet(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const ItemIcon(item: ItemType.wood, size: 28),
              title: const Text('Collect wood'),
              subtitle: Text('$ready of ${lumberjack.woodCapacity.round()} ready'),
              enabled: ready >= 1,
              onTap: () {
                Navigator.pop(sheetContext);
                _game!.requestCollect(lumberjack);
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline, color: Colors.red),
              title: const Text('Destroy'),
              subtitle: const Text('Remove this lumberjack - no refund, and any wood in it is lost'),
              onTap: () {
                Navigator.pop(sheetContext);
                _game!.destroyLumberjack(lumberjack);
              },
            ),
          ],
        ),
      ),
    );
  }

  void _showPortMenu(PortComponent port) {
    if (!_game!.isOwnedByMe(port.ownerId)) {
      showModalBottomSheet(
        context: context,
        builder: (_) => const SafeArea(child: NotYoursTile(building: 'port')),
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
                isSaved
                    ? 'Build a boat at this port. Costs 10 wood (you have ${_inventory.value.count(ItemType.wood)}).'
                    : 'Port is still being built...',
              ),
              enabled: isSaved,
              onTap: () {
                Navigator.pop(sheetContext);
                if (_inventory.value.held?.item != ItemType.hammer) {
                  _showNotice('Hold a hammer to build a boat');
                  return;
                }
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
                Positioned.fill(
                  child: IgnorePointer(
                    child: AnimatedOpacity(
                      opacity: _hurtFlash ? 1 : 0,
                      duration: const Duration(milliseconds: 150),
                      child: Container(color: const Color(0x55D50000)),
                    ),
                  ),
                ),
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
                else if (_currentIsland != null)
                  Positioned(
                    top: 16,
                    left: 16,
                    child: VitalsHud(island: _currentIsland!),
                  ),
                if (!_connected)
                  const Positioned(
                    top: 16,
                    left: 0,
                    right: 0,
                    child: Center(child: ReconnectingBanner()),
                  ),
                if (game.isLoaded)
                  Positioned(
                    top: 16,
                    right: 16,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        CoordinatesHud(
                          x: game.player.position.x,
                          y: game.player.position.y,
                        ),
                        const SizedBox(height: 8),
                        Minimap(game: game),
                      ],
                    ),
                  ),
                Positioned(
                  bottom: 8,
                  left: 8,
                  right: 8,
                  child: Center(
                    child: ValueListenableBuilder<InventoryState>(
                      valueListenable: _inventory,
                      builder: (context, inventory, _) => Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (inventory.held != null && isFood(inventory.held!.item))
                            Padding(
                              padding: const EdgeInsets.only(bottom: 6),
                              child: FilledButton.icon(
                                onPressed: _useHeld,
                                icon: ItemIcon(item: inventory.held!.item, size: 20),
                                label: const Text('Eat (E)'),
                              ),
                            ),
                          Hotbar(
                            inventory: inventory,
                            onSelect: _selectSlot,
                            onOpenBag: _openBag,
                            onOpenCraft: _openCraft,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                Positioned(
                  bottom: 90,
                  right: 16,
                  child: ZoomControls(game: game),
                ),
                Positioned(
                  bottom: 90,
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
