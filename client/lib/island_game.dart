import 'dart:math';
import 'dart:ui';

import 'package:flame/components.dart';
import 'package:flame/game.dart';

import 'game/animals.dart';
import 'game/boat.dart';
import 'game/build_ghost.dart';
import 'game/buildings.dart';
import 'game/island_components.dart';
import 'game/player.dart';
import 'game/records.dart';
import 'game/resource_nodes.dart';
import 'game/world_input_layer.dart';
import 'inventory.dart';

// The game's pieces live in lib/game/; exporting them here means anything
// that imports the game gets them too.
export 'game/animals.dart';
export 'game/boat.dart';
export 'game/build_ghost.dart';
export 'game/buildings.dart';
export 'game/island_components.dart';
export 'game/player.dart';
export 'game/records.dart';
export 'game/resource_nodes.dart';
export 'game/world_input_layer.dart';

class IslandGame extends FlameGame {
  static const double islandRadius = 600;
  static const double _worldSeaBoundary = 4500;

  final List<IslandLayout> islandLayout;
  final List<BuildingRecord> existingBuildings;
  final List<BoatRecord> existingBoats;
  final List<RemotePlayerRecord> existingPlayers;
  final List<ResourceNodeRecord> existingNodes;
  final List<AnimalRecord> existingAnimals;

  /// Where this player was when they last left (null the first time), and
  /// the boat they were aboard, if any - they start from there.
  final Vector2? startPosition;
  final int? startBoatId;
  final int userId;
  final String username;
  final Color playerColor;

  IslandGame({
    required this.islandLayout,
    required this.userId,
    required this.username,
    required this.playerColor,
    this.existingBuildings = const [],
    this.existingBoats = const [],
    this.existingPlayers = const [],
    this.existingNodes = const [],
    this.existingAnimals = const [],
    this.startPosition,
    this.startBoatId,
  });

  late final World islandWorld;
  late final CameraComponent cameraComponent;
  late final IslandComponent homeIsland;
  late final PlayerComponent player;

  final List<IslandComponent> islands = [];
  final List<PortComponent> ports = [];
  final List<BoatComponent> boats = [];
  final List<LumberjackComponent> lumberjacks = [];
  final Map<int, ResourceNodeComponent> resourceNodes = {};
  final Map<int, AnimalComponent> animals = {};
  final Map<int, PlayerComponent> _remotePlayers = {};

  /// The island the player is currently standing/walking on.
  late IslandComponent currentPlayerIsland;

  /// Called when the player taps an already-built port, so the Flutter UI
  /// can show that port's menu (a BuildContext is needed for that).
  void Function(PortComponent port)? onPortTapped;

  /// Called when the player taps an already-built lumberjack, so the
  /// Flutter UI can show its menu.
  void Function(LumberjackComponent lumberjack)? onLumberjackTapped;

  /// Called when the player confirms placing a port; the Flutter UI
  /// persists it on the backend and returns its id, or null on failure so
  /// the placement can be rolled back.
  Future<int?> Function(Vector2 position)? onPortBuildRequested;

  /// Called when the player destroys a port; the Flutter UI tells the
  /// backend to forget it and reports whether that succeeded.
  Future<bool> Function(int backendId)? onPortDestroyRequested;

  /// Called when the player confirms placing a lumberjack; the Flutter UI
  /// performs the actual backend call (spending wood) and returns its id,
  /// or null on failure so the placement can be rolled back.
  Future<int?> Function(Vector2 position)? onLumberjackBuildRequested;

  /// Called when the player destroys a lumberjack; the Flutter UI performs
  /// the actual backend call and reports whether it succeeded, so the
  /// removal can be rolled back (the building re-added) if not.
  Future<bool> Function(int backendId)? onLumberjackDestroyRequested;

  /// Called when the player builds a boat at one of their ports; the
  /// Flutter UI performs the backend call (spending wood) and returns the
  /// new boat, or null on failure.
  Future<BoatRecord?> Function(int portBackendId)? onBoatBuildRequested;

  /// Called when this player starts walking, so the Flutter UI can tell the
  /// server (which passes it on to everyone else).
  void Function(Vector2 from, List<Vector2> path)? onLocalWalk;

  /// Called when this player steps aboard one of their boats.
  void Function(int boatId)? onLocalBoard;

  /// Called when this player starts sailing the boat they're aboard.
  void Function(int boatId, Vector2 from, List<Vector2> path)? onLocalSail;

  /// Called when this player steps off their boat at [position].
  void Function(int boatId, Vector2 position)? onLocalLand;

  /// Called when this player has finished gathering from a node, so the
  /// Flutter UI can ask the server for what it gives.
  void Function(int nodeId)? onLocalGather;

  /// Called when this player has walked up to their lumberjack to collect
  /// its wood.
  void Function(int buildingId)? onLocalCollect;

