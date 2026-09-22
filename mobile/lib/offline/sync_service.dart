import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api.dart';
import 'connectivity_monitor.dart';
import 'pending_op.dart';
import 'pending_store.dart';

/// Quién está usando la app: el token para enviar y el dueño de las órdenes guardadas.
class Session {
  const Session({required this.token, required this.owner});

  final String token;

  /// `empresa/usuario`.
  final String owner;

  @override
  bool operator ==(Object other) => other is Session && other.token == token && other.owner == owner;

  @override
  int get hashCode => Object.hash(token, owner);
}

/// Lo que pasó con una orden al pedir que se enviara: se ejecutó ya, o quedó guardada para más tarde.
class SubmitOutcome {
  const SubmitOutcome.sent(CommandResult this.result) : queued = null;
  const SubmitOutcome.queued(PendingOp this.queued) : result = null;

  final CommandResult? result;
  final PendingOp? queued;
}

/// Modo offline (CU-18, reducido a lo que el móvil hace: dictar órdenes).
///
/// - Si no hay conexión, o el envío falla por la red, la orden se guarda en la base local del dispositivo.
/// - Al volver la conexión —o al reintentar cada cierto tiempo, o a mano— se envían **en el orden en que se
///   dictaron**, de una en una.
/// - Un fallo de red o del servidor detiene el envío y deja lo que queda como pendiente. Un rechazo del
///   servidor («no entiendo la orden») marca esa orden como fallida y sigue con la siguiente, porque
///   reintentarla daría lo mismo.
class SyncService extends ChangeNotifier {
  SyncService({
    required this.api,
    required this.store,
    ConnectivityMonitor? connectivity,
    this.retryEvery = const Duration(seconds: 20),
  }) : connectivity = connectivity ?? const AlwaysOnline();

  final Api api;
  final PendingStore store;
  final ConnectivityMonitor connectivity;

  /// Cada cuánto reintenta mientras haya órdenes pendientes. `null` lo desactiva (pruebas).
  final Duration? retryEvery;

  /// Errores que se arreglan solos con el tiempo: la orden se conserva y se reintenta.
  static const _reintentables = {'NETWORK_ERROR', 'AI_UNAVAILABLE', 'AI_TIMEOUT', 'INTERNAL_ERROR'};

  bool online = true;
  bool syncing = false;

  /// El servidor rechazó el token: las órdenes siguen guardadas, pero hay que volver a entrar para enviarlas.
  bool sessionExpired = false;

  /// Las órdenes del usuario actual, de la más antigua a la más nueva.
  List<PendingOp> ops = const [];

  /// Se avisa con cuántas órdenes se enviaron cuando termina un envío en segundo plano.
  void Function(int enviadas)? onSynced;

  Session? _session;
  StreamSubscription<bool>? _subscription;
  Timer? _timer;
  bool _disposed = false;

  int get pendingCount => ops.where((o) => o.status == PendingStatus.pending || o.status == PendingStatus.syncing).length;

  Future<void> start() async {
    await store.recoverInterrupted();
    online = await connectivity.isOnline();
    _subscription = connectivity.changes.listen((hayRed) {
      online = hayRed;
      _notify();
      if (hayRed) unawaited(sync());
    });
    final cada = retryEvery;
    if (cada != null) {
      _timer = Timer.periodic(cada, (_) {
        // Con la sesión vencida, reintentar solo daría el mismo 401: hay que volver a entrar.
        if (online && !sessionExpired && pendingCount > 0) unawaited(sync());
      });
    }
    _notify();
  }

  /// Al entrar: se cargan las órdenes de este usuario y se envían las que hubieran quedado pendientes.
  Future<void> signIn(Session session) async {
    _session = session;
    sessionExpired = false;
    await _reload();
    if (online) unawaited(sync());
  }

  void signOut() {
    _session = null;
    ops = const [];
    _notify();
  }

