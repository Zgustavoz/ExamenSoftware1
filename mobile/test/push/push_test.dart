import 'package:diagramas_movil/command_page.dart';
import 'package:diagramas_movil/push/push_messaging.dart';
import 'package:diagramas_movil/push/push_registrar.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fakes.dart';

void main() {
  group('PushRegistrar', () {
    late FakeApi api;
    late FakePush push;
    late PushRegistrar registrar;

    setUp(() {
      api = FakeApi();
      push = FakePush();
      registrar = PushRegistrar(api: api, messaging: push);
    });

    tearDown(() => registrar.dispose());

    test('pide permiso, obtiene el token y se lo entrega al backend con la sesión del usuario', () async {
      final estado = await registrar.start('jwt-designer');

      expect(estado, PushStatus.active);
      expect(push.permisosPedidos, 1);
      expect(api.tokensFcm, [('jwt-designer', 'fcm-token-1')]);
      expect(registrar.lastToken, 'fcm-token-1');
    });

    test('si el usuario niega el permiso, no registra nada', () async {
      push.permiso = false;

      final estado = await registrar.start('jwt');

      expect(estado, PushStatus.denied);
      expect(api.tokensFcm, isEmpty);
    });

    test('sin token (por ejemplo, sin servicios de Google) queda como no disponible', () async {
      push.token = null;

      expect(await registrar.start('jwt'), PushStatus.unavailable);
      expect(api.tokensFcm, isEmpty);
    });

    test('un fallo de Firebase no rompe la app: se queda como no disponible', () async {
      push.falla = true;

      expect(await registrar.start('jwt'), PushStatus.unavailable);
    });

    test('si el backend no responde al registrar, no lanza y queda como no disponible', () async {
      api.fallaRegistroFcm = true;

      expect(await registrar.start('jwt'), PushStatus.unavailable);
      expect(registrar.lastToken, isNull);
    });

    test('cuando Firebase cambia el token, se vuelve a registrar solo', () async {
      await registrar.start('jwt');

      push.cambiarToken('fcm-token-2');
      await pumpEventQueue();

      expect(api.tokensFcm.map((t) => t.$2), ['fcm-token-1', 'fcm-token-2']);
      expect(registrar.lastToken, 'fcm-token-2');
    });

    test('si el primer registro falló, el cambio de token lo reintenta', () async {
      api.fallaRegistroFcm = true;
      await registrar.start('jwt');
      api.fallaRegistroFcm = false;

      push.cambiarToken('fcm-token-2');
      await pumpEventQueue();

      expect(api.tokensFcm.map((t) => t.$2), ['fcm-token-2']);
    });

    test('al salir de la sesión deja de registrar tokens', () async {
      await registrar.start('jwt');
      await registrar.stop();

      push.cambiarToken('fcm-token-2');
      await pumpEventQueue();

      expect(api.tokensFcm.map((t) => t.$2), ['fcm-token-1']);
      expect(registrar.status, PushStatus.unknown);
    });

    test('al entrar otro usuario, el token se registra con su sesión', () async {
      await registrar.start('jwt-ana');
      await registrar.start('jwt-luis');

      expect(api.tokensFcm.map((t) => t.$1), ['jwt-ana', 'jwt-luis']);
    });
  });

  group('en la pantalla', () {
    late FakeApi api;
    late FakePush push;

    Future<void> entrar(WidgetTester tester) async {
      api = FakeApi();
      await tester.pumpWidget(MaterialApp(home: CommandPage(api: api, push: push)));
      await tester.tap(find.text('Entrar'));
      await tester.pumpAndSettle();
    }

    testWidgets('tras entrar registra el token y avisa que las notificaciones están activas', (tester) async {
      push = FakePush();
      await entrar(tester);

      expect(api.tokensFcm, [('jwt-de-prueba', 'fcm-token-1')]);
      expect(find.text('Notificaciones activadas en este dispositivo.'), findsOneWidget);
    });

    testWidgets('si se niega el permiso, lo dice', (tester) async {
      push = FakePush(permiso: false);
      await entrar(tester);

      expect(api.tokensFcm, isEmpty);
      expect(find.textContaining('desactivadas: no dio el permiso'), findsOneWidget);
    });

    testWidgets('una notificación recibida con la app abierta se muestra en pantalla', (tester) async {
      push = FakePush();
      await entrar(tester);

      push.llega(const PushMessage(title: 'Tarea asignada', body: 'Revisar el modelo'));
      // Un fotograma para recibir el evento y otro para que el SnackBar se anime. Sin `pumpAndSettle`: esperaría
      // a que el aviso desaparezca y ya no se vería.
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Tarea asignada: Revisar el modelo'), findsOneWidget);
    });

    testWidgets('sin notificaciones configuradas la pantalla funciona igual', (tester) async {
      api = FakeApi();
      await tester.pumpWidget(MaterialApp(home: CommandPage(api: api)));
      await tester.tap(find.text('Entrar'));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.mic), findsOneWidget);
      expect(find.textContaining('desactivadas'), findsOneWidget);
    });
  });
}
