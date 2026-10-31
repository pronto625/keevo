import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// Story 5.5 — Task 7.1: SyncEventLogger unit tests.
void main() {
  late AppDatabase db;
  late SyncEventLogger logger;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  setUp(() {
    db = AppDatabase.forTesting();
    logger = SyncEventLogger(db);
  });

  tearDown(() async {
    await db.close();
  });

  group('SyncEventLogger', () {
    test('logPushSuccess creates entry with PUSH type and SUCCESS status',
        () async {
      await logger.logPushSuccess(3);

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'PUSH');
      expect(events.first.status, 'SUCCESS');
      expect(events.first.operationCount, 3);
      expect(events.first.errorMessage, isNull);
    });

    test('logPullFailed creates entry with PULL type and error message',
        () async {
      await logger.logPullFailed('Connection timeout');

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'PULL');
      expect(events.first.status, 'FAILED');
      expect(events.first.errorMessage, 'Connection timeout');
    });

    test('logDiagnostic creates entry with DIAGNOSTIC type', () async {
      await logger.logDiagnostic('WARN', 'server unreachable');

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'DIAGNOSTIC');
      expect(events.first.status, 'WARN');
      expect(events.first.errorMessage, 'server unreachable');
    });

    test('getHistory returns events ordered by createdAt DESC', () async {
      // Injected clock: two distinct timestamps without a real wait.
      var tick = DateTime(2026, 10, 12, 9);
      logger = SyncEventLogger(db, now: () => tick);
      await logger.logPushSuccess(1);
      tick = tick.add(const Duration(minutes: 1));
      await logger.logPullSuccess(2);

      final events = await logger.getHistory();
      expect(events, hasLength(2));
      // Most recent first
      expect(events[0].type, 'PULL');
      expect(events[1].type, 'PUSH');
    });

    test('pruning keeps only 50 most recent entries', () async {
      // Insert 55 entries
      for (var i = 0; i < 55; i++) {
        await logger.logPushSuccess(i);
      }

      final events = await logger.getHistory(limit: 100);
      expect(events.length, 50);
    });
  });

  group('v1s-16-20: history titles and messages are French, true and readable',
      () {
    test('titles: never raw codes or English', () {
      expect(syncEventTitle('PUSH', 'FAILED'), 'Envoi des données échoué');
      expect(syncEventTitle('PULL', 'SUCCESS'), 'Réception des données réussie');
      expect(syncEventTitle('OTHER', 'SUCCESS'), 'Synchronisation réussie');
      expect(syncEventTitle('OTHER', 'WEIRD_STATUS'), 'Synchronisation');
      expect(syncEventTitle(kSyncEventAbandon, 'ABANDONED'),
          'Opération abandonnée');
    });

    test('PUSH / PULL failures with technical text → connection message', () {
      const connection = 'Problème de connexion — nouvel essai automatique';
      expect(syncEventMessage('PUSH', 'FAILED', '423 gate: 8 days stale'),
          connection);
      expect(
          syncEventMessage('PULL', 'FAILED',
              'DioException [connection error]: The connection errored'),
          connection);
      expect(syncEventMessage('PUSH', 'FAILED', kSyncPushFailedMessage),
          kSyncPushFailedMessage);
    });

    test('diagnostics: legacy English translated, legacy OK stays true', () {
      expect(
          syncEventMessage(
              'DIAGNOSTIC', 'WARN', 'server unreachable, JWT expired/invalid'),
          'serveur injoignable, session expirée, reconnectez-vous');
      expect(
          syncEventMessage('DIAGNOSTIC', 'OK',
              'Server reachable, JWT valid — sync issue may be transient'),
          'Serveur joignable, session valide');
    });

    test('ABANDON keeps the readable label, drops the code', () {
      expect(
          syncEventMessage(kSyncEventAbandon, 'ABANDONED',
              'Enregistrement de vente du 11/10 — 13 000 FCFA '
                  '(Refusé par le serveur (SOME_UNKNOWN_CODE))'),
          'Enregistrement de vente du 11/10 — 13 000 FCFA '
          '(Refusé par le serveur)');
      expect(syncEventMessage(kSyncEventAbandon, 'ABANDONED', 'SOME_CODE'),
          'Opération refusée par le serveur, abandonnée');
    });

    test('readable labels with ordinary words are kept (word boundaries)', () {
      expect(syncEventMessage('PUSH', 'FAILED', 'Serveurs occupés, Errorless'),
          'Serveurs occupés, Errorless');
    });
  });
}