  /// Envía la orden ya, o la guarda si no se puede. Un rechazo del servidor se lanza como [ApiError]: esa
  /// orden no se guarda, porque hay que corregirla, no esperar.
  Future<SubmitOutcome> submit({
    required String diagramId,
    required String diagramName,
    required String instruction,
  }) async {
    final session = _session;
    if (session == null) throw ApiError('UNAUTHORIZED', 'Vuelva a entrar para enviar la orden.');

    // Con órdenes anteriores esperando, la nueva va detrás: si se enviara primero, se ejecutaría en otro orden
    // del que el usuario las dictó.
    final hayCola = ops.any((o) => o.status == PendingStatus.pending || o.status == PendingStatus.syncing);
    if (online && !hayCola) {
      try {
        return SubmitOutcome.sent(await api.command(session.token, diagramId, instruction));
      } on ApiError catch (e) {
        if (!_esReintentable(e)) rethrow;
        return SubmitOutcome.queued(await _enqueue(session, diagramId, diagramName, instruction, intentos: 1, error: e.message));
      }
    }

    final op = await _enqueue(session, diagramId, diagramName, instruction);
    if (online) unawaited(sync());
    return SubmitOutcome.queued(op);
  }

  /// Envía las órdenes pendientes, en orden. Devuelve cuántas se ejecutaron.
  Future<int> sync() async {
    final session = _session;
    if (session == null || syncing) return 0;

    syncing = true;
    sessionExpired = false;
    _notify();

    var enviadas = 0;
    try {
      // Se vuelve a leer la lista por si mientras tanto se dictó otra orden.
      outer:
      while (_session == session) {
        final pendientes = (await store.all(session.owner)).where((o) => o.status == PendingStatus.pending).toList();
        if (pendientes.isEmpty) break;

        for (final op in pendientes) {
          if (_session != session) break outer;
          op
            ..status = PendingStatus.syncing
            ..attempts += 1;
          await store.update(op);
          await _reload();

          try {
            final r = await api.command(session.token, op.diagramId, op.instruction);
            op
              ..status = PendingStatus.done
              ..error = null
              ..result = {
                'explanation': r.explanation,
                'method': r.method,
                'path': r.path,
                'status': r.status,
                'usedAi': r.usedAi,
              };
            await store.update(op);
            enviadas++;
          } on ApiError catch (e) {
            op.error = e.message;
            if (e.code == 'UNAUTHORIZED') {
              // No fue un intento de verdad: el servidor ni llegó a mirar la orden.
              op.status = PendingStatus.pending;
              op.attempts -= 1;
              sessionExpired = true;
              await store.update(op);
              break outer;
            }
            if (_esReintentable(e)) {
              // Sin red o sin servidor: lo que queda detrás también fallaría, y hay que respetar el orden.
              op.status = PendingStatus.pending;
              await store.update(op);
              break outer;
            }
            op.status = PendingStatus.failed;
            await store.update(op);
          }
        }
      }
      await store.pruneDone(session.owner);
    } finally {
      syncing = false;
      await _reload();
    }

    if (enviadas > 0) onSynced?.call(enviadas);
    return enviadas;
  }

  /// Quita una orden de la lista del dispositivo (pendiente, rechazada o ya enviada).
  Future<void> discard(PendingOp op) async {
    if (op.id == null || op.status == PendingStatus.syncing) return;
    await store.delete(op.id!);
    await _reload();
  }

  static bool _esReintentable(ApiError e) => _reintentables.contains(e.code);

  Future<PendingOp> _enqueue(
    Session session,
    String diagramId,
    String diagramName,
    String instruction, {
    int intentos = 0,
    String? error,
  }) async {
    final op = await store.add(
      PendingOp(
        owner: session.owner,
        diagramId: diagramId,
        diagramName: diagramName,
        instruction: instruction,
        attempts: intentos,
        error: error,
      ),
    );
    await _reload();
    return op;
  }

  Future<void> _reload() async {
    final session = _session;
    ops = session == null ? const [] : await store.all(session.owner);
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _subscription?.cancel();
    _timer?.cancel();
    super.dispose();
  }
}
