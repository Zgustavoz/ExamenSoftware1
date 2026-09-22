import 'dart:io';

import 'package:diagramas_movil/offline/pending_op.dart';
import 'package:diagramas_movil/offline/sqlite_pending_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

PendingOp _op(String instruction, {String owner = 'demo/designer', PendingStatus status = PendingStatus.pending}) =>
    PendingOp(owner: owner, diagramId: 'd1', diagramName: 'Ventas · Dominio', instruction: instruction, status: status);

/// Prueba el almacén contra SQLite de verdad (no una imitación): es lo que guarda las órdenes en el móvil.
void main() {
  sqfliteFfiInit();

  late Directory carpeta;
  late String archivo;
  late SqlitePendingStore store;

  setUp(() async {
    carpeta = await Directory.systemTemp.createTemp('pending_ops_test');
    archivo = p.join(carpeta.path, 'offline.db');
    store = await SqlitePendingStore.open(factory: databaseFactoryFfi, path: archivo);
  });

  tearDown(() async {
    await store.close();
    await carpeta.delete(recursive: true);
  });

  test('guarda una orden y la devuelve con todos sus datos', () async {
    final guardada = await store.add(_op('registra un cliente con nombre Juan'));

    expect(guardada.id, isNotNull);
    final leida = (await store.all('demo/designer')).single;
    expect(leida.id, guardada.id);
    expect(leida.instruction, 'registra un cliente con nombre Juan');
    expect(leida.diagramId, 'd1');
    expect(leida.diagramName, 'Ventas · Dominio');
    expect(leida.status, PendingStatus.pending);
    expect(leida.attempts, 0);
    expect(leida.result, isNull);
  });

  test('las órdenes sobreviven a cerrar y volver a abrir la base (como cerrar la app)', () async {
    await store.add(_op('sobrevive'));
    await store.close();

    store = await SqlitePendingStore.open(factory: databaseFactoryFfi, path: archivo);

    expect((await store.all('demo/designer')).map((o) => o.instruction), ['sobrevive']);
  });

  test('devuelve las órdenes en el orden en que se dictaron', () async {
    for (final o in ['uno', 'dos', 'tres']) {
      await store.add(_op(o));
    }

    expect((await store.all('demo/designer')).map((o) => o.instruction), ['uno', 'dos', 'tres']);
  });

  test('actualiza el estado, los intentos, el error y la respuesta del servidor', () async {
    final op = await store.add(_op('a'));
    op
      ..status = PendingStatus.done
      ..attempts = 3
      ..error = 'antes falló'
      ..result = {'explanation': 'Registré un Cliente.', 'method': 'POST', 'path': '/api/clientes', 'status': 201, 'usedAi': false};

    await store.update(op);

    final leida = (await store.all('demo/designer')).single;
    expect(leida.status, PendingStatus.done);
    expect(leida.attempts, 3);
    expect(leida.error, 'antes falló');
    expect(leida.result, {
      'explanation': 'Registré un Cliente.',
      'method': 'POST',
      'path': '/api/clientes',
      'status': 201,
      'usedAi': false,
    });
  });

  test('cada usuario ve solo sus órdenes', () async {
    await store.add(_op('mía'));
    await store.add(_op('de otro', owner: 'demo/otro'));

    expect((await store.all('demo/designer')).map((o) => o.instruction), ['mía']);
    expect((await store.all('demo/otro')).map((o) => o.instruction), ['de otro']);
  });

  test('borra una orden', () async {
    final op = await store.add(_op('a'));
    await store.add(_op('b'));

    await store.delete(op.id!);

    expect((await store.all('demo/designer')).map((o) => o.instruction), ['b']);
  });

  test('una orden que quedó «enviándose» vuelve a pendiente; las demás no se tocan', () async {
    await store.add(_op('a', status: PendingStatus.syncing));
    await store.add(_op('b', status: PendingStatus.done));
    await store.add(_op('c', status: PendingStatus.failed));

    await store.recoverInterrupted();

    expect(
      (await store.all('demo/designer')).map((o) => o.status),
      [PendingStatus.pending, PendingStatus.done, PendingStatus.failed],
    );
  });

  test('conserva solo las últimas enviadas y respeta las pendientes y las de otros', () async {
    for (var i = 0; i < 5; i++) {
      await store.add(_op('hecha $i', status: PendingStatus.done));
    }
    await store.add(_op('pendiente'));
    await store.add(_op('hecha de otro', owner: 'demo/otro', status: PendingStatus.done));

    await store.pruneDone('demo/designer', keep: 2);

    final mias = await store.all('demo/designer');
    expect(mias.map((o) => o.instruction), ['hecha 3', 'hecha 4', 'pendiente']);
    expect(await store.all('demo/otro'), hasLength(1));
  });
}
