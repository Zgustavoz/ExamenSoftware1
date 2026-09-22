import 'dart:convert';

import 'package:http/http.dart' as http;

/// Error de la plataforma, con el mismo formato que en la web (sección 7.1). El mensaje ya viene en español.
class ApiError implements Exception {
  ApiError(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => message;
}

/// Resultado de una orden dictada: lo que se pidió al backend generado y lo que respondió.
class CommandResult {
  CommandResult({
    required this.explanation,
    required this.method,
    required this.path,
    required this.status,
    required this.usedAi,
    required this.response,
  });

  final String explanation;
  final String method;
  final String path;
  final int status;

  /// `false` cuando la orden la interpretó el respaldo local en vez del asistente.
  final bool usedAi;
  final Object? response;

  factory CommandResult.fromJson(Map<String, dynamic> json) => CommandResult(
    explanation: json['explanation'] as String? ?? '',
    method: json['method'] as String? ?? '',
    path: json['path'] as String? ?? '',
    status: json['status'] as int? ?? 0,
    usedAi: json['usedAi'] as bool? ?? false,
    response: json['response'],
  );
}

/// Cliente de la plataforma. La dirección se pasa al compilar:
/// `flutter run --dart-define=API_URL=http://10.0.2.2:8080`
/// (10.0.2.2 es como el emulador de Android ve el localhost del PC).
class Api {
  const Api();

  static const String baseUrl = String.fromEnvironment(
    'API_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );
  static const String companySlug = String.fromEnvironment('COMPANY_SLUG', defaultValue: 'demo');

  Future<String> login(String username, String password) async {
    final r = await _post('/api/auth/login', {
      'companySlug': companySlug,
      'username': username,
      'password': password,
    });
    return r['token'] as String;
  }

  /// Los diagramas de todos los proyectos de la empresa, para elegir contra cuál se dictan las órdenes.
  Future<List<({String id, String name})>> diagrams(String token) async {
    final projects = await _get('/api/projects?size=100', token);
    final content = (projects['content'] as List).cast<Map<String, dynamic>>();

    final result = <({String id, String name})>[];
    for (final project in content) {
      final data = await _graphql(token, r'query($p: ID!) { diagrams(projectId: $p) { id name type } }', {
        'p': project['id'],
      });
      for (final d in (data['diagrams'] as List).cast<Map<String, dynamic>>()) {
        if (d['type'] == 'CLASS') {
          result.add((id: d['id'] as String, name: '${project['name']} · ${d['name']}'));
        }
      }
    }
    return result;
  }

  /// Lo que el backend generado expone para ese diagrama.
  Future<List<String>> entities(String token, String diagramId) async {
    final r = await _getList('/api/generated-app/$diagramId/entities', token);
    return r.map((e) => (e as Map<String, dynamic>)['name'] as String).toList();
  }

  /// Entrega al backend el token de notificaciones de este dispositivo (`PUT /api/me/fcm-token`).
  Future<void> registerFcmToken(String jwt, String fcmToken) async {
    final response = await _send(() => http.put(
      Uri.parse('$baseUrl/api/me/fcm-token'),
      headers: _headers(jwt),
      body: jsonEncode({'token': fcmToken}),
    ));
    _decode(response);
  }

  Future<CommandResult> command(String token, String diagramId, String instruction) async {
    final r = await _post('/api/generated-app/$diagramId/command', {'instruction': instruction}, token: token);
    return CommandResult.fromJson(r);
  }

  // ------------------------------------------------------------------ transporte

  Future<Map<String, dynamic>> _graphql(String token, String query, Map<String, dynamic> variables) async {
    final r = await _post('/graphql', {'query': query, 'variables': variables}, token: token);
    if (r['errors'] != null) {
      final first = (r['errors'] as List).first as Map<String, dynamic>;
      final ext = first['extensions'] as Map<String, dynamic>?;
      throw ApiError(ext?['code'] as String? ?? 'UNKNOWN', first['message'] as String? ?? 'Error');
    }
    return r['data'] as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> _post(String path, Object body, {String? token}) async {
    final response = await _send(() => http.post(
      Uri.parse('$baseUrl$path'),
      headers: _headers(token),
      body: jsonEncode(body),
    ));
    return _decode(response) as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> _get(String path, String token) async {
    final response = await _send(() => http.get(Uri.parse('$baseUrl$path'), headers: _headers(token)));
    return _decode(response) as Map<String, dynamic>;
  }

  Future<List<dynamic>> _getList(String path, String token) async {
    final response = await _send(() => http.get(Uri.parse('$baseUrl$path'), headers: _headers(token)));
    return _decode(response) as List<dynamic>;
  }

  Map<String, String> _headers(String? token) => {
    'Content-Type': 'application/json',
    if (token != null) 'Authorization': 'Bearer $token',
  };

  Future<http.Response> _send(Future<http.Response> Function() call) async {
    try {
      return await call().timeout(const Duration(seconds: 30));
    } catch (_) {
      throw ApiError('NETWORK_ERROR', 'No se pudo conectar con el servidor ($baseUrl).');
    }
  }

  /// Traduce el formato de error de la plataforma a `ApiError`; el mensaje ya viene en español.
  Object _decode(http.Response response) {
    final texto = utf8.decode(response.bodyBytes);
    final body = texto.isEmpty ? <String, dynamic>{} : jsonDecode(texto);
    if (response.statusCode >= 400) {
      final mapa = body is Map<String, dynamic> ? body : <String, dynamic>{};
      throw ApiError(
        mapa['code'] as String? ?? 'UNKNOWN',
        mapa['message'] as String? ?? 'Ocurrió un error inesperado.',
      );
    }
    return body as Object;
  }
}
