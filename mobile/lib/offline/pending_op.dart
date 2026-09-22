/// Estado de una orden guardada en el dispositivo (sección 11.2: `pending_ops.status`).
///
/// No hay `CONFLICT`: una orden dictada es un comando, no un guardado con `baseVersion`, así que no puede
/// chocar con la versión de nadie.
enum PendingStatus {
  /// Esperando a que haya conexión (o a que el servidor esté disponible).
  pending,

  /// Se está enviando ahora mismo.
  syncing,

  /// El servidor la ejecutó.
  done,

  /// El servidor la rechazó; reintentar no serviría de nada. Hay que corregirla y volver a dictarla.
  failed,
}

/// Una orden dictada y guardada en el dispositivo hasta que se pueda enviar.
class PendingOp {
  PendingOp({
    this.id,
    required this.owner,
    required this.diagramId,
    required this.diagramName,
    required this.instruction,
    this.status = PendingStatus.pending,
    this.attempts = 0,
    this.error,
    this.result,
    DateTime? createdAt,
  }) : createdAt = createdAt ?? DateTime.now();

  /// `null` hasta que el almacén la guarda.
  final int? id;

  /// `empresa/usuario`: una orden solo se envía con la sesión de quien la dictó.
  final String owner;
  final String diagramId;
  final String diagramName;
  final String instruction;
  final DateTime createdAt;

  PendingStatus status;
  int attempts;

  /// Por qué no se pudo enviar todavía, o por qué se rechazó.
  String? error;

  /// Lo que respondió el servidor cuando la ejecutó (`explanation`, `method`, `path`, `status`).
  Map<String, Object?>? result;

  PendingOp copyWith({int? id}) => PendingOp(
    id: id ?? this.id,
    owner: owner,
    diagramId: diagramId,
    diagramName: diagramName,
    instruction: instruction,
    status: status,
    attempts: attempts,
    error: error,
    result: result,
    createdAt: createdAt,
  );
}
