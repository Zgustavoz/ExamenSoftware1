import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart';

import 'api.dart';
import 'offline/pending_op.dart';
import 'offline/pending_store.dart';
import 'offline/sync_service.dart';
import 'push/push_messaging.dart';
import 'push/push_registrar.dart';

/// Pantalla única: se elige el diagrama, se dicta la orden y se ve lo que respondió el backend generado.
///
/// Sin conexión, la orden se guarda en el dispositivo y se envía sola al volver (ver [SyncService]).
class CommandPage extends StatefulWidget {
  const CommandPage({super.key, required this.api, this.sync, this.push});

  final Api api;

  /// Las notificaciones push. Si no se pasa, la pantalla no las usa (pruebas, o Firebase sin iniciar).
  final PushMessaging? push;

  /// El servicio de órdenes guardadas. Si no se pasa, la pantalla crea uno en memoria (pruebas).
  final SyncService? sync;

  @override
  State<CommandPage> createState() => _CommandPageState();
}

class _CommandPageState extends State<CommandPage> {
  final _speech = SpeechToText();
  final _instruction = TextEditingController();
  final _username = TextEditingController(text: 'designer');
  final _password = TextEditingController();

  late final SyncService _sync;
  late final bool _ownsSync;
  late final PushMessaging _push;
  late final PushRegistrar _registrar;
  StreamSubscription<PushMessage>? _pushSub;

  String? _token;
  List<({String id, String name})> _diagrams = const [];
  String? _diagramId;
  List<String> _entities = const [];

  bool _listening = false;
  bool _busy = false;
  String? _error;
  String? _notice;
  CommandResult? _result;

  @override
  void initState() {
    super.initState();
    _ownsSync = widget.sync == null;
    _sync = widget.sync ?? SyncService(api: widget.api, store: MemoryPendingStore(), retryEvery: null);
    _sync.onSynced = _avisarEnviadas;
    if (_ownsSync) unawaited(_sync.start());

    _push = widget.push ?? const NoPushMessaging();
    _registrar = PushRegistrar(api: widget.api, messaging: _push);
    // Con la app abierta Android no muestra las notificaciones: se enseñan dentro de la pantalla.
    _pushSub = _push.onForegroundMessage.listen(_mostrarNotificacion);
  }

  @override
  void dispose() {
    _sync.onSynced = null;
    unawaited(_pushSub?.cancel());
    _registrar.dispose();
    if (_ownsSync) _sync.dispose();
    _instruction.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  void _mostrarNotificacion(PushMessage m) {
    if (!mounted) return;
    final texto = [m.title, m.body].where((t) => t.isNotEmpty).join(': ');
    if (texto.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(texto), duration: const Duration(seconds: 6), showCloseIcon: true),
    );
  }

