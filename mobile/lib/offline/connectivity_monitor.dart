import 'package:connectivity_plus/connectivity_plus.dart';

/// Si el dispositivo tiene red. Es una pista, no una garantía: se puede estar conectado a una wifi sin salida
/// a internet, y en ese caso el envío falla con `NETWORK_ERROR` y la orden se guarda igualmente.
abstract class ConnectivityMonitor {
  Future<bool> isOnline();

  /// Emite cada vez que se gana o se pierde la red.
  Stream<bool> get changes;
}

/// La implementación real, con `connectivity_plus`.
class PlusConnectivityMonitor implements ConnectivityMonitor {
  PlusConnectivityMonitor([Connectivity? connectivity]) : _connectivity = connectivity ?? Connectivity();

  final Connectivity _connectivity;

  static bool _hayRed(List<ConnectivityResult> r) => r.any((x) => x != ConnectivityResult.none);

  @override
  Future<bool> isOnline() async => _hayRed(await _connectivity.checkConnectivity());

  @override
  Stream<bool> get changes => _connectivity.onConnectivityChanged.map(_hayRed).distinct();
}

/// Para cuando no se inyecta otra cosa (las pruebas de pantalla): siempre hay red y nunca cambia.
class AlwaysOnline implements ConnectivityMonitor {
  const AlwaysOnline();

  @override
  Future<bool> isOnline() async => true;

  @override
  Stream<bool> get changes => const Stream.empty();
}
