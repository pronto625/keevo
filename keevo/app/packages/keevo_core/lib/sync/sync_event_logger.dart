import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

import '../storage/app_database.dart';

/// Event type of an abandoned quarantined operation (v1s-16-15).
const kSyncEventAbandon = 'ABANDON';

/// Story 15.5b — event type of an offline record the server refused for good
/// and that was removed from the device (e.g. an invoice created offline).
const kSyncEventReject = 'REJECT';

/// History messages of a failed push / pull (v1s-16-20): shown to the user
/// in « Historique de synchronisation », so French and without exception
/// text (the technical detail goes to `dev.log` only).
const kSyncPushFailedMessage =
    'Envoi des données impossible pour le moment — nouvel essai automatique';
/// A50: push held back while the account is read-only (suspended, deletion
/// pending) — the operations are kept.
const kSyncPushReadOnlyMessage =
    'Compte en consultation uniquement — vos opérations sont conservées et '
    'seront envoyées dès sa réactivation';
const kSyncPullFailedMessage =
    'Réception des données impossible pour le moment — nouvel essai automatique';

/// French title of a sync history entry (v1s-16-20): never the raw
/// `PUSH — FAILED` codes, never English.
String syncEventTitle(String type, String status) {
  return switch ((type, status)) {
    ('PUSH', 'SUCCESS') => 'Envoi des données réussi',
    ('PUSH', 'FAILED') => 'Envoi des données échoué',
    ('PULL', 'SUCCESS') => 'Réception des données réussie',
    ('PULL', 'FAILED') => 'Réception des données échouée',
    ('DIAGNOSTIC', 'OK') => 'Diagnostic : tout va bien',
    ('DIAGNOSTIC', 'WARN') => 'Diagnostic : un problème a été détecté',
    (kSyncEventAbandon, _) => 'Opération abandonnée',
    (kSyncEventReject, _) => 'Opération refusée par le serveur',
    (_, 'SUCCESS') => 'Synchronisation réussie',
    (_, 'FAILED') => 'Synchronisation échouée',
    (_, 'OK') => 'Synchronisation : tout va bien',
    (_, 'WARN') => 'Synchronisation : un problème a été détecté',
    _ => 'Synchronisation',
  };
}

/// Technical text a user must not read: exception / class names, HTTP or
/// gate vocabulary, a UUID, a SNAKE_CASE code or JSON (word-bounded).
final _technicalMessage = RegExp(
    r'\w*Exception\b|\bError\b|\bDio\b|\bhttps?\b|status code|\bgate\b'
    r'|\bstale\b|\bServer\b|\bJWT\b|\bnull\b'
    r'|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-|\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b'
    r'|\{"|\["');

/// Message of a sync history entry as shown to the user (v1s-16-20), or
/// `null` when nothing readable can be said. Entries logged before this rule
/// may hold an exception text, an HTTP code, English diagnostics or codes:
/// the replacement depends on the event, so it never says something false.
String? syncEventMessage(String type, String status, String message) {
  const legacy = {
    'server unreachable': 'serveur injoignable',
    'JWT expired/invalid': 'session expirée, reconnectez-vous',
  };
  var text = message;
  legacy.forEach((k, v) => text = text.replaceAll(k, v));
  if (!_technicalMessage.hasMatch(text)) return text;
  switch ((type, status)) {
    case ('DIAGNOSTIC', 'OK'):
      return 'Serveur joignable, session valide';
    case ('DIAGNOSTIC', _):
      return 'Un problème de connexion a été détecté';
    case (kSyncEventAbandon, _):
      // Keep the readable part of the label (operation, date, amount).
      final cleaned = text
          .replaceAll(RegExp(r'\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b'), '')
          .replaceAll(RegExp(
              r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'),
              '')
          .replaceAll(RegExp(r'\s*\(\s*\)'), '')
          .replaceAll(RegExp(r'\s+'), ' ')
          .trim();
      return cleaned.isEmpty || _technicalMessage.hasMatch(cleaned)
          ? 'Opération refusée par le serveur, abandonnée'
          : cleaned;
    case ('PUSH' || 'PULL', 'FAILED'):
      return 'Problème de connexion — nouvel essai automatique';
  }
  return null;
}

/// Logs sync events to local Drift sync_events table.
/// Prunes entries older than the 50 most recent on every insert.
class SyncEventLogger {
  final AppDatabase _db;

  /// Timestamp source, injectable so tests never wait on the real clock.
  final DateTime Function() _now;

  SyncEventLogger(this._db, {DateTime Function()? now})
      : _now = now ?? DateTime.now;

  Future<void> logPushSuccess(int operationCount) =>
      _log('PUSH', 'SUCCESS', operationCount, null);

  Future<void> logPushFailed(String error) =>
      _log('PUSH', 'FAILED', 0, error);

  Future<void> logPullSuccess(int operationCount) =>
      _log('PULL', 'SUCCESS', operationCount, null);

  Future<void> logPullFailed(String error) =>
      _log('PULL', 'FAILED', 0, error);

  Future<void> logDiagnostic(String status, String? message) =>
      _log('DIAGNOSTIC', status, 0, message);

  /// A quarantined operation abandoned by the owner after an export
  /// (v1s-16-15). [label] describes it (operation, date, amount).
  Future<void> logOperationAbandoned(String label) =>
      _log(kSyncEventAbandon, 'ABANDONED', 1, label);

  /// Story 15.5b — an offline record refused for good by the server and
  /// removed from the device; [label] names it in French (e.g. « Facture de
  /// Awa Traoré — 15 000 FCFA : Données invalides »).
  Future<void> logOperationRejected(String label) =>
      _log(kSyncEventReject, 'FAILED', 1, label);

  Future<void> _log(
      String type, String status, int opCount, String? error) async {
    await _db.into(_db.syncEvents).insert(SyncEventsCompanion.insert(
          id: const Uuid().v4(),
          type: type,
          status: status,
          operationCount: Value(opCount),
          errorMessage: Value(error),
          createdAt: _now(),
        ));
    // Prune: keep only last 50 entries
    await _db.customStatement(
      // Abandoned-operation traces (v1s-16-15, FR73a) are never rotated out.
      "DELETE FROM sync_events WHERE type != '$kSyncEventAbandon' AND id NOT IN "
      "(SELECT id FROM sync_events WHERE type != '$kSyncEventAbandon' "
      'ORDER BY created_at DESC LIMIT 50)',
    );
  }

  Future<List<SyncEvent>> getHistory({int limit = 20}) {
    return (_db.select(_db.syncEvents)
          ..orderBy([(t) => OrderingTerm.desc(t.createdAt)])
          ..limit(limit))
        .get();
  }
}
