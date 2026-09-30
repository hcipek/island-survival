import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:web_socket_channel/web_socket_channel.dart';

/// A random id for this browser tab, so the server can tell a reconnect of
/// this tab from someone else playing as the same user.
///
/// Built from small random numbers on purpose: on the web, Dart numbers are
/// JavaScript numbers and bit shifts wrap at 32 bits - `1 << 32` is 0 there,
/// so `Random().nextInt(1 << 32)` throws.
String newClientId() {
  final random = Random();
  return List.generate(4, (_) => random.nextInt(0x10000).toRadixString(16).padLeft(4, '0')).join();
}

/// The live link to one world on the backend. Delivers every event the
/// server pushes to [onEvent], and reconnects by itself (with growing
/// pauses) if the connection drops. The server sends a fresh `snapshot`
/// after every (re)connect, so nothing missed while offline is lost.
class LiveConnection {
  final Uri url;
  final void Function(Map<String, dynamic> event) onEvent;

  /// Called when the connection opens or drops, e.g. to show a banner.
  final void Function(bool connected)? onStatusChanged;

  LiveConnection({required this.url, required this.onEvent, this.onStatusChanged});

  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  Timer? _retryTimer;
  int _failedAttempts = 0;
  bool _connected = false;
  bool _closed = false;

  bool get isConnected => _connected;

  Future<void> connect() async {
    if (_closed) return;
    final channel = WebSocketChannel.connect(url);
    _channel = channel;
    try {
      await channel.ready;
    } catch (_) {
      _scheduleReconnect();
      return;
    }
    if (_closed) {
      channel.sink.close();
      return;
    }

    _failedAttempts = 0;
    _setConnected(true);
    _subscription = channel.stream.listen(
      (data) {
        final decoded = jsonDecode(data as String);
        if (decoded is Map<String, dynamic>) onEvent(decoded);
      },
      onDone: _scheduleReconnect,
      onError: (_) => _scheduleReconnect(),
      cancelOnError: true,
    );
  }

  /// Sends [message] if connected. Dropped otherwise - whatever it changed
  /// is re-sent on reconnect (see the screen's snapshot handling).
  void send(Map<String, dynamic> message) {
    if (!_connected) return;
    _channel?.sink.add(jsonEncode(message));
  }

  void close() {
    _closed = true;
    _retryTimer?.cancel();
    _subscription?.cancel();
    _channel?.sink.close();
    _setConnected(false);
  }

  void _scheduleReconnect() {
    _subscription?.cancel();
    _subscription = null;
    _setConnected(false);
    if (_closed || (_retryTimer?.isActive ?? false)) return;
    // 0.5s, 1s, 2s, 4s... capped at 8s.
    final delayMs = 500 * pow(2, min(_failedAttempts, 4)).toInt();
    _failedAttempts++;
    _retryTimer = Timer(Duration(milliseconds: delayMs), connect);
  }

  void _setConnected(bool connected) {
    if (_connected == connected) return;
    _connected = connected;
    onStatusChanged?.call(connected);
  }
}
