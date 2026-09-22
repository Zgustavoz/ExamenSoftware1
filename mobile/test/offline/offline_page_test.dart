import 'package:diagramas_movil/api.dart';
import 'package:diagramas_movil/command_page.dart';
import 'package:diagramas_movil/offline/pending_store.dart';
import 'package:diagramas_movil/offline/sync_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fakes.dart';

/// El escenario del enunciado: se da una orden, se va el internet, la orden queda en el dispositivo y se
/// envía al volver la conexión.
void main() {
  late FakeApi api;
  late FakeConnectivity conexion;
  late SyncService sync;

  /// Monta la pantalla, entra con la red puesta y deja todo listo para dictar.
  Future<void> montarYEntrar(WidgetTester tester) async {
    api = FakeApi();
    conexion = FakeConnectivity();
    sync = SyncService(api: api, store: MemoryPendingStore(), connectivity: conexion, retryEvery: null);
    await sync.start();
    await tester.pumpWidget(MaterialApp(home: CommandPage(api: api, sync: sync)));

    await tester.tap(find.text('Entrar'));
    await tester.pumpAndSettle();
  }

  Future<void> dictar(WidgetTester tester, String orden) async {
    await tester.enterText(find.byType(TextField), orden);
    await tester.pump();
    await tester.tap(find.text('Enviar la orden'));
    await tester.pumpAndSettle();
  }

  tearDown(() => sync.dispose());

  testWidgets('sin conexión avisa, y con conexión no muestra ningún aviso', (tester) async {
    await montarYEntrar(tester);
    expect(find.textContaining('Sin conexión.'), findsNothing);

    conexion.set(false);
    await tester.pumpAndSettle();

    expect(find.textContaining('Sin conexión. Las órdenes que dicte se guardarán en el dispositivo'), findsOneWidget);
  });

  testWidgets('sin conexión la orden se guarda en el dispositivo y no se envía', (tester) async {
    await montarYEntrar(tester);
    conexion.set(false);
    await tester.pumpAndSettle();

    await dictar(tester, 'registra un cliente con nombre Juan');

    expect(api.enviadas, isEmpty);
    expect(find.textContaining('quedó guardada en el dispositivo'), findsOneWidget);
    expect(find.text('Órdenes en el dispositivo'), findsOneWidget);
    expect(find.text('registra un cliente con nombre Juan'), findsOneWidget);
    expect(find.textContaining('Pendiente de envío'), findsOneWidget);
    // El campo queda libre para dictar la siguiente sin volver a enviar esta.
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, isEmpty);
  });

  testWidgets('al volver la conexión la orden se envía sola y pasa a «Enviada»', (tester) async {
    await montarYEntrar(tester);
    conexion.set(false);
    await tester.pumpAndSettle();
    await dictar(tester, 'registra un cliente con nombre Juan');

    conexion.set(true);
    await tester.pumpAndSettle();

    expect(api.enviadas, ['registra un cliente con nombre Juan']);
    expect(find.textContaining('Enviada: Hecho: registra un cliente con nombre Juan'), findsOneWidget);
    expect(find.textContaining('POST /api/clientes → 201'), findsOneWidget);
    expect(find.text('Se envió 1 orden guardada.'), findsOneWidget);
    expect(find.textContaining('Sin conexión.'), findsNothing);
    expect(find.textContaining('Pendiente de envío'), findsNothing);
  });

  testWidgets('varias órdenes dictadas sin conexión se envían todas y en orden', (tester) async {
    await montarYEntrar(tester);
    conexion.set(false);
    await tester.pumpAndSettle();
    await dictar(tester, 'primera orden');
    await dictar(tester, 'segunda orden');

    conexion.set(true);
    await tester.pumpAndSettle();

    expect(api.enviadas, ['primera orden', 'segunda orden']);
    expect(find.text('Se enviaron 2 órdenes guardadas.'), findsOneWidget);
  });

  testWidgets('si el envío falla por la red, la orden se guarda aunque el aviso de conexión no lo supiera', (tester) async {
    await montarYEntrar(tester);
    api.fallo = (_) => sinRed();

    await dictar(tester, 'registra un cliente con nombre Juan');

    expect(find.textContaining('No se pudo enviar ahora'), findsOneWidget);
    expect(find.textContaining('Pendiente de envío · intento 1'), findsOneWidget);
  });

  testWidgets('se puede quitar una orden pendiente de la lista', (tester) async {
    await montarYEntrar(tester);
    conexion.set(false);
    await tester.pumpAndSettle();
    await dictar(tester, 'no la quiero');

    // La lista queda debajo del formulario: hay que desplazar la pantalla hasta el botón.
    await tester.ensureVisible(find.byTooltip('Quitar de la lista'));
    await tester.tap(find.byTooltip('Quitar de la lista'));
    await tester.pumpAndSettle();

    expect(find.text('no la quiero'), findsNothing);
    expect(find.text('Órdenes en el dispositivo'), findsNothing);
  });

  testWidgets('con la sesión vencida avisa y deja volver a entrar sin perder las órdenes', (tester) async {
    await montarYEntrar(tester);
    conexion.set(false);
    await tester.pumpAndSettle();
    await dictar(tester, 'registra un cliente con nombre Juan');
    api.fallo = (_) => ApiError('UNAUTHORIZED', 'La sesión venció.');

    conexion.set(true);
    await tester.pumpAndSettle();
    expect(find.textContaining('Su sesión venció'), findsOneWidget);

    api.fallo = null;
    await tester.tap(find.text('Entrar de nuevo'));
    await tester.pumpAndSettle();
    expect(find.text('Entrar'), findsOneWidget);

    // Al volver a entrar, la orden que había quedado guardada se envía.
    await tester.tap(find.text('Entrar'));
    await tester.pumpAndSettle();
    expect(api.enviadas, ['registra un cliente con nombre Juan']);
  });
}
