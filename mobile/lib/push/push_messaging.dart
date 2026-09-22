import 'package:firebase_messaging/firebase_messaging.dart';

/// Una notificación que llegó con la app abierta.
class PushMessage {
  const PushMessage({required this.title, required this.body, this.data = const {}});

  final String title;
  final String body;

  /// Lo que el backend adjunta: `type` (`TASK_ASSIGNED`, `CODE_READY`…), `notificationId`, etc.
  final Map<String, String> data;
}

/// Lo que la app necesita de las notificaciones push. Es una interfaz para poder probar sin Firebase.
abstract class PushMessaging {
  /// Pide permiso para mostrar notificaciones (en Android 13 o superior sale un aviso del sistema).
  /// Devuelve `false` si el usuario lo negó.
  Future<bool> requestPermission();

  /// El token de este dispositivo, que el backend usa para enviarle notificaciones.
  Future<String?> getToken();

  /// Emite cuando Firebase cambia el token (reinstalar, borrar datos…): hay que volver a registrarlo.
  Stream<String> get onTokenRefresh;

  /// Notificaciones recibidas **con la app abierta**. Android no las muestra solo: hay que pintarlas.
  /// Con la app en segundo plano las muestra el propio sistema.
  Stream<PushMessage> get onForegroundMessage;
}

/// La implementación real, con Firebase Cloud Messaging.
class FirebasePushMessaging implements PushMessaging {
  FirebasePushMessaging([FirebaseMessaging? messaging]) : _messaging = messaging ?? FirebaseMessaging.instance;

  final FirebaseMessaging _messaging;

  @override
  Future<bool> requestPermission() async {
    final settings = await _messaging.requestPermission();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  @override
  Future<String?> getToken() => _messaging.getToken();

  @override
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Stream<PushMessage> get onForegroundMessage => FirebaseMessaging.onMessage.map(
    (m) => PushMessage(
      title: m.notification?.title ?? '',
      body: m.notification?.body ?? '',
      data: m.data.map((k, v) => MapEntry(k, '$v')),
    ),
  );
}

/// Sin notificaciones: cuando Firebase no se pudo iniciar, y en las pruebas de pantalla.
class NoPushMessaging implements PushMessaging {
  const NoPushMessaging();

  @override
  Future<bool> requestPermission() async => false;

  @override
  Future<String?> getToken() async => null;

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Stream<PushMessage> get onForegroundMessage => const Stream.empty();
}
