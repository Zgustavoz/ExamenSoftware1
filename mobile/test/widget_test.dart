import 'package:diagramas_movil/api.dart';
import 'package:diagramas_movil/command_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Api de prueba: ni red ni micrófono. Registra lo que se le pidió.
class _FakeApi implements Api {
  final List<String> ordenes = [];
  bool fallaLogin = false;

  @override
  Future<String> login(String username, String password) async {
    if (fallaLogin) throw ApiError('INVALID_CREDENTIALS', 'Empresa, usuario o contraseña incorrectos.');
    return 'jwt-de-prueba';
  }

  @override
  Future<List<({String id, String name})>> diagrams(String token) async => [
    (id: 'd1', name: 'Ventas · Dominio'),
  ];

  @override
  Future<List<String>> entities(String token, String diagramId) async => ['Cliente'];

  @override
  Future<void> registerFcmToken(String jwt, String fcmToken) async {}

  @override
  Future<CommandResult> command(String token, String diagramId, String instruction) async {
    ordenes.add(instruction);
    return CommandResult(
      explanation: 'Registré un Cliente.',
      method: 'POST',
      path: '/api/clientes',
      status: 201,
      usedAi: false,
      response: {'id': 'abc', 'nombre': 'Juan'},
    );
  }
}

Future<void> _montar(WidgetTester tester, _FakeApi api) async {
  await tester.pumpWidget(MaterialApp(home: CommandPage(api: api)));
}

void main() {
  testWidgets('pide entrar antes de dejar dictar', (tester) async {
    await _montar(tester, _FakeApi());

    expect(find.text('Entrar'), findsOneWidget);
    expect(find.byIcon(Icons.mic), findsNothing);
  });

  testWidgets('tras entrar muestra el diagrama, el micrófono y lo que registra', (tester) async {
    await _montar(tester, _FakeApi());

    await tester.tap(find.text('Entrar'));
    await tester.pumpAndSettle();

    expect(find.text('Ventas · Dominio'), findsOneWidget);
    expect(find.byIcon(Icons.mic), findsOneWidget);
    expect(find.textContaining('El backend generado registra: Cliente'), findsOneWidget);
  });

  testWidgets('envía la orden dictada y muestra lo que respondió el backend generado', (tester) async {
    final api = _FakeApi();
    await _montar(tester, api);
    await tester.tap(find.text('Entrar'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'registra un cliente con nombre Juan');
    await tester.pump();
    await tester.tap(find.text('Enviar la orden'));
    await tester.pumpAndSettle();

    expect(api.ordenes, ['registra un cliente con nombre Juan']);
    expect(find.text('Registré un Cliente.'), findsOneWidget);
    expect(find.textContaining('POST /api/clientes → 201'), findsOneWidget);
    // Cuando la orden no la interpretó la IA, se dice: para no dar por hecho lo que no fue.
    expect(find.textContaining('interpretado sin IA'), findsOneWidget);
  });

  testWidgets('muestra el mensaje del servidor si las credenciales no valen', (tester) async {
    final api = _FakeApi()..fallaLogin = true;
    await _montar(tester, api);

    await tester.tap(find.text('Entrar'));
    await tester.pumpAndSettle();

    expect(find.text('Empresa, usuario o contraseña incorrectos.'), findsOneWidget);
    expect(find.byIcon(Icons.mic), findsNothing);
  });
}
