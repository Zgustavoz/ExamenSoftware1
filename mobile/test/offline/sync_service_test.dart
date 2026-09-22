import 'dart:async';

import 'package:diagramas_movil/api.dart';
import 'package:diagramas_movil/offline/pending_op.dart';
import 'package:diagramas_movil/offline/pending_store.dart';
import 'package:diagramas_movil/offline/sync_service.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fakes.dart';

const _sesion = Session(token: 'jwt', owner: 'demo/designer');

void main() {
  late FakeApi api;
  late FakeConnectivity conexion;
  late MemoryPendingStore store;
  late SyncService sync;

  /// Un servicio con sesión iniciada, sin reintento automático (se prueba aparte).
  Future<SyncService> servicio({bool online = true}) async {
    conexion = FakeConnectivity(online: online);
    final s = SyncService(api: api, store: store, connectivity: conexion, retryEvery: null);
    await s.start();
    await s.signIn(_sesion);
    await pumpEventQueue();
    return s;
  }

  Future<SubmitOutcome> dictar(String orden) =>
      sync.submit(diagramId: 'd1', diagramName: 'Ventas · Dominio', instruction: orden);

  setUp(() {
    api = FakeApi();
    store = MemoryPendingStore();
  });

  tearDown(() => sync.dispose());

  group('con conexión', () {
    test('envía la orden al momento y no guarda nada', () async {
      sync = await servicio();

      final r = await dictar('registra un cliente con nombre Juan');

      expect(r.result?.status, 201);
      expect(r.queued, isNull);
      expect(api.enviadas, ['registra un cliente con nombre Juan']);
      expect(sync.ops, isEmpty);
    });

    test('un fallo de red al enviar la deja guardada en vez de perderla', () async {
      api.fallo = (_) => sinRed();
      sync = await servicio();

      final r = await dictar('registra un cliente con nombre Juan');

      expect(r.queued, isNotNull);
      expect(sync.ops, hasLength(1));
      expect(sync.ops.single.status, PendingStatus.pending);
      expect(sync.ops.single.attempts, 1);
      expect(sync.ops.single.error, contains('No se pudo conectar'));
    });

    test('el servidor caído (backend generado apagado) también se reintenta', () async {
      api.fallo = (_) => ApiError('AI_UNAVAILABLE', 'No se pudo contactar con el backend generado.');
      sync = await servicio();

      final r = await dictar('lista los clientes');

      expect(r.queued, isNotNull);
      expect(sync.ops.single.status, PendingStatus.pending);
    });

    test('un rechazo del servidor se le muestra al usuario y NO se guarda: reintentar daría lo mismo', () async {
      api.fallo = (_) => ApiError('VALIDATION_ERROR', 'Diga o escriba qué quiere registrar.');
      sync = await servicio();

      await expectLater(dictar('???'), throwsA(isA<ApiError>()));

      expect(sync.ops, isEmpty);
    });
  });

  group('sin conexión', () {
    test('guarda la orden en el dispositivo sin llamar al servidor', () async {
      sync = await servicio(online: false);

      final r = await dictar('registra un cliente con nombre Juan');

      expect(r.queued, isNotNull);
      expect(api.enviadas, isEmpty);
      final guardadas = await store.all(_sesion.owner);
      expect(guardadas.single.instruction, 'registra un cliente con nombre Juan');
      expect(guardadas.single.status, PendingStatus.pending);
    });

    test('al volver la conexión envía lo pendiente en el orden en que se dictó', () async {
      sync = await servicio(online: false);
      var avisadas = 0;
      sync.onSynced = (n) => avisadas = n;
      await dictar('primera');
      await dictar('segunda');
      await dictar('tercera');
      expect(api.enviadas, isEmpty);

      conexion.set(true);
      await pumpEventQueue();

      expect(api.enviadas, ['primera', 'segunda', 'tercera']);
      expect(sync.ops.map((o) => o.status), everyElement(PendingStatus.done));
      expect(sync.pendingCount, 0);
      expect(avisadas, 3);
    });

    test('lo enviado guarda la respuesta del servidor para enseñarla', () async {
      sync = await servicio(online: false);
      await dictar('registra un cliente con nombre Juan');

      conexion.set(true);
      await pumpEventQueue();

      final r = sync.ops.single.result!;
      expect(r['explanation'], 'Hecho: registra un cliente con nombre Juan');
      expect(r['method'], 'POST');
      expect(r['path'], '/api/clientes');
      expect(r['status'], 201);
    });

    test('si la red se cae de nuevo, lo que queda sigue pendiente y en orden', () async {
      sync = await servicio(online: false);
      await dictar('A');
      await dictar('B');
      await dictar('C');
      api.fallo = (o) => o == 'B' ? sinRed() : null;

      conexion.set(true);
      await pumpEventQueue();

      // A salió; B falló y C ni se intentó: enviarla antes rompería el orden.
      expect(api.enviadas, ['A']);
      expect(sync.ops.map((o) => o.status), [PendingStatus.done, PendingStatus.pending, PendingStatus.pending]);
      expect(sync.ops[1].attempts, 1);
      expect(sync.ops[2].attempts, 0);

      api.fallo = null;
      await sync.sync();

      expect(api.enviadas, ['A', 'B', 'C']);
      expect(sync.pendingCount, 0);
    });

    test('una orden rechazada queda como fallida y las siguientes se envían igualmente', () async {
      sync = await servicio(online: false);
      await dictar('buena 1');
      await dictar('mala');
      await dictar('buena 2');
      api.fallo = (o) => o == 'mala' ? ApiError('VALIDATION_ERROR', 'No entendí la orden.') : null;

      conexion.set(true);
      await pumpEventQueue();

      expect(api.enviadas, ['buena 1', 'buena 2']);
      expect(sync.ops.map((o) => o.status), [PendingStatus.done, PendingStatus.failed, PendingStatus.done]);
      expect(sync.ops[1].error, 'No entendí la orden.');
      expect(sync.pendingCount, 0);
    });

    test('con la sesión vencida las órdenes siguen guardadas y se avisa', () async {
      sync = await servicio(online: false);
      await dictar('registra un cliente con nombre Juan');
      api.fallo = (_) => ApiError('UNAUTHORIZED', 'La sesión venció.');

      conexion.set(true);
      await pumpEventQueue();

      expect(sync.sessionExpired, isTrue);
      expect(sync.ops.single.status, PendingStatus.pending);
      // Un 401 no cuenta como intento: el servidor ni miró la orden.
      expect(sync.ops.single.attempts, 0);
      expect(api.enviadas, isEmpty);
    });
  });

  group('orden y concurrencia', () {
    test('una orden nueva no adelanta a la que se está enviando', () async {
      sync = await servicio(online: false);
      await dictar('A');
      api.puerta = Completer<void>();

      // A queda «en vuelo»: el servidor todavía no contesta.
      conexion.set(true);
      await pumpEventQueue();
      expect(sync.ops.single.status, PendingStatus.syncing);

      final r = await dictar('B');
      expect(r.queued, isNotNull, reason: 'B va detrás de A, no se envía directamente');
      expect(api.enviadas, isEmpty);

      api.puerta!.complete();
      await pumpEventQueue();

      expect(api.enviadas, ['A', 'B']);
    });

    test('dos sincronizaciones a la vez no envían la misma orden dos veces', () async {
      sync = await servicio(online: false);
      await dictar('registra un cliente con nombre Juan');
      conexion.online = true;

      await Future.wait([sync.sync(), sync.sync()]);

      expect(api.enviadas, ['registra un cliente con nombre Juan']);
    });

    test('las órdenes de otro usuario no se envían con esta sesión', () async {
      await store.add(PendingOp(owner: 'demo/otro', diagramId: 'd1', diagramName: 'X', instruction: 'ajena'));
      sync = await servicio();

      await sync.sync();

      expect(api.enviadas, isEmpty);
      expect(sync.ops, isEmpty);
      expect(await store.all('demo/otro'), hasLength(1));
    });

    test('sin sesión no envía nada', () async {
      conexion = FakeConnectivity();
      sync = SyncService(api: api, store: store, connectivity: conexion, retryEvery: null);
      await store.add(PendingOp(owner: _sesion.owner, diagramId: 'd1', diagramName: 'X', instruction: 'a'));

      expect(await sync.sync(), 0);
      expect(api.enviadas, isEmpty);
    });
  });

  group('recuperación', () {
    test('una orden que se quedó «enviándose» al cerrar la app vuelve a pendiente y se envía', () async {
      await store.add(
        PendingOp(
          owner: _sesion.owner,
          diagramId: 'd1',
          diagramName: 'X',
          instruction: 'interrumpida',
          status: PendingStatus.syncing,
        ),
      );

      sync = await servicio();

      expect(api.enviadas, ['interrumpida']);
      expect(sync.ops.single.status, PendingStatus.done);
    });

    test('las órdenes guardadas de una sesión anterior se envían al volver a entrar', () async {
      await store.add(PendingOp(owner: _sesion.owner, diagramId: 'd1', diagramName: 'X', instruction: 'de ayer'));

      sync = await servicio();

      expect(api.enviadas, ['de ayer']);
    });

    test('quitar una orden pendiente la borra del dispositivo', () async {
      sync = await servicio(online: false);
      await dictar('no la quiero');

      await sync.discard(sync.ops.single);

      expect(sync.ops, isEmpty);
      expect(await store.all(_sesion.owner), isEmpty);
    });

    test('el historial de enviadas no crece sin límite', () async {
      sync = await servicio();
      for (var i = 0; i < 25; i++) {
        await store.add(
          PendingOp(
            owner: _sesion.owner,
            diagramId: 'd1',
            diagramName: 'X',
            instruction: 'vieja $i',
            status: PendingStatus.done,
          ),
        );
      }
      await store.add(PendingOp(owner: _sesion.owner, diagramId: 'd1', diagramName: 'X', instruction: 'nueva'));

      await sync.sync();

      final quedan = await store.all(_sesion.owner);
      expect(quedan.where((o) => o.status == PendingStatus.done), hasLength(20));
      expect(quedan.any((o) => o.instruction == 'vieja 0'), isFalse, reason: 'se borran las más antiguas');
    });
  });

  testWidgets('el reintento automático envía lo pendiente cuando el servidor vuelve, sin cambio de red', (tester) async {
    api.fallo = (_) => ApiError('AI_UNAVAILABLE', 'El backend generado no responde.');
    conexion = FakeConnectivity();
    sync = SyncService(api: api, store: store, connectivity: conexion, retryEvery: const Duration(seconds: 20));
    await sync.start();
    await sync.signIn(_sesion);
    await dictar('registra un cliente con nombre Juan');
    expect(sync.pendingCount, 1);

    // El servidor se recupera; la red nunca se cayó, así que ningún evento de conexión lo avisa.
    api.fallo = null;
    await tester.pump(const Duration(seconds: 21));
    await tester.pump();

    expect(api.enviadas, ['registra un cliente con nombre Juan']);
    expect(sync.pendingCount, 0);

    // El temporizador periódico tiene que apagarse antes de que termine la prueba.
    sync.dispose();
  });
}