  /// Called with a short message the Flutter UI should show the player,
  /// e.g. when they try to board someone else's boat.
  void Function(String message)? onNotice;

  /// Called whenever placement mode starts, moves, or ends, so the Flutter
  /// UI (e.g. the Build button label) can stay in sync via setState.
  VoidCallback? onPlacementStateChanged;

  BuildingType? _pendingBuildingType;
  BuildGhostComponent? _ghost;
  late final MapBoundaryComponent _mapBoundary;

  double _minZoom = 0.3;
  double _maxZoom = 3.0;
  double _zoomAtGestureStart = 1;
  bool _hasAppliedInitialZoom = false;

  /// While true, the camera stays wherever the player last dragged it to
  /// instead of re-centering on the player every frame.
  bool _isFreeLooking = false;

  @override
  Color backgroundColor() => const Color(0xFF4FA8D8);

  @override
  Future<void> onLoad() async {
    await super.onLoad();

    islandWorld = World();

    IslandComponent? home;
    for (final layout in islandLayout) {
      final island = await _spawnIsland(
        center: Vector2(layout.centerX, layout.centerY),
        radius: layout.radius,
      );
      if (layout.isHome) {
        home = island;
      }
    }
    homeIsland = home!;
    currentPlayerIsland = homeIsland;

    for (final record in existingAnimals) {
      await _spawnAnimal(record);
    }
    for (final record in existingNodes) {
      await _spawnNode(record);
    }
    for (final record in existingBuildings) {
      await _spawnBuildingFromRecord(record);
    }
    for (final record in existingBoats) {
      await _spawnBoatFromRecord(record);
    }
    for (final record in existingPlayers) {
      await _upsertRemotePlayer(record);
    }

    player = PlayerComponent(
      islandRadius: islandRadius,
      username: username,
      ownerColor: playerColor,
    )..position = homeIsland.position.clone();
    _placePlayerWhereTheyLeft();
    await islandWorld.add(player);

    await islandWorld.add(
      WorldInputLayer(onTapAt: _handleWorldTap, onHoverAt: _handleWorldHover),
    );

    _mapBoundary = MapBoundaryComponent(boundaryRadius: _worldSeaBoundary);
    await islandWorld.add(_mapBoundary);

    cameraComponent = CameraComponent(world: islandWorld)
      ..viewfinder.anchor = Anchor.center
      ..viewfinder.position = player.position.clone();

    await addAll([islandWorld, cameraComponent]);

    _fitZoomToScreen();
  }

  /// How long picking berries, sticks or stones takes - and chopping a
  /// tree - shown as a ring above the player. The server's cooldown between
  /// gathers is a little shorter than either.
  static const double gatherSeconds = 2;
  static const double chopSeconds = 3;

  /// What the player is holding, kept up to date by the Flutter UI from
  /// their quick-access bar. Tools only work while held.
  ItemType? heldItem;

  /// Kept up to date by the Flutter UI from the player's hunger. At zero you
  /// can still move and pick berries, but not gather sticks or build.
  bool isStarving = false;

  /// What this player is gathering from right now, if anything.
  ResourceNodeComponent? _gatheringFrom;
  double _gatherElapsed = 0;
  double _gatherDuration = gatherSeconds;

  @override
  void update(double dt) {
    super.update(dt);
    if (!_isFreeLooking) {
      cameraComponent.viewfinder.position = player.position;
    }
    _updateGathering(dt);
  }

  void _updateGathering(double dt) {
    final node = _gatheringFrom;
    if (node == null) return;
    // Picked by someone else first: a bush goes bare, sticks vanish.
    if (!node.isAvailable || resourceNodes[node.id] != node) {
      _stopGathering();
      onNotice?.call('Someone else got there first');
      return;
    }
    _gatherElapsed += dt;
    player.gatherProgress = (_gatherElapsed / _gatherDuration).clamp(0, 1);
    if (_gatherElapsed >= _gatherDuration) {
      _stopGathering();
      onLocalGather?.call(node.id);
    }
  }

  void _stopGathering() {
    _gatheringFrom = null;
    _gatherElapsed = 0;
    player.gatherProgress = null;
  }

