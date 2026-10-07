import 'dart:async';

import 'package:flutter/material.dart';

import '../api.dart';
import 'island_screen.dart';

/// Getting into the game in two steps: first who you are, then which
/// world - with each world showing its settings and who's in it.
class WelcomeScreen extends StatefulWidget {
  /// Tests pass a fake; normally it talks to the real backend.
  final IslandApi? api;

  const WelcomeScreen({super.key, this.api});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  late final IslandApi _api = widget.api ?? IslandApi();
  final TextEditingController _newUserController = TextEditingController();
  final TextEditingController _newWorldController = TextEditingController();

  List<UserData>? _users;
  List<WorldData>? _worlds;
  String? _error;

  /// Picked in step 1; step 2 (choosing a world) shows once it's set.
  UserData? _user;

  bool _creatingUser = false;
  bool _creatingWorld = false;

  /// Keeps "who's playing" up to date, for both users and worlds.
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _refresh(firstLoad: true);
    _refreshTimer = Timer.periodic(const Duration(seconds: 3), (_) => _refresh());
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _newUserController.dispose();
    _newWorldController.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool firstLoad = false}) async {
    try {
      final users = await _api.fetchUsers();
      final worlds = await _api.fetchWorlds();
      if (!mounted) return;
      setState(() {
        _users = users;
        _worlds = worlds;
        _error = null;
        // Someone else started playing as the user we'd picked.
        final picked = _user;
        if (picked != null && users.any((u) => u.id == picked.id && u.playing)) {
          _user = null;
          _notice('Someone else is now playing as ${picked.name}');
        }
      });
    } catch (e) {
      // Keep showing the last lists; the next tick tries again.
      if (firstLoad && mounted) setState(() => _error = 'Could not reach backend: $e');
    }
  }

  void _notice(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
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
        _user = user;
      });
    } catch (e) {
      if (mounted) _notice('Could not create user: $e');
    } finally {
      if (mounted) setState(() => _creatingUser = false);
    }
  }

  Future<void> _createWorld() async {
    final name = _newWorldController.text.trim();
    if (name.isEmpty) return;
    final settings = await showDialog<WorldSettings>(
      context: context,
      builder: (_) => _WorldSettingsDialog(title: 'New world: $name', confirmLabel: 'Create world'),
    );
    if (settings == null) return; // cancelled
    setState(() => _creatingWorld = true);
    try {
      final world = await _api.createWorld(name, settings);
      _newWorldController.clear();
      setState(() => _worlds = [...?_worlds, world]);
    } catch (e) {
      if (mounted) _notice('Could not create world: $e');
    } finally {
      if (mounted) setState(() => _creatingWorld = false);
    }
  }

  Future<void> _editSettings(WorldData world) async {
    final settings = await showDialog<WorldSettings>(
      context: context,
      builder: (_) => _WorldSettingsDialog(
        title: 'Settings: ${world.name}',
        confirmLabel: 'Save',
        initial: world.settings,
      ),
    );
    if (settings == null) return;
    try {
      final updated = await _api.updateWorldSettings(world.id, settings);
      setState(() => _worlds = [for (final w in _worlds ?? <WorldData>[]) w.id == updated.id ? updated : w]);
    } catch (e) {
      if (mounted) _notice('Could not change settings: $e');
      await _refresh();
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
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Cancel')),
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
      setState(() => _worlds = _worlds?.where((w) => w.id != world.id).toList());
    } catch (e) {
      if (mounted) _notice('Could not delete world: $e');
    }
  }

  Future<void> _enter(WorldData world) async {
    final user = _user;
    if (user == null) return;
    await Navigator.of(context).push(
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
    // Back from the world: who's playing where has changed.
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final user = _user;
    return Scaffold(
      appBar: AppBar(
        title: Text(user == null ? 'Welcome' : 'Choose a world'),
        leading: user == null
            ? null
            : IconButton(
                tooltip: 'Pick someone else',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => setState(() => _user = null),
              ),
      ),
      body: _users == null || _worlds == null
          ? Center(
              child: _error != null
                  ? Padding(padding: const EdgeInsets.all(24), child: Text(_error!, textAlign: TextAlign.center))
                  : const CircularProgressIndicator(),
            )
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: user == null ? _buildUserStep() : _buildWorldStep(user),
                ),
              ),
            ),
    );
  }

  /// Step 1: who are you?
  Widget _buildUserStep() {
    return _PickerSection<UserData>(
      title: 'Who are you?',
      items: _users!,
      selected: null,
      labelOf: (u) => u.name,
      idOf: (u) => u.id,
      unavailableReasonOf: (u) => u.playing ? 'playing' : null,
      colorOf: (u) => u.color,
      onSelect: (u) => setState(() => _user = u),
      newItemController: _newUserController,
      newItemHint: 'New username',
      creating: _creatingUser,
      onCreate: _createUser,
    );
  }

  /// Step 2: which world?
  Widget _buildWorldStep(UserData user) {
    final worlds = _worlds!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            CircleAvatar(radius: 10, backgroundColor: user.color),
            const SizedBox(width: 8),
            Expanded(child: Text('Playing as ${user.name}', style: Theme.of(context).textTheme.titleMedium)),
            TextButton(onPressed: () => setState(() => _user = null), child: const Text('Change')),
          ],
        ),
        const SizedBox(height: 12),
        if (worlds.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('No worlds yet - create one below.'),
          ),
        for (final world in worlds)
          _WorldCard(
            world: world,
            onEnter: () => _enter(world),
            onEditSettings: world.started ? null : () => _editSettings(world),
            onDelete: world.playing.isEmpty ? () => _confirmDeleteWorld(world) : null,
          ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _newWorldController,
                decoration: const InputDecoration(
                  hintText: 'New world name',
                  isDense: true,
                  border: OutlineInputBorder(),
                ),
                onSubmitted: (_) => _createWorld(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton.icon(
              onPressed: _creatingWorld ? null : _createWorld,
              icon: const Icon(Icons.add),
              label: const Text('Create'),
            ),
          ],
        ),
      ],
    );
  }
}

