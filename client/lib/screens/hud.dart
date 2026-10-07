import 'package:flutter/material.dart';

import '../island_game.dart';
import '../protocol.dart';

/// Shown while the live connection to the server is down.
class ReconnectingBanner extends StatelessWidget {
  const ReconnectingBanner({super.key});

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Colors.orange.shade800,
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
            ),
            SizedBox(width: 8),
            Text('Reconnecting...', style: TextStyle(color: Colors.white)),
          ],
        ),
      ),
    );
  }
}

/// Shown instead of a building's actions when it belongs to someone else.
class NotYoursTile extends StatelessWidget {
  final String building;

  const NotYoursTile({super.key, required this.building});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.lock_outline),
      title: Text("Another player's $building"),
      subtitle: const Text('Only its owner can use or remove it'),
    );
  }
}

class ZoomControls extends StatelessWidget {
  final IslandGame game;

  const ZoomControls({super.key, required this.game});

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

class CoordinatesHud extends StatelessWidget {
  final double x;
  final double y;

  const CoordinatesHud({super.key, required this.x, required this.y});

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

/// Health and hunger, top left.
class VitalsHud extends StatelessWidget {
  final PlayerState island;

  const VitalsHud({super.key, required this.island});

  @override
  Widget build(BuildContext context) {
    final healthLow = island.health <= 25;
    return Card(
      color: Colors.black.withValues(alpha: 0.55),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: SizedBox(
          width: 160,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _Bar(
                label: island.healthChangePerSecond > 0
                    ? 'Health ${island.health.ceil()} (healing)'
                    : 'Health ${island.health.ceil()}',
                fraction: island.health / PlayerState.maxHealth,
                color: healthLow ? Colors.redAccent : Colors.pinkAccent,
                warning: healthLow,
              ),
              const SizedBox(height: 8),
              _Bar(
                label: island.isStarving ? 'Starving! Eat some berries' : 'Hunger ${island.hunger.ceil()}',
                fraction: island.hunger / PlayerState.maxHunger,
                color: island.hunger <= 25 ? Colors.redAccent : Colors.lightGreenAccent,
                warning: island.isStarving,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Bar extends StatelessWidget {
  final String label;
  final double fraction;
  final Color color;
  final bool warning;

  const _Bar({required this.label, required this.fraction, required this.color, this.warning = false});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: TextStyle(
            color: warning ? Colors.redAccent : Colors.white,
            fontSize: 12,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: LinearProgressIndicator(
            value: fraction,
            minHeight: 8,
            color: color,
            backgroundColor: Colors.white24,
          ),
        ),
      ],
    );
  }
}