  void _avisarEnviadas(int n) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(n == 1 ? 'Se envió 1 orden guardada.' : 'Se enviaron $n órdenes guardadas.')),
    );
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } on ApiError catch (e) {
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _login() => _run(() async {
    final token = await widget.api.login(_username.text.trim(), _password.text);
    final diagrams = await widget.api.diagrams(token);
    setState(() {
      _token = token;
      _diagrams = diagrams;
      _diagramId = diagrams.isEmpty ? null : diagrams.first.id;
      _notice = null;
    });
    // Las órdenes que quedaron guardadas de otra vez se cargan y, si hay red, se envían.
    await _sync.signIn(Session(token: token, owner: '${Api.companySlug}/${_username.text.trim()}'));
    unawaited(_registrar.start(token));
    if (_diagramId != null) await _loadEntities();
  });

  /// Con la sesión vencida las órdenes siguen guardadas: se vuelve a la pantalla de entrada para enviarlas.
  void _entrarDeNuevo() {
    _sync.signOut();
    unawaited(_registrar.stop());
    setState(() {
      _token = null;
      _password.clear();
    });
  }

  Future<void> _loadEntities() async {
    // Sin conexión no se puede preguntar; el usuario puede dictar igualmente contra lo que ya tenía a la vista.
    if (!_sync.online) return;
    final names = await widget.api.entities(_token!, _diagramId!);
    if (mounted) setState(() => _entities = names);
  }

  /// La transcripción ocurre en el móvil (D-06): al servidor solo le llega texto.
  Future<void> _toggleMic() async {
    if (_listening) {
      await _speech.stop();
      setState(() => _listening = false);
      return;
    }
    final disponible = await _speech.initialize(
      onError: (e) => setState(() {
        _listening = false;
        _error = 'No se pudo usar el micrófono (${e.errorMsg}).';
      }),
      onStatus: (s) {
        if (s == 'done' || s == 'notListening') setState(() => _listening = false);
      },
    );
    if (!disponible) {
      setState(() => _error = 'Este dispositivo no tiene reconocimiento de voz disponible.');
      return;
    }
    setState(() {
      _listening = true;
      _error = null;
    });
    await _speech.listen(
      listenOptions: SpeechListenOptions(localeId: 'es_ES'),
      onResult: (r) => setState(() => _instruction.text = r.recognizedWords),
    );
  }

  Future<void> _send() => _run(() async {
    final diagramId = _diagramId!;
    final nombre = _diagrams.firstWhere((d) => d.id == diagramId).name;
    final outcome = await _sync.submit(
      diagramId: diagramId,
      diagramName: nombre,
      instruction: _instruction.text.trim(),
    );

    final guardada = outcome.queued;
    if (guardada == null) {
      setState(() {
        _result = outcome.result;
        _notice = null;
      });
      return;
    }
    // Quedó en el dispositivo: se limpia el campo para poder dictar la siguiente sin reenviar esta.
    setState(() {
      _result = null;
      _notice = !_sync.online
          ? 'Sin conexión: la orden quedó guardada en el dispositivo y se enviará cuando vuelva.'
          : guardada.error != null
          ? 'No se pudo enviar ahora. ${guardada.error} La orden quedó guardada y se reintentará.'
          : 'La orden quedó en cola detrás de las que estaban pendientes y se envía en orden.';
      _instruction.clear();
    });
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Backend generado')),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _sync,
          builder: (context, _) => SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (_token != null && !_sync.online) ...[
                  _Aviso(
                    icono: Icons.cloud_off,
                    texto:
                        'Sin conexión. Las órdenes que dicte se guardarán en el dispositivo y se enviarán cuando vuelva.',
                    color: Theme.of(context).colorScheme.tertiaryContainer,
                  ),
                  const SizedBox(height: 16),
                ],
                if (_token != null && _sync.sessionExpired) ...[
                  _Aviso(
                    icono: Icons.lock_clock,
                    texto: 'Su sesión venció. Las órdenes siguen guardadas: entre de nuevo para enviarlas.',
                    color: Theme.of(context).colorScheme.errorContainer,
                    accion: TextButton(onPressed: _entrarDeNuevo, child: const Text('Entrar de nuevo')),
                  ),
                  const SizedBox(height: 16),
                ],
                if (_token == null) ..._loginFields() else ..._commandFields(),
                if (_notice != null) ...[
                  const SizedBox(height: 16),
                  _Aviso(
                    icono: Icons.save_alt,
                    texto: _notice!,
                    color: Theme.of(context).colorScheme.secondaryContainer,
                  ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  _Aviso(texto: _error!, color: Theme.of(context).colorScheme.errorContainer),
                ],
                if (_result != null) ...[
                  const SizedBox(height: 16),
                  _ResultCard(result: _result!),
                ],
                if (_token != null && _sync.ops.isNotEmpty) ...[
                  const SizedBox(height: 24),
                  _OrdenesGuardadas(sync: _sync),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _loginFields() => [
    const Text('Entre con su usuario para elegir el diagrama.'),
    const SizedBox(height: 12),
    TextField(
      controller: _username,
      decoration: const InputDecoration(labelText: 'Usuario', border: OutlineInputBorder()),
    ),
    const SizedBox(height: 12),
    TextField(
      controller: _password,
      obscureText: true,
      decoration: const InputDecoration(labelText: 'Contraseña', border: OutlineInputBorder()),
      onSubmitted: (_) => _login(),
    ),
    const SizedBox(height: 16),
    FilledButton(
      onPressed: _busy ? null : _login,
      child: Text(_busy ? 'Entrando…' : 'Entrar'),
    ),
  ];

  List<Widget> _commandFields() => [
    DropdownButtonFormField<String>(
      initialValue: _diagramId,
      isExpanded: true,
      decoration: const InputDecoration(labelText: 'Diagrama', border: OutlineInputBorder()),
      items: [
        for (final d in _diagrams) DropdownMenuItem(value: d.id, child: Text(d.name, overflow: TextOverflow.ellipsis)),
      ],
      onChanged: _busy
          ? null
          : (value) {
              setState(() => _diagramId = value);
              _run(_loadEntities);
            },
    ),
    ListenableBuilder(
      listenable: _registrar,
      builder: (context, _) => _EstadoNotificaciones(status: _registrar.status),
    ),
    if (_entities.isNotEmpty) ...[
      const SizedBox(height: 8),
      Text(
        'El backend generado registra: ${_entities.join(', ')}.',
        style: Theme.of(context).textTheme.bodySmall,
      ),
    ],
    const SizedBox(height: 16),
    TextField(
      controller: _instruction,
      minLines: 2,
      maxLines: 4,
      // Sin esto el botón de enviar no se entera de que ya hay texto escrito.
      onChanged: (_) => setState(() {}),
      decoration: const InputDecoration(
        labelText: 'Orden',
        hintText: 'registra un cliente con nombre Juan y email juan@ejemplo.com',
        border: OutlineInputBorder(),
      ),
    ),
    const SizedBox(height: 16),
    Row(
      children: [
        // El botón del micrófono es lo único que hace falta para la demostración.
        IconButton.filled(
          onPressed: _busy ? null : _toggleMic,
          iconSize: 40,
          padding: const EdgeInsets.all(20),
          style: IconButton.styleFrom(
            backgroundColor: _listening ? Theme.of(context).colorScheme.error : null,
          ),
          icon: Icon(_listening ? Icons.stop : Icons.mic),
          tooltip: _listening ? 'Dejar de dictar' : 'Dictar la orden',
        ),
        const SizedBox(width: 16),
        Expanded(
          child: FilledButton.icon(
            onPressed: _busy || _diagramId == null || _instruction.text.trim().isEmpty ? null : _send,
            icon: const Icon(Icons.send),
            label: Text(_busy ? 'Enviando…' : 'Enviar la orden'),
          ),
        ),
      ],
    ),
    if (_listening)
      const Padding(
        padding: EdgeInsets.only(top: 12),
        child: Text('Escuchando… hable ahora.'),
      ),
  ];
}

/// Una línea discreta que dice si este dispositivo recibirá notificaciones.
class _EstadoNotificaciones extends StatelessWidget {
  const _EstadoNotificaciones({required this.status});

  final PushStatus status;

  @override
  Widget build(BuildContext context) {
    final (icono, texto) = switch (status) {
      PushStatus.unknown => (null, null),
      PushStatus.active => (Icons.notifications_active_outlined, 'Notificaciones activadas en este dispositivo.'),
      PushStatus.denied => (Icons.notifications_off_outlined, 'Notificaciones desactivadas: no dio el permiso.'),
      PushStatus.unavailable => (Icons.notifications_off_outlined, 'No se pudieron activar las notificaciones.'),
    };
    if (icono == null || texto == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        children: [
          Icon(icono, size: 16),
          const SizedBox(width: 6),
          Expanded(child: Text(texto, style: Theme.of(context).textTheme.bodySmall)),
        ],
      ),
    );
  }
}

class _Aviso extends StatelessWidget {
  const _Aviso({required this.texto, required this.color, this.icono, this.accion});

  final String texto;
  final Color color;
  final IconData? icono;
  final Widget? accion;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(12)),
      child: Row(
        children: [
          if (icono != null) ...[Icon(icono, size: 20), const SizedBox(width: 12)],
          Expanded(child: Text(texto)),
          ?accion,
        ],
      ),
    );
  }
}

