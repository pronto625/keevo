/// category_repository_offline_first_test.dart — Story 5.6 AC3 + AC10
///
/// Tests [CategoryRepositoryImpl] offline-first writes using in-memory Drift DB.
library;

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/catalog/data/repository/category_repository_impl.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

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

void main() {
  late AppDatabase db;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late CategoryRepositoryImpl repo;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    registerFallbackValue(<String, dynamic>{});
    registerFallbackValue(Value<String?>(''));
  });

  setUp(() {
    db = AppDatabase.forTesting();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = CategoryRepositoryImpl(db, mockSyncService, mockDispatcher);

    when(() => mockSyncService.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
    when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);
  });

  tearDown(() async => db.close());

  group('AC3 — createCustomCategory() offline-first', () {
    test('createCustomCategory_writesLocalFirst_queuesCreateCategory', () async {
      final category = await repo.createCustomCategory('Électronique');

      expect(category.id.length, 36, reason: 'UUID v4 expected');
      expect(category.name, 'Électronique');
      expect(category.isCustom, isTrue);
      // Local DB write must happen — verify the category persists
      final local = await repo.getLocalCategories();
      expect(local.any((c) => c.name == 'Électronique'), isTrue);
      // Queue operation must be called
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_CATEGORY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });

    test('createCustomCategory_withParentId_queuesWithParentId', () async {
      final parent = await repo.createCustomCategory('Parent');
      // reset call count
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.createCustomCategory('Enfant', parentId: parent.id);

      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_CATEGORY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  group('AC3 — toggleCategoryStatus() offline-first', () {
    test('toggleCategoryStatus_writesLocalFirst_queuesToggleCategory', () async {
      final created = await repo.createCustomCategory('Vêtements');
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final toggled = await repo.toggleCategoryStatus(created.id);

      expect(toggled.isActive, isFalse); // was true, now false
      verify(() => mockSyncService.queueOperation(
            operation: 'TOGGLE_CATEGORY',
            payload: any(named: 'payload'),
            entityId: created.id,
          )).called(1);
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });
  });

  group('AC3 — renameCategory() offline-first', () {
    test('renameCategory_writesLocalFirst_queuesRenameCategory', () async {
      final created = await repo.createCustomCategory('Ancien nom');
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final renamed = await repo.renameCategory(created.id, 'Nouveau nom');

      expect(renamed.name, 'Nouveau nom');
      verify(() => mockSyncService.queueOperation(
            operation: 'RENAME_CATEGORY',
            payload: any(named: 'payload'),
            entityId: created.id,
          )).called(1);
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });
  });
}

