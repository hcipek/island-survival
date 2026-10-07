import 'dart:async';

import 'package:flutter/material.dart';

import '../api.dart';
import 'island_screen.dart';

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

  /// Keeps "who's playing" up to date, so a user frees up here as soon as
  /// its player leaves.
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _loadAll();
    _refreshTimer = Timer.periodic(const Duration(seconds: 3), (_) => _refreshUsers());
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _newUserController.dispose();
    _newWorldController.dispose();
    super.dispose();
  }

  Future<void> _refreshUsers() async {
    try {
      final users = await _api.fetchUsers();
      if (!mounted) return;
      setState(() {
        _users = users;
        // Someone else started playing as the user we'd picked.
        final picked = _selectedUser;
        if (picked != null && users.any((u) => u.id == picked.id && u.playing)) {
          _selectedUser = null;
        }
      });
    } catch (_) {
      // Keep showing the last list; the next tick tries again.
    }
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
    final settings = await showDialog<WorldSettings>(
      context: context,
      builder: (_) => _WorldSettingsDialog(worldName: name),
    );
    if (settings == null) return; // cancelled
    setState(() => _creatingWorld = true);
    try {
      final world = await _api.createWorld(name, settings);
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

  Future<void> _enter() async {
    final user = _selectedUser;
    final world = _selectedWorld;
    if (user == null || world == null) return;
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
    // Back from the world: the list of who's playing has changed.
    await _refreshUsers();
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
                        idOf: (u) => u.id,
                        unavailableReasonOf: (u) => u.playing ? 'playing' : null,
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
                        detailOf: (w) => w.settings.summary,
                        idOf: (w) => w.id,
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

/// Asked when creating a world: its settings, which can't be changed later.
class _WorldSettingsDialog extends StatefulWidget {
  final String worldName;

  const _WorldSettingsDialog({required this.worldName});

  @override
  State<_WorldSettingsDialog> createState() => _WorldSettingsDialogState();
}

class _WorldSettingsDialogState extends State<_WorldSettingsDialog> {
  WorldSettings _settings = const WorldSettings();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('New world: ${widget.worldName}'),
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
          Text('These can\'t be changed later.', style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, _settings), child: const Text('Create world')),
      ],
    );
  }
}
