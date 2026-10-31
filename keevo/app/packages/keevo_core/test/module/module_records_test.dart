import 'dart:ffi';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_event_logger.dart';
import 'package:sqlite3/open.dart';

/// Story 15.5b — the local store of module records, the generic pull merger,
/// the sync queue port and the v31 → v32 migration.
void main() {
  setUpAll(() {
    if (!Platform.isLinux) return;
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  late AppDatabase db;
  late ModuleRecordStore store;

  setUp(() {
    db = AppDatabase.forTesting();
    store = ModuleRecordStore(db);
  });
  tearDown(() => db.close());

  group('ModuleRecordStore', () {
    test('put / get / list / delete, keyed by (module, entity, id)', () async {
      await store.put('invoicing', 'invoices', 'a', {
        'id': 'a',
        'n': 1,
      }, pending: true);
      await store.put('invoicing', 'invoices', 'b', {
        'id': 'b',
      }, updatedAt: DateTime(2020));
      await store.put('other', 'invoices', 'a', {'id': 'a', 'n': 9});

      final a = (await store.get('invoicing', 'invoices', 'a'))!;
      expect(a.json, {'id': 'a', 'n': 1});
      expect(a.pending, isTrue);
      expect(
        (await store.list('invoicing', 'invoices')).map((r) => r.id),
        ['a', 'b'], // most recently changed first
      );

      // replaced in place
      await store.put('invoicing', 'invoices', 'a', {'id': 'a', 'n': 2});
      expect((await store.get('invoicing', 'invoices', 'a'))!.pending, isFalse);
      expect((await store.get('other', 'invoices', 'a'))!.json['n'], 9);

      expect(await store.delete('invoicing', 'invoices', 'a'), isTrue);
      expect(await store.get('invoicing', 'invoices', 'a'), isNull);
      expect(await store.delete('invoicing', 'invoices', 'a'), isFalse);
      expect((await store.findById('a'))!.module, 'other');
    });

    test('watch emits on every change', () async {
      final seen = <int>[];
      final sub = store
          .watch('invoicing', 'invoices')
          .listen((rows) => seen.add(rows.length));
      addTearDown(sub.cancel);
      await pumpEventQueue();
      await store.put('invoicing', 'invoices', 'a', {'id': 'a'});
      await pumpEventQueue();
      await store.put('invoicing', 'invoices', 'b', {'id': 'b'});
      await pumpEventQueue();
      expect(seen, [0, 1, 2]);
    });
  });

  group('replaceUnlessPending', () {
    test('pulled rows replace the local copy (no longer pending) unless '
        'their id has a queued operation; rows without id skipped', () async {
      await store.put('invoicing', 'invoices', 'kept', {
        'id': 'kept',
        'v': 0,
      }, pending: true);
      await store.put('invoicing', 'invoices', 'mine', {
        'id': 'mine',
        'v': 0,
      }, pending: true);
      final merge = replaceUnlessPending(
        module: 'invoicing',
        entity: 'invoices',
      );

      await merge(
        store,
        [
          {'id': 'kept', 'v': 1, 'updatedAt': '2026-10-31T10:00:00Z'},
          {'id': 'mine', 'v': 1},
          {'id': 'new', 'v': 1},
          {'v': 2},
        ],
        {'kept'},
      );

      final kept = (await store.get('invoicing', 'invoices', 'kept'))!;
      expect(kept.json['v'], 0);
      expect(kept.pending, isTrue);
      final mine = (await store.get('invoicing', 'invoices', 'mine'))!;
      expect(mine.json['v'], 1);
      expect(mine.pending, isFalse);
      expect(await store.get('invoicing', 'invoices', 'new'), isNotNull);
      expect(await store.list('invoicing', 'invoices'), hasLength(3));
    });
  });

  group('replaceUnlessPending (15.5b review)', () {
    final merge = replaceUnlessPending(module: 'invoicing', entity: 'invoices');

    test('a tombstone removes the local copy unless protected', () async {
      await store.put('invoicing', 'invoices', 'a', {'id': 'a'});
      await store.put('invoicing', 'invoices', 'b', {'id': 'b'}, pending: true);
      await merge(
        store,
        [
          {'id': 'a', 'deleted': true},
          {'id': 'b', 'deleted': true},
        ],
        {'b'},
      );
      expect(await store.get('invoicing', 'invoices', 'a'), isNull);
      expect(await store.get('invoicing', 'invoices', 'b'), isNotNull);
    });

    test('a row older than a confirmed local copy is skipped; a pending '
        'unprotected copy is always replaced', () async {
      await store.put('invoicing', 'invoices', 'a', {
        'id': 'a',
        'v': 2,
      }, updatedAt: DateTime.utc(2026, 10, 31, 12));
      await store.put(
        'invoicing',
        'invoices',
        'p',
        {'id': 'p', 'v': 2},
        updatedAt: DateTime.utc(2026, 10, 31, 12),
        pending: true,
      );
      await merge(store, [
        {'id': 'a', 'v': 1, 'updatedAt': '2026-10-31T10:00:00Z'},
        {'id': 'p', 'v': 1, 'updatedAt': '2026-10-31T10:00:00Z'},
      ], {});
      expect((await store.get('invoicing', 'invoices', 'a'))!.json['v'], 2);
      final p = (await store.get('invoicing', 'invoices', 'p'))!;
      expect(p.json['v'], 1);
      expect(p.pending, isFalse);
    });

    test('rejection history: French title for the new event type', () {
      expect(
        syncEventTitle(kSyncEventReject, 'FAILED'),
        'Opération refusée par le serveur',
      );
    });
  });

  group('composeModuleMergers / port', () {
    test('mergers by pull key; a key claimed twice is a StateError', () {
      final merge = replaceUnlessPending(module: 'm', entity: 'e');
      const empty = KeevoModuleUi(id: 'x');
      final a = KeevoModuleUi(id: 'a', pulledEntities: {'invoices': merge});
      final b = KeevoModuleUi(id: 'b', pulledEntities: {'invoices': merge});
      expect(composeModuleMergers([empty, a]).keys, ['invoices']);
      final withPath = KeevoModuleUi(
        id: 'c',
        recordPaths: {'invoices': (id) => '/x/$id'},
      );
      expect(
        composeModuleRecordPaths([empty, withPath])['invoices']!('7'),
        '/x/7',
      );
      expect(() => composeModuleMergers([a, b]), throwsStateError);
    });

    test('sync queue port not overridden → error naming it', () {
      final c = ProviderContainer(retry: noProviderRetry);
      addTearDown(c.dispose);
      Object? error;
      try {
        c.read(moduleSyncQueueProvider);
      } catch (e) {
        error = e;
      }
      expect(error.toString(), contains('moduleSyncQueueProvider'));
    });
  });

  test('v31 → v32 creates module_records, earlier data kept', () async {
    final old = AppDatabase.forTestingWith(
      NativeDatabase.memory(
        setup: (raw) {
          raw.execute(
            'CREATE TABLE sync_queue (id TEXT NOT NULL PRIMARY KEY, '
            'operation TEXT NOT NULL, payload TEXT NOT NULL, '
            'created_at TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, '
            'retry_count INTEGER NOT NULL DEFAULT 0, last_attempt_at TEXT NULL, '
            'entity_id TEXT NULL, last_error TEXT NULL, '
            'quarantined_at TEXT NULL)',
          );
          raw.execute(
            "INSERT INTO sync_queue (id, operation, payload, created_at) "
            "VALUES ('q1', 'CREATE_SALE', '{}', '2026-10-30T10:00:00.000')",
          );
          raw.userVersion = 31;
        },
      ),
    );
    addTearDown(old.close);
    final migrated = ModuleRecordStore(old);
    await migrated.put('invoicing', 'invoices', 'a', {'id': 'a'});
    expect(await migrated.get('invoicing', 'invoices', 'a'), isNotNull);
    expect(await old.select(old.syncQueue).get(), hasLength(1));
  });
}