  /// Called when the player taps a tree, bush or pile of sticks: walks up to
  /// it and gathers once there.
  void requestGather(ResourceNodeComponent node) {
    if (player.boardedBoat != null) {
      onNotice?.call('Land on the island first');
      return;
    }
    final isTree = node.type == ResourceNodeType.tree;
    if (!node.isAvailable) {
      final seconds = node.regrowsAt!.difference(DateTime.now()).inSeconds + 1;
      onNotice?.call(isTree ? 'Just a stump - it grows back in ${seconds}s' : 'Already picked - it regrows in ${seconds}s');
      return;
    }
    if (isTree && heldItem != ItemType.axe) {
      onNotice?.call('Hold an axe to chop trees');
      return;
    }
    if (_islandAt(node.position) != currentPlayerIsland) {
      onNotice?.call('Sail over to that island first');
      return;
    }
    if (node is ItemPileComponent && !isOwnedByMe(node.ownerId ?? -1)) {
      onNotice?.call("Those are someone else's things");
      return;
    }
    // Berries and your own dropped things are fine while starving.
    if (isStarving && node.type != ResourceNodeType.berryBush && node.type != ResourceNodeType.itemPile) {
      onNotice?.call("You're starving - pick some berries and eat first");
      return;
    }

    // Stand just next to it, on the side we're coming from.
    final offset = player.position - node.position;
    final direction = offset.length == 0 ? Vector2(0, 1) : offset.normalized();
    final spot = currentPlayerIsland.clampToIsland(node.position + direction * 35);
    _walkTo(spot, onArrived: () {
      _gatheringFrom = node;
      _gatherElapsed = 0;
      _gatherDuration = isTree ? chopSeconds : gatherSeconds;
    });
  }

  /// Walks to [lumberjack] (one of ours) and collects its wood once there.
  void requestCollect(LumberjackComponent lumberjack) {
    final id = lumberjack.backendId;
    if (id == null) return;
    if (player.boardedBoat != null) {
      onNotice?.call('Land on the island first');
      return;
    }
    if (_islandAt(lumberjack.position) != currentPlayerIsland) {
      onNotice?.call('Sail over to that island first');
      return;
    }
    final offset = player.position - lumberjack.position;
    final direction = offset.length == 0 ? Vector2(0, 1) : offset.normalized();
    final spot = currentPlayerIsland.clampToIsland(lumberjack.position + direction * 70);
    _walkTo(spot, onArrived: () => onLocalCollect?.call(id));
  }

  /// A building changed on the server, e.g. a lumberjack's wood was taken.
  void updateBuilding(BuildingRecord record) {
    for (final lumberjack in lumberjacks.where((l) => l.backendId == record.id)) {
      lumberjack.applyStorage(record);
    }
  }

  /// Someone (maybe us) gathered from a node; it's bare for [regrowsIn].
  void depleteNode(int nodeId, Duration regrowsIn) {
    resourceNodes[nodeId]?.setRegrowsIn(regrowsIn);
  }

  /// New sticks dropped somewhere.
  Future<void> addNode(ResourceNodeRecord record) async {
    if (resourceNodes.containsKey(record.id)) return;
    await _spawnNode(record);
  }

  /// Sticks were picked up (by anyone), so they're gone.
  void removeNode(int nodeId) {
    resourceNodes.remove(nodeId)?.removeFromParent();
  }

  /// Snaps the camera back to following the player after a free-look pan.
  void recenterOnPlayer() {
    _isFreeLooking = false;
  }

  bool isOwnedByMe(int ownerId) => ownerId == userId;

  /// Radius of the sailable sea around the world origin - the minimap uses
  /// it as the extent of the full map.
  double get worldSeaBoundary => _worldSeaBoundary;

  /// Other players currently shown in this world.
  Iterable<PlayerComponent> get remotePlayers => _remotePlayers.values;

  /// The part of the world currently on screen.
  Rect get visibleWorldRect => cameraComponent.visibleWorldRect;

  /// Jumps the camera to [worldPosition] (e.g. from a minimap tap),
  /// entering free-look mode.
  void lookAt(Vector2 worldPosition) {
    _isFreeLooking = true;
    cameraComponent.viewfinder.position = worldPosition;
  }

  void setDebugBoundaryVisible(bool visible) {
    _mapBoundary.visible = visible;
  }

  Future<IslandComponent> _spawnIsland({
    required Vector2 center,
    required double radius,
  }) async {
    final newIsland = IslandComponent(center: center, islandRadius: radius);
    islands.add(newIsland);
    await islandWorld.add(newIsland);
    return newIsland;
  }

  Future<void> _spawnNode(ResourceNodeRecord record) async {
    final position = Vector2(record.x, record.y);
    final node = switch (record.type) {
      ResourceNodeType.tree => TreeComponent(id: record.id, position: position, islandRadius: islandRadius),
      ResourceNodeType.berryBush =>
        BerryBushComponent(id: record.id, position: position, islandRadius: islandRadius),
      ResourceNodeType.sticks => SticksComponent(id: record.id, position: position, islandRadius: islandRadius),
      ResourceNodeType.stones => StonesComponent(id: record.id, position: position, islandRadius: islandRadius),
      ResourceNodeType.itemPile =>
        ItemPileComponent(id: record.id, position: position, islandRadius: islandRadius, ownerId: record.ownerId),
    };
    node.setRegrowsIn(record.regrowsIn);
    resourceNodes[record.id] = node;
    await islandWorld.add(node);
  }