/// One world in step 2: its settings, who's in it, and what you can do.
class _WorldCard extends StatelessWidget {
  final WorldData world;
  final VoidCallback onEnter;

  /// Null once the world has started - its settings are fixed then.
  final VoidCallback? onEditSettings;

  /// Null while someone is playing in it.
  final VoidCallback? onDelete;

  const _WorldCard({required this.world, required this.onEnter, this.onEditSettings, this.onDelete});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text(world.name, style: theme.textTheme.titleMedium)),
                _StatusChip(world: world),
              ],
            ),
            const SizedBox(height: 4),
            Text(world.settings.summary, style: theme.textTheme.bodySmall),
            if (world.playing.isNotEmpty) ...[
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [
                  for (final player in world.playing)
                    Chip(
                      visualDensity: VisualDensity.compact,
                      avatar: CircleAvatar(backgroundColor: player.color),
                      label: Text(player.name),
                    ),
                ],
              ),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                FilledButton(onPressed: onEnter, child: const Text('Enter')),
                const SizedBox(width: 8),
                if (onEditSettings != null)
                  OutlinedButton.icon(
                    onPressed: onEditSettings,
                    icon: const Icon(Icons.tune, size: 18),
                    label: const Text('Edit settings'),
                  ),
                const Spacer(),
                IconButton(
                  tooltip: onDelete == null ? "Can't delete while someone is playing" : 'Delete world',
                  onPressed: onDelete,
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final WorldData world;

  const _StatusChip({required this.world});

  @override
  Widget build(BuildContext context) {
    final (label, color) = !world.started
        ? ('Not started yet', Colors.blue)
        : world.playing.isEmpty
            ? ('Nobody playing', Colors.grey)
            : ('${world.playing.length} playing', Colors.green);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(label, style: TextStyle(color: color.shade700, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

class _PickerSection<T> extends StatelessWidget {
  final String title;
  final List<T> items;
  final T? selected;
  final String Function(T) labelOf;

  /// Items are compared by this, since the list is reloaded now and then.
  final Object Function(T) idOf;

  /// Why an item can't be picked right now, if it can't - shown on its chip.
  final String? Function(T)? unavailableReasonOf;

  /// Extra information shown when hovering over an item.
  final String Function(T)? detailOf;
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
    required this.idOf,
    this.unavailableReasonOf,
    this.detailOf,
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
                  final picked = selected;
                  final isSelected = picked != null && idOf(picked) == idOf(item);
                  final unavailable = unavailableReasonOf?.call(item);
                  return InputChip(
                    avatar: colorOf != null
                        ? CircleAvatar(backgroundColor: colorOf!(item))
                        : null,
                    label: Text(unavailable == null ? labelOf(item) : '${labelOf(item)} ($unavailable)'),
                    selected: isSelected,
                    isEnabled: unavailable == null,
                    tooltip: unavailable != null
                        ? '${labelOf(item)} is $unavailable right now'
                        : detailOf?.call(item),
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

/// A world's settings: asked when creating one, and editable until someone
/// has played in it.
class _WorldSettingsDialog extends StatefulWidget {
  final String title;
  final String confirmLabel;
  final WorldSettings initial;

  const _WorldSettingsDialog({
    required this.title,
    required this.confirmLabel,
    this.initial = const WorldSettings(),
  });

  @override
  State<_WorldSettingsDialog> createState() => _WorldSettingsDialogState();
}

class _WorldSettingsDialogState extends State<_WorldSettingsDialog> {
  late WorldSettings _settings = widget.initial;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Animal aggression'),
            subtitle: Text(_settings.animalAggression ? 'Boars attack players' : 'Animals never attack'),
            value: _settings.animalAggression,
            onChanged: (value) => setState(() => _settings =
                WorldSettings(animalAggression: value, inventoryOnDeath: _settings.inventoryOnDeath)),
          ),
          const SizedBox(height: 12),
          const Text('When you die, your bag is...'),
          const SizedBox(height: 8),
          SegmentedButton<InventoryOnDeath>(
            segments: const [
              ButtonSegment(value: InventoryOnDeath.keep, label: Text('Kept')),
              ButtonSegment(value: InventoryOnDeath.drop, label: Text('Dropped')),
              ButtonSegment(value: InventoryOnDeath.lose, label: Text('Lost')),
            ],
            selected: {_settings.inventoryOnDeath},
            onSelectionChanged: (choice) => setState(() => _settings =
                WorldSettings(animalAggression: _settings.animalAggression, inventoryOnDeath: choice.single)),
          ),
          const SizedBox(height: 8),
          Text(
            '${switch (_settings.inventoryOnDeath) {
              InventoryOnDeath.keep => 'Nothing is lost.',
              InventoryOnDeath.drop => 'Left in a pile where you died - go back for it before it disappears.',
              InventoryOnDeath.lose => 'Gone for good.',
            }} Your quick-access bar is always kept.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          Text(
            'Can be changed until someone plays this world.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, _settings), child: Text(widget.confirmLabel)),
      ],
    );
  }
}