/// Las órdenes que están en el dispositivo: las que esperan, las que se enviaron al volver la conexión y las
/// que el servidor rechazó.
class _OrdenesGuardadas extends StatelessWidget {
  const _OrdenesGuardadas({required this.sync});

  final SyncService sync;

  @override
  Widget build(BuildContext context) {
    final tema = Theme.of(context).textTheme;
    // Lo más reciente arriba.
    final ops = sync.ops.reversed.toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: Text('Órdenes en el dispositivo', style: tema.titleMedium)),
            if (sync.pendingCount > 0)
              TextButton.icon(
                onPressed: sync.syncing ? null : () => sync.sync(),
                icon: sync.syncing
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.sync),
                label: Text(sync.syncing ? 'Enviando…' : 'Sincronizar ahora'),
              ),
          ],
        ),
        for (final op in ops) _OrdenTile(op: op, onDiscard: () => sync.discard(op)),
      ],
    );
  }
}

class _OrdenTile extends StatelessWidget {
  const _OrdenTile({required this.op, required this.onDiscard});

  final PendingOp op;
  final VoidCallback onDiscard;

  @override
  Widget build(BuildContext context) {
    final r = op.result;
    final (icono, estado) = switch (op.status) {
      PendingStatus.pending => (
        const Icon(Icons.schedule),
        'Pendiente de envío${op.attempts > 0 ? ' · intento ${op.attempts}' : ''}'
            '${op.error == null ? '' : '\n${op.error}'}',
      ),
      PendingStatus.syncing => (
        const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
        'Enviando…',
      ),
      PendingStatus.done => (
        const Icon(Icons.check_circle, color: Colors.green),
        'Enviada: ${r?['explanation'] ?? ''}\n${r?['method']} ${r?['path']} → ${r?['status']}',
      ),
      PendingStatus.failed => (
        Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
        'Rechazada: ${op.error ?? 'el servidor no la aceptó'}',
      ),
    };
    return Card(
      child: ListTile(
        leading: icono,
        title: Text(op.instruction, maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: Text('${op.diagramName}\n$estado'),
        isThreeLine: true,
        trailing: IconButton(
          icon: const Icon(Icons.close),
          tooltip: 'Quitar de la lista',
          onPressed: op.status == PendingStatus.syncing ? null : onDiscard,
        ),
      ),
    );
  }
}

class _ResultCard extends StatelessWidget {
  const _ResultCard({required this.result});

  final CommandResult result;

  @override
  Widget build(BuildContext context) {
    final cuerpo = const JsonEncoder.withIndent('  ').convert(result.response);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(result.explanation, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              '${result.method} ${result.path} → ${result.status}'
              '${result.usedAi ? '' : '  (interpretado sin IA)'}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            SelectableText(cuerpo, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ],
        ),
      ),
    );
  }
}