  Future<void> _spawnAnimal(AnimalRecord record) async {
    final animal = AnimalComponent.fromRecord(record);
    animals[record.id] = animal;
    await islandWorld.add(animal);
  }

  /// A new animal appeared.
  Future<void> addAnimal(AnimalRecord record) async {
    if (animals.containsKey(record.id)) return;
    await _spawnAnimal(record);
  }

  /// An animal set off somewhere (or, with no [movement], stopped at
  /// [restingAt]).
  void moveAnimal(int animalId, MovementRecord? movement, Vector2 restingAt) {
    final animal = animals[animalId];
    if (animal == null) return;
    if (movement == null) {
      animal
        ..stop()
        ..position = restingAt;
    } else {
      animal.follow(movement);
    }
  }

  void removeAnimal(int animalId) {
    animals.remove(animalId)?.removeFromParent();
  }

  /// Brings the animals in line with a fresh snapshot.
  Future<void> applyAnimals(List<AnimalRecord> latest) async {
    for (final animal in animals.values.toList()) {
      animal.removeFromParent();
    }
    animals.clear();
    for (final record in latest) {
      await _spawnAnimal(record);
    }
  }

  /// Brings trees, bushes and sticks in line with a fresh snapshot.
  Future<void> applyNodes(List<ResourceNodeRecord> latest) async {
    final latestIds = latest.map((n) => n.id).toSet();
    for (final id in resourceNodes.keys.where((id) => !latestIds.contains(id)).toList()) {
      resourceNodes.remove(id)?.removeFromParent();
    }
    for (final record in latest) {
      final existing = resourceNodes[record.id];
      if (existing == null) {
        await _spawnNode(record);
      } else {
        existing.setRegrowsIn(record.regrowsIn);
      }
    }
  }

  /// Puts the player back aboard their boat, or on the island they were
  /// standing on - or on the home island the first time (or if the stored
  /// spot isn't on land for some reason).
  void _placePlayerWhereTheyLeft() {
    final boatId = startBoatId;
    final boat = boatId == null ? null : _boatById(boatId);
    if (boat != null && isOwnedByMe(boat.ownerId)) {
      boat.isSelected = true;
      player
        ..boardedBoat = boat
        ..position = boat.position.clone();
      return;
    }
    final start = startPosition;
    final island = start == null ? null : _islandAt(start);
    if (start != null && island != null) {
      player.position = start.clone();
      currentPlayerIsland = island;
    }
  }

  @override
  void onGameResize(Vector2 size) {
    super.onGameResize(size);
    if (isLoaded) {
      _fitZoomToScreen();
    }
  }

  void _fitZoomToScreen() {
    final shortestSide = size.x < size.y ? size.x : size.y;
    final fitZoom = shortestSide / (islandRadius * 2 * 1.3);
    _minZoom = fitZoom * 0.5;
    _maxZoom = fitZoom * 5;

    if (!_hasAppliedInitialZoom) {
      // Only set the zoom on the very first layout. Later calls (e.g. from
      // spurious browser resize/metrics events) should just re-clamp the
      // bounds, not throw away a zoom level the player already chose.
      cameraComponent.viewfinder.zoom = fitZoom;
      _hasAppliedInitialZoom = true;
    } else {
      cameraComponent.viewfinder.zoom =
          cameraComponent.viewfinder.zoom.clamp(_minZoom, _maxZoom);
    }
  }

  void zoomBy(double factor) {
    cameraComponent.viewfinder.zoom =
        (cameraComponent.viewfinder.zoom * factor).clamp(_minZoom, _maxZoom);
  }

  /// Pans the camera by a screen-space [delta] (e.g. from a single-pointer
  /// drag), entering free-look mode.
  void panCameraBy(Vector2 delta) {
    if (delta.length == 0) return;
    _isFreeLooking = true;
    cameraComponent.viewfinder.position -= delta / cameraComponent.viewfinder.zoom;
  }

  /// Called by [WorldInputLayer] when a two-finger pinch/scale gesture begins.
  void handleScaleStart() {
    _zoomAtGestureStart = cameraComponent.viewfinder.zoom;
  }

  /// Called by [WorldInputLayer] as a two-finger pinch/scale gesture
  /// progresses - also pans using the pinch's focal-point movement.
  void handleScaleUpdate(double scale, Vector2 focalPointDelta) {
    cameraComponent.viewfinder.zoom =
        (_zoomAtGestureStart * scale).clamp(_minZoom, _maxZoom);
    panCameraBy(focalPointDelta);
  }

  bool get isPlacingBuilding => _pendingBuildingType != null;

  /// Enters placement mode: a ghost preview of [type] appears and follows
  /// the pointer, glowing green where it's legal to build and red where
  /// it isn't. Tapping on a valid spot confirms the placement.
  void startPlacing(BuildingType type) {
    _pendingBuildingType = type;
    _ghost?.removeFromParent();
    final ghost = BuildGhostComponent(islandRadius: islandRadius)
      ..position = player.position.clone();
    ghost.isValid = _isValidLocation(type, ghost.position);
    _ghost = ghost;
    islandWorld.add(ghost);
    onPlacementStateChanged?.call();
  }

