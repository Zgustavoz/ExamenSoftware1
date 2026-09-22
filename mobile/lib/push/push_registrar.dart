import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api.dart';
import 'push_messaging.dart';

/// Cómo quedó el registro de las notificaciones en este dispositivo.
enum PushStatus {
  /// Todavía no se intentó (no se entró en la app).
  unknown,

  /// Hay permiso y el backend ya conoce el token de este dispositivo.
  active,

  /// El usuario negó el permiso: no llegarán notificaciones.
  denied,

  /// No se pudo: sin servicios de Google, sin red al registrar, Firebase sin iniciar…
  unavailable,
}

/// Entrega al backend el token FCM del dispositivo (`PUT /api/me/fcm-token`) para que pueda avisarle de
/// «tarea asignada» o «código listo».
///
/// Un fallo aquí **nunca** rompe la app: sin notificaciones se puede seguir trabajando.
class PushRegistrar extends ChangeNotifier {
  PushRegistrar({required this.api, required this.messaging});

  final Api api;
  final PushMessaging messaging;

  PushStatus status = PushStatus.unknown;

  /// El último token que el backend aceptó (útil para comprobarlo con Postman o en la consola de Firebase).
  String? lastToken;

  String? _jwt;
  StreamSubscription<String>? _refresh;

  /// Pide el permiso, obtiene el token y lo registra. Se llama al entrar y otra vez si cambia el usuario.
  Future<PushStatus> start(String jwt) async {
    await _refresh?.cancel();
    _refresh = null;
    _jwt = jwt;
    try {
      if (!await messaging.requestPermission()) return _set(PushStatus.denied);
      final token = await messaging.getToken();
      if (token == null) return _set(PushStatus.unavailable);

      // Se escucha el cambio de token aunque el primer registro falle: cuando haya red, se reintenta solo.
      _refresh = messaging.onTokenRefresh.listen((t) => unawaited(_send(t)));
      return _set(await _send(token) ? PushStatus.active : PushStatus.unavailable);
    } catch (e) {
      debugPrint('Notificaciones no disponibles: $e');
      return _set(PushStatus.unavailable);
    }
  }

  /// Al salir de la sesión: el token deja de registrarse con un usuario que ya no está.
  Future<void> stop() async {
    await _refresh?.cancel();
    _refresh = null;
    _jwt = null;
    _set(PushStatus.unknown);
  }

  Future<bool> _send(String token) async {
    final jwt = _jwt;
    if (jwt == null) return false;
    try {
      await api.registerFcmToken(jwt, token);
      lastToken = token;
      return true;
    } catch (e) {
      debugPrint('No se pudo registrar el token de notificaciones: $e');
      return false;
    }
  }

  PushStatus _set(PushStatus s) {
    status = s;
    notifyListeners();
    return s;
  }

  @override
  void dispose() {
    _refresh?.cancel();
    super.dispose();
  }
}
