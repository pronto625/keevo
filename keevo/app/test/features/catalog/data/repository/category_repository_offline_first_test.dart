/// category_repository_offline_first_test.dart — Story 5.6 AC3 + AC10
///
/// Tests [CategoryRepositoryImpl] offline-first writes using in-memory Drift DB.
library;

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/repository/category_repository_impl.dart';
import 'package:keevo/features/catalog/domain/repository/category_repository.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
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

  group('device test — duplicate names refused (case/accent-insensitive)', () {
    test('create refuses "boissons" when "Boissons" exists, queues nothing',
        () async {
      await repo.createCustomCategory('Boissons');
      clearInteractions(mockSyncService);

      await expectLater(repo.createCustomCategory(' boissons '),
          throwsA(isA<CategoryNameTakenException>()));
      await expectLater(repo.createCustomCategory('Epicerie'), completes);
      await expectLater(repo.createCustomCategory('ÉPICERIE'),
          throwsA(isA<CategoryNameTakenException>()));
      final rows = await db.select(db.categories).get();
      expect(rows.where((c) => c.name.toLowerCase().contains('boisson')),
          hasLength(1));
    });

    test('rename to another category name is refused; same name kept is fine',
        () async {
      await repo.createCustomCategory('Boissons');
      final other = await repo.createCustomCategory('Snacks');

      await expectLater(repo.renameCategory(other.id, 'BOISSONS'),
          throwsA(isA<CategoryNameTakenException>()));
      await expectLater(repo.renameCategory(other.id, 'snacks'), completes);
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

  group('Story 14.14 — deleteCategory() soft-deactivate + sync', () {
    test('AC1 — deleteCategory_active_setsIsActiveFalse_queuesToggleCategory',
        () async {
      final created = await repo.createCustomCategory('À supprimer');
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final beforeDelete = DateTime.now();
      await repo.deleteCategory(created.id);

      // Verify local state: isActive should be false, row still exists,
      // updatedAt bumped, and no physical delete (AC1 — other fields
      // survive the soft-deactivate untouched).
      final allRows = await db.select(db.categories).get();
      final deleted = allRows.firstWhere((r) => r.id == created.id);
      expect(deleted.isActive, isFalse);
      expect(deleted.name, created.name);
      expect(deleted.isCustom, created.isCustom);
      expect(deleted.createdAt, created.createdAt);
      expect(
        deleted.updatedAt.isAfter(beforeDelete) ||
            deleted.updatedAt.isAtSameMomentAs(beforeDelete),
        isTrue,
        reason: 'updatedAt must be bumped by deleteCategory()',
      );

      // Verify sync: TOGGLE_CATEGORY queued with isActive=false
      verify(() => mockSyncService.queueOperation(
            operation: 'TOGGLE_CATEGORY',
            payload: {'categoryId': created.id, 'isActive': false},
            entityId: created.id,
          )).called(1);
      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);

      // Verify not visible in active-only query
      final activeCats = await repo.getLocalCategories();
      expect(activeCats.any((c) => c.id == created.id), isFalse);
    });

    test('AC1 — deleteCategory_alreadyInactive_isNoOp',
        () async {
      final created = await repo.createCustomCategory('Déjà désactivée');
      // Toggle it off first
      await repo.toggleCategoryStatus(created.id);
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.deleteCategory(created.id);

      // No new sync operation should be queued
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    // AC3 (non-régression : la catégorie supprimée ne réapparaît plus après
    // un pull) is validated end-to-end against the real pull path — not
    // here. See sync_pull_merge_test.dart,
    // 'category_deletePending_TOGGLE_CATEGORY_isActiveFalse_isNotReactivated',
    // which exercises RestSyncService.pull() (mocked Dio) with a pending
    // TOGGLE_CATEGORY(isActive:false) op and asserts the local row stays
    // isActive=false when the server still reports isActive=true (AC7
    // guard in rest_sync_service.dart's _upsertCategories()). A previous
    // version of this test simulated the pull via a raw db.customStatement()
    // that bypassed that guard entirely, so it could not prove AC3 either
    // way — it has been replaced by the real-path test above.
  });

  group('v1s-14-15 — typed not-found + atomic writes', () {
    const missingId = '00000000-0000-4000-8000-000000000000';

    void stubQueueFailure() {
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('queue failure'));
    }

    void resetMocks() {
      clearInteractions(mockSyncService);
      clearInteractions(mockDispatcher);
    }

    test('toggleCategoryStatus_missingRow_throwsCategoryNotFound_queuesNothing',
        () async {
      await expectLater(
        repo.toggleCategoryStatus(missingId),
        throwsA(isA<CategoryNotFoundException>()),
      );
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
      expect(await db.select(db.categories).get(), isEmpty);
    });

    test('renameCategory_missingRow_throwsCategoryNotFound_queuesNothing',
        () async {
      await expectLater(
        repo.renameCategory(missingId, 'Nouveau'),
        throwsA(isA<CategoryNotFoundException>()),
      );
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    test('CategoryNotFoundException_appErrorMessage_isFrenchMessage', () {
      expect(
        appErrorMessage(const CategoryNotFoundException(missingId)),
        'Catégorie introuvable ou supprimée.',
      );
    });

    test('deleteCategory_missingRow_isNoOp', () async {
      await repo.deleteCategory(missingId);

      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    test('createCustomCategory_queueFails_rollsBackLocalInsert_noPush',
        () async {
      stubQueueFailure();

      await expectLater(
          repo.createCustomCategory('Fantôme'), throwsA(isA<Exception>()));

      expect(await db.select(db.categories).get(), isEmpty);
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    test('toggleCategoryStatus_queueFails_rollsBackLocalToggle_noPush',
        () async {
      final created = await repo.createCustomCategory('Boissons');
      resetMocks();
      stubQueueFailure();

      await expectLater(
          repo.toggleCategoryStatus(created.id), throwsA(isA<Exception>()));

      final row = await (db.select(db.categories)
            ..where((c) => c.id.equals(created.id)))
          .getSingle();
      expect(row.isActive, isTrue);
      expect(row.updatedAt, created.updatedAt);
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    test('renameCategory_queueFails_rollsBackLocalRename_noPush', () async {
      final created = await repo.createCustomCategory('Ancien');
      resetMocks();
      stubQueueFailure();

      await expectLater(repo.renameCategory(created.id, 'Nouveau'),
          throwsA(isA<Exception>()));

      final row = await (db.select(db.categories)
            ..where((c) => c.id.equals(created.id)))
          .getSingle();
      expect(row.name, 'Ancien');
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });

    test('deleteCategory_queueFails_rollsBackLocalDeactivate_noPush',
        () async {
      final created = await repo.createCustomCategory('Épicerie');
      resetMocks();
      stubQueueFailure();

      await expectLater(
          repo.deleteCategory(created.id), throwsA(isA<Exception>()));

      final row = await (db.select(db.categories)
            ..where((c) => c.id.equals(created.id)))
          .getSingle();
      expect(row.isActive, isTrue);
      verifyNever(() => mockDispatcher.triggerPushIfIdle());
    });
  });
}