  void cancelPlacing() {
    _pendingBuildingType = null;
    _ghost?.removeFromParent();
    _ghost = null;
    onPlacementStateChanged?.call();
  }

  void _handleWorldHover(Vector2 worldPosition) {
    _updateGhostAt(worldPosition);
  }

  void _updateGhostAt(Vector2 worldPosition) {
    final ghost = _ghost;
    final pending = _pendingBuildingType;
    if (ghost == null || pending == null) return;
    ghost.position = worldPosition;
    ghost.isValid = _isValidLocation(pending, worldPosition);
  }

  /// Called by [BuildGhostComponent] while the player drags it around -
  /// this is how placement works on touch devices, which have no hover.
  void dragGhostBy(Vector2 worldDelta) {
    final ghost = _ghost;
    if (ghost == null) return;
    _updateGhostAt(ghost.position + worldDelta);
  }

  /// Called when the player lets go after dragging the ghost: confirms the
  /// placement if it landed somewhere valid, otherwise just leaves it be so
  /// they can try dragging again.
  void dropGhost() {
    final ghost = _ghost;
    final pending = _pendingBuildingType;
    if (ghost == null || pending == null) return;
    if (ghost.isValid) {
      _confirmPlacement(pending, ghost.position);
    } else {
      onPlacementStateChanged?.call();
    }
  }

  /// The island whose land [worldPosition] falls on, if any.
  IslandComponent? _islandAt(Vector2 worldPosition) {
    for (final candidate in islands) {
      if ((worldPosition - candidate.position).length <= candidate.radius) {
        return candidate;
      }
    }
    return null;
  }

  void _handleWorldTap(Vector2 worldPosition) {
    final pending = _pendingBuildingType;
    if (pending != null) {
      final ghost = _ghost!;
      ghost.position = worldPosition;
      final valid = _isValidLocation(pending, worldPosition);
      ghost.isValid = valid;
      if (valid) {
        _confirmPlacement(pending, worldPosition);
      } else {
        onPlacementStateChanged?.call();
      }
      return;
    }

    final tappedIsland = _islandAt(worldPosition);
    final boardedBoat = player.boardedBoat;

    if (boardedBoat != null) {
      if (tappedIsland != null) {
        _requestLanding(boardedBoat, tappedIsland, worldPosition);
      } else {
        final destination = _clampToSea(worldPosition);
        _sail(boardedBoat, _seaPathTo(boardedBoat.position, destination));
      }
      return;
    }

    if (tappedIsland == currentPlayerIsland) {
      _walkTo(tappedIsland!.clampToIsland(worldPosition));
    }
    // Tapping a different island while on foot, or tapping open sea without
    // being aboard a boat, does nothing - you need to sail there first.
  }

  /// Point on [island]'s edge closest to [worldPosition]'s bearing from its
  /// center - i.e. the nearest bit of coastline.
  Vector2 _nearestCoastPoint(IslandComponent island, Vector2 worldPosition) {
    final offset = worldPosition - island.position;
    final direction = offset.length == 0 ? Vector2(1, 0) : offset.normalized();
    return island.position + direction * island.radius;
  }

  /// Called when the player (on foot) taps a boat: walks to the shore right
  /// next to it and hops in automatically on arrival.
  void requestBoard(BoatComponent boat) {
    if (player.boardedBoat != null) return;
    if (!isOwnedByMe(boat.ownerId)) {
      onNotice?.call("That's another player's boat");
      return;
    }
    final boardingPoint = _nearestCoastPoint(currentPlayerIsland, boat.position);
    _walkTo(
      boardingPoint,
      onArrived: () {
        boat.isSelected = true;
        player.boardedBoat = boat;
        onLocalBoard?.call(boat.backendId!);
      },
    );
  }

  /// Walks this player to [target] and tells everyone else.
  void _walkTo(Vector2 target, {VoidCallback? onArrived}) {
    _stopGathering();
    final from = player.position.clone();
    player.moveTo(target, onArrived: onArrived);
    onLocalWalk?.call(from, [target]);
  }

  /// Sails [boat] (which this player is aboard) along [path] and tells
  /// everyone else.
  void _sail(BoatComponent boat, List<Vector2> path, {VoidCallback? onArrived}) {
    final from = boat.position.clone();
    boat.moveAlongPath(path, onArrived: onArrived);
    onLocalSail?.call(boat.backendId!, from, path);
  }

