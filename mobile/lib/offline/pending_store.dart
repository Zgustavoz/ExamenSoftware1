import 'pending_op.dart';

/// Dónde se guardan las órdenes pendientes. En el móvil es SQLite; en las pruebas, memoria.
abstract class PendingStore {
  /// Guarda una orden nueva y la devuelve con su `id`.
  Future<PendingOp> add(PendingOp op);

  Future<void> update(PendingOp op);

  Future<void> delete(int id);

  /// Todas las órdenes de un usuario, de la más antigua a la más nueva.
  Future<List<PendingOp>> all(String owner);

  /// Una orden que se quedó `syncing` porque la app se cerró a mitad del envío no está enviándose: vuelve a
  /// `pending` para que se reintente.
  Future<void> recoverInterrupted();

  /// Conserva solo las últimas [keep] órdenes ya enviadas, para que el historial no crezca sin límite.
  Future<void> pruneDone(String owner, {int keep = 20});
}

/// Almacén en memoria: para las pruebas.
class MemoryPendingStore implements PendingStore {
  final List<PendingOp> _ops = [];
  int _next = 1;

  @override
  Future<PendingOp> add(PendingOp op) async {
    final saved = op.copyWith(id: _next++);
    _ops.add(saved);
    return saved;
  }

  @override
  Future<void> update(PendingOp op) async {
    final i = _ops.indexWhere((o) => o.id == op.id);
    if (i >= 0) _ops[i] = op;
  }

  @override
  Future<void> delete(int id) async => _ops.removeWhere((o) => o.id == id);

  @override
  Future<List<PendingOp>> all(String owner) async => [..._ops.where((o) => o.owner == owner)];

  @override
  Future<void> recoverInterrupted() async {
    for (final o in _ops.where((o) => o.status == PendingStatus.syncing)) {
      o.status = PendingStatus.pending;
    }
  }

  @override
  Future<void> pruneDone(String owner, {int keep = 20}) async {
    final done = _ops.where((o) => o.owner == owner && o.status == PendingStatus.done).toList();
    for (final o in done.take((done.length - keep).clamp(0, done.length))) {
      _ops.remove(o);
    }
  }
}
