import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import 'pending_op.dart';
import 'pending_store.dart';

/// Órdenes pendientes en la base SQLite del dispositivo: sobreviven a que se cierre la app o se apague el
/// móvil.
class SqlitePendingStore implements PendingStore {
  SqlitePendingStore._(this._db);

  final Database _db;

  static const _table = 'pending_ops';

  /// Abre (y crea la primera vez) la base. [factory] y [path] solo se cambian en las pruebas.
  static Future<SqlitePendingStore> open({DatabaseFactory? factory, String? path}) async {
    final f = factory ?? databaseFactory;
    final file = path ?? p.join(await f.getDatabasesPath(), 'diagramas_offline.db');
    final db = await f.openDatabase(
      file,
      options: OpenDatabaseOptions(
        version: 1,
        onCreate: (db, _) => db.execute('''
          CREATE TABLE $_table (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner TEXT NOT NULL,
            diagram_id TEXT NOT NULL,
            diagram_name TEXT NOT NULL,
            instruction TEXT NOT NULL,
            status TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            error TEXT,
            result_json TEXT,
            created_at TEXT NOT NULL
          )
        '''),
      ),
    );
    return SqlitePendingStore._(db);
  }

  Future<void> close() => _db.close();

  @override
  Future<PendingOp> add(PendingOp op) async {
    final id = await _db.insert(_table, _toRow(op));
    return op.copyWith(id: id);
  }

  @override
  Future<void> update(PendingOp op) async {
    await _db.update(_table, _toRow(op), where: 'id = ?', whereArgs: [op.id]);
  }

  @override
  Future<void> delete(int id) async {
    await _db.delete(_table, where: 'id = ?', whereArgs: [id]);
  }

  @override
  Future<List<PendingOp>> all(String owner) async {
    // El orden de creación manda: `id` crece siempre, así que es más fiable que la fecha.
    final rows = await _db.query(_table, where: 'owner = ?', whereArgs: [owner], orderBy: 'id ASC');
    return rows.map(_fromRow).toList();
  }

  @override
  Future<void> recoverInterrupted() async {
    await _db.update(
      _table,
      {'status': PendingStatus.pending.name},
      where: 'status = ?',
      whereArgs: [PendingStatus.syncing.name],
    );
  }

  @override
  Future<void> pruneDone(String owner, {int keep = 20}) async {
    await _db.delete(
      _table,
      where: 'owner = ? AND status = ? AND id NOT IN ('
          'SELECT id FROM $_table WHERE owner = ? AND status = ? ORDER BY id DESC LIMIT ?)',
      whereArgs: [owner, PendingStatus.done.name, owner, PendingStatus.done.name, keep],
    );
  }

  Map<String, Object?> _toRow(PendingOp op) => {
    'owner': op.owner,
    'diagram_id': op.diagramId,
    'diagram_name': op.diagramName,
    'instruction': op.instruction,
    'status': op.status.name,
    'attempts': op.attempts,
    'error': op.error,
    'result_json': op.result == null ? null : jsonEncode(op.result),
    'created_at': op.createdAt.toIso8601String(),
  };

  PendingOp _fromRow(Map<String, Object?> row) => PendingOp(
    id: row['id'] as int,
    owner: row['owner'] as String,
    diagramId: row['diagram_id'] as String,
    diagramName: row['diagram_name'] as String,
    instruction: row['instruction'] as String,
    status: PendingStatus.values.byName(row['status'] as String),
    attempts: row['attempts'] as int,
    error: row['error'] as String?,
    result: row['result_json'] == null
        ? null
        : (jsonDecode(row['result_json'] as String) as Map).cast<String, Object?>(),
    createdAt: DateTime.parse(row['created_at'] as String),
  );
}