  /// Called when the player (aboard [boat]) taps a spot on [targetIsland]:
  /// sails the boat to the coastline nearest that spot and only then drops
  /// the player off right next to where the boat actually ends up.
  void _requestLanding(BoatComponent boat, IslandComponent targetIsland, Vector2 tapPosition) {
    final offset = tapPosition - targetIsland.position;
    final direction = offset.length == 0 ? Vector2(1, 0) : offset.normalized();
    final boatDestination = targetIsland.position + direction * (targetIsland.radius * 1.05);
    final landingSpot = targetIsland.position + direction * targetIsland.radius;

    _sail(
      boat,
      _seaPathTo(boat.position, boatDestination),
      onArrived: () {
        boat.isSelected = false;
        player.boardedBoat = null;
        player.position = landingSpot;
        currentPlayerIsland = targetIsland;
        onLocalLand?.call(boat.backendId!, landingSpot);
      },
    );
  }

  /// Keeps [worldPosition] out of every island's land and within the sea's
  /// overall bounds.
  Vector2 _clampToSea(Vector2 worldPosition) {
    var result = worldPosition;
    if (result.length > _worldSeaBoundary) {
      result = result.normalized() * _worldSeaBoundary;
    }
    for (final candidate in islands) {
      final offset = result - candidate.position;
      final minDistance = candidate.radius * 1.05;
      if (offset.length < minDistance) {
        final direction = offset.length == 0 ? Vector2(1, 0) : offset.normalized();
        result = candidate.position + direction * minDistance;
      }
    }
    return result;
  }

  /// A sea route from [start] to [end] that avoids cutting across whichever
  /// island the direct line would cross first: it heads to a safe radius
  /// just off that island's coast, arcs around it, then continues to [end].
  List<Vector2> _seaPathTo(Vector2 start, Vector2 end) {
    IslandComponent? blocker;
    var blockerT = 1.0;

    final segment = end - start;
    final lengthSquared = segment.length2;
    for (final candidate in islands) {
      final t = lengthSquared == 0
          ? 0.0
          : ((candidate.position - start).dot(segment) / lengthSquared).clamp(0.0, 1.0);
      final closestPoint = start + segment * t;
      if ((closestPoint - candidate.position).length < candidate.radius) {
        if (blocker == null || t < blockerT) {
          blocker = candidate;
          blockerT = t;
        }
      }
    }

    if (blocker == null) return [end];

    final center = blocker.position;
    final safeRadius = blocker.radius * 1.15;
    final startAngle = atan2(start.y - center.y, start.x - center.x);
    final endAngle = atan2(end.y - center.y, end.x - center.x);

    var delta = endAngle - startAngle;
    while (delta > pi) {
      delta -= 2 * pi;
    }
    while (delta < -pi) {
      delta += 2 * pi;
    }

    const arcSteps = 6;
    final waypoints = <Vector2>[
      center + Vector2(cos(startAngle), sin(startAngle)) * safeRadius,
    ];
    for (var i = 1; i <= arcSteps; i++) {
      final angle = startAngle + delta * i / arcSteps;
      waypoints.add(center + Vector2(cos(angle), sin(angle)) * safeRadius);
    }
    waypoints.add(end);
    return waypoints;
  }

  bool _isValidLocation(BuildingType type, Vector2 worldPosition) {
    switch (type) {
      case BuildingType.port:
        return currentPlayerIsland.isOnSand(worldPosition);
      case BuildingType.lumberjack:
        return currentPlayerIsland.isOnGrass(worldPosition);
    }
  }

  Future<void> _confirmPlacement(BuildingType type, Vector2 worldPosition) async {
    switch (type) {
      case BuildingType.port:
        final port = PortComponent(
          worldPosition: worldPosition,
          islandRadius: islandRadius,
          ownerId: userId,
          ownerColor: playerColor,
        );
        ports.add(port);
        await islandWorld.add(port);
        final id = onPortBuildRequested == null
            ? null
            : await onPortBuildRequested!(worldPosition);
        if (onPortBuildRequested != null && id == null) {
          ports.remove(port);
          port.removeFromParent();
        } else {
          port.backendId = id;
        }
      case BuildingType.lumberjack:
        final lumberjack = LumberjackComponent(
          worldPosition: worldPosition,
          islandRadius: islandRadius,
          ownerId: userId,
          ownerColor: playerColor,
        );
        lumberjacks.add(lumberjack);
        await islandWorld.add(lumberjack);
        final id = onLumberjackBuildRequested == null
            ? null
            : await onLumberjackBuildRequested!(worldPosition);
        if (onLumberjackBuildRequested != null && id == null) {
          lumberjacks.remove(lumberjack);
          lumberjack.removeFromParent();
        } else {
          lumberjack.backendId = id;
        }
    }
    cancelPlacing();
  }

  Future<void> destroyPort(PortComponent port) async {
    ports.remove(port);
    port.removeFromParent();

    final backendId = port.backendId;
    if (backendId == null) return;
    final succeeded = await (onPortDestroyRequested?.call(backendId) ?? Future.value(true));
    if (!succeeded) {
      ports.add(port);
      await islandWorld.add(port);
    }
  }

  Future<void> destroyLumberjack(LumberjackComponent lumberjack) async {
    lumberjacks.remove(lumberjack);
    lumberjack.removeFromParent();

    final backendId = lumberjack.backendId;
    if (backendId == null) return;
    final succeeded =
        await (onLumberjackDestroyRequested?.call(backendId) ?? Future.value(true));
    if (!succeeded) {
      // Backend refused/failed the removal - put it back so client and
      // server state stay in sync.
      lumberjacks.add(lumberjack);
      await islandWorld.add(lumberjack);
    }
  }

  /// Asks the backend for a boat at [port] - it decides where the boat
  /// spawns and charges the wood - and shows it once that succeeds.
  Future<void> buildBoatFromPort(PortComponent port) async {
    final portId = port.backendId;
    final request = onBoatBuildRequested;
    if (portId == null || request == null) return;
    final record = await request(portId);
    if (record == null) return;
    await addBoat(record);
  }

  Future<void> _spawnBoatFromRecord(BoatRecord record) async {
    final boat = BoatComponent(
      startPosition: Vector2(record.x, record.y),
      islandRadius: islandRadius,
      ownerId: record.ownerId,
    )..backendId = record.id;
    final movement = record.movement;
    if (movement != null) boat.follow(movement);
    boats.add(boat);
    await islandWorld.add(boat);
  }

  BoatComponent? _boatById(int id) => boats.where((b) => b.backendId == id).firstOrNull;

  /// A boat was built, by anyone. Our own new boat may already be here -
  /// the server's event and the build call's response can arrive in either
  /// order.
  Future<void> addBoat(BoatRecord record) async {
    if (_boatById(record.id) != null) return;
    await _spawnBoatFromRecord(record);
  }

  /// Another player started sailing one of their boats.
  void moveRemoteBoat(int boatId, MovementRecord movement) {
    final boat = _boatById(boatId);
    if (boat == null || isOwnedByMe(boat.ownerId)) return;
    boat.follow(movement);
  }

  /// Brings the boats in line with a fresh snapshot (e.g. after
  /// reconnecting). Our own boats keep their local state - this client is
  /// the one sailing them.
  Future<void> applyBoats(List<BoatRecord> latest) async {
    final latestIds = latest.map((b) => b.id).toSet();
    for (final boat in boats
        .where((b) => !latestIds.contains(b.backendId) && player.boardedBoat != b)
        .toList()) {
      boats.remove(boat);
      boat.removeFromParent();
    }

    for (final record in latest) {
      final existing = _boatById(record.id);
      if (existing == null) {
        await _spawnBoatFromRecord(record);
      } else if (!isOwnedByMe(existing.ownerId)) {
        final movement = record.movement;
        if (movement != null) {
          existing.follow(movement);
        } else {
          existing.stop();
          existing.position = Vector2(record.x, record.y);
        }
      }
    }
  }

  /// Re-sends what this player is doing right now, so the server (and
  /// everyone else) catches up after a reconnect.
  void reportCurrentActivity() {
    final boat = player.boardedBoat;
    if (boat != null) {
      final id = boat.backendId!;
      onLocalBoard?.call(id);
      final remaining = boat.remainingPath;
      onLocalSail?.call(id, boat.position.clone(), remaining.isEmpty ? [boat.position.clone()] : remaining);
      return;
    }
    final remaining = player.remainingPath;
    if (remaining.isNotEmpty) {
      onLocalWalk?.call(player.position.clone(), remaining);
    }
  }

  Future<void> _spawnBuildingFromRecord(BuildingRecord record) async {
    switch (record.type) {
      case BuildingType.port:
        final port = PortComponent(
          worldPosition: Vector2(record.x, record.y),
          islandRadius: islandRadius,
          ownerId: record.ownerId,
          ownerColor: record.ownerColor,
        )..backendId = record.id;
        ports.add(port);
        await islandWorld.add(port);
      case BuildingType.lumberjack:
        final lumberjack = LumberjackComponent(
          worldPosition: Vector2(record.x, record.y),
          islandRadius: islandRadius,
          ownerId: record.ownerId,
          ownerColor: record.ownerColor,
        )
          ..backendId = record.id
          ..applyStorage(record);
        lumberjacks.add(lumberjack);
        await islandWorld.add(lumberjack);
    }
  }

  /// A building was built, by anyone. If it's one we just placed ourselves
  /// and the build call hasn't answered yet, it's already on screen without
  /// an id - adopt that one instead of drawing a second copy.
  Future<void> addBuilding(BuildingRecord record) async {
    final all = <PositionComponent>[...ports, ...lumberjacks];
    if (all.any((b) => _backendIdOf(b) == record.id)) return;

    final position = Vector2(record.x, record.y);
    final pending = switch (record.type) {
      BuildingType.port => ports
          .where((p) => p.backendId == null && p.position.distanceTo(position) < 0.01)
          .firstOrNull,
      BuildingType.lumberjack => lumberjacks
          .where((l) => l.backendId == null && l.position.distanceTo(position) < 0.01)
          .firstOrNull,
    };
    if (pending is PortComponent) {
      pending.backendId = record.id;
    } else if (pending is LumberjackComponent) {
      pending
        ..backendId = record.id
        ..applyStorage(record);
    } else {
      await _spawnBuildingFromRecord(record);
    }
  }

  void removeBuilding(int buildingId) {
    for (final port in ports.where((p) => p.backendId == buildingId).toList()) {
      ports.remove(port);
      port.removeFromParent();
    }
    for (final lumberjack in lumberjacks.where((l) => l.backendId == buildingId).toList()) {
      lumberjacks.remove(lumberjack);
      lumberjack.removeFromParent();
    }
  }

  static int? _backendIdOf(PositionComponent building) => switch (building) {
        PortComponent p => p.backendId,
        LumberjackComponent l => l.backendId,
        _ => null,
      };

  /// Brings the buildings in line with a fresh snapshot: adds anything built
  /// since, and removes anything destroyed since.
  Future<void> syncBuildings(List<BuildingRecord> latest) async {
    final latestIds = latest.map((b) => b.id).toSet();

    // Buildings with no backendId yet are still mid-placement (optimistic,
    // awaiting the create call's response) - leave them alone regardless of
    // what the backend currently reports.
    for (final port
        in ports.where((p) => p.backendId != null && !latestIds.contains(p.backendId)).toList()) {
      ports.remove(port);
      port.removeFromParent();
    }
    for (final lumberjack in lumberjacks
        .where((l) => l.backendId != null && !latestIds.contains(l.backendId))
        .toList()) {
      lumberjacks.remove(lumberjack);
      lumberjack.removeFromParent();
    }

    for (final record in latest) {
      await addBuilding(record);
    }
  }

  Future<void> _upsertRemotePlayer(RemotePlayerRecord record) async {
    if (record.userId == userId) return;
    var remote = _remotePlayers[record.userId];
    if (remote == null) {
      remote = PlayerComponent(
        islandRadius: islandRadius,
        username: record.username,
        ownerColor: record.color,
      );
      _remotePlayers[record.userId] = remote;
      await islandWorld.add(remote);
    }

    remote
      ..boardedBoat = null
      ..stop()
      ..position = Vector2(record.x, record.y);
    final boatId = record.boatId;
    final movement = record.movement;
    if (boatId != null) {
      remote.boardedBoat = _boatById(boatId);
    } else if (movement != null) {
      remote.follow(movement);
    }
  }

  /// Another player entered the world.
  Future<void> addRemotePlayer(RemotePlayerRecord record) => _upsertRemotePlayer(record);

  /// Another player left the world.
  void removeRemotePlayer(int userId) {
    _remotePlayers.remove(userId)?.removeFromParent();
  }

  /// Another player started walking.
  void moveRemotePlayer(int userId, MovementRecord movement) {
    final remote = _remotePlayers[userId];
    if (remote == null) return;
    remote.boardedBoat = null;
    remote.follow(movement);
  }

  /// Another player stepped aboard one of their boats - from now on they
  /// ride along with it, exactly like our own player does.
  void boardRemotePlayer(int userId, int boatId) {
    final remote = _remotePlayers[userId];
    final boat = _boatById(boatId);
    if (remote == null || boat == null) return;
    remote
      ..stop()
      ..boardedBoat = boat;
  }

  /// Another player stepped off their boat onto an island.
  /// This player died and woke up at [position] on the home island.
  void respawnLocalPlayer(Vector2 position) {
    _stopGathering();
    player.boardedBoat?.isSelected = false;
    player
      ..boardedBoat = null
      ..stop()
      ..position = position;
    currentPlayerIsland = _islandAt(position) ?? homeIsland;
    _isFreeLooking = false;
  }

  /// Another player died and came back at [position].
  void respawnRemotePlayer(int userId, Vector2 position) {
    _remotePlayers[userId]
      ?..boardedBoat = null
      ..stop()
      ..position = position;
  }

  void landRemotePlayer(int userId, Vector2 position) {
    final remote = _remotePlayers[userId];
    if (remote == null) return;
    remote
      ..boardedBoat = null
      ..position = position;
  }

  /// Brings the other players in line with a fresh snapshot.
  Future<void> applyRemotePlayers(List<RemotePlayerRecord> latest) async {
    final latestIds = latest.map((p) => p.userId).toSet();
    for (final id in _remotePlayers.keys.where((id) => !latestIds.contains(id)).toList()) {
      removeRemotePlayer(id);
    }
    for (final record in latest) {
      await _upsertRemotePlayer(record);
    }
  }
}
