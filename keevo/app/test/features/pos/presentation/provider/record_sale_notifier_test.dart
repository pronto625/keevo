import 'dart:ffi';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:drift/drift.dart' show Value;
import 'package:keevo/core/storage/app_database.dart'
    show AppDatabase, ProductsCompanion;
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/repository/sale_repository.dart';
import 'package:keevo/features/pos/domain/usecase/record_sale_usecase.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/pos/presentation/provider/record_sale_notifier.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

class MockSaleRepository extends Mock implements SaleRepository {}

class MockLocalSaleDataSource extends Mock implements LocalSaleDataSource {}

class MockLocalProductDataSource extends Mock
    implements LocalProductDataSource {}

class FakeSale extends Fake implements Sale {}

/// The draft path never queues through LocalProductDataSource.
class _NoopSyncService extends Fake implements SyncService {}

CartItem _item({String id = 'p1', int price = 500, int qty = 1}) => CartItem(
      id: id,
      productId: id,
      productName: 'Savon',
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
    );

void main() {
  late MockSaleRepository mockRepo;
  late MockLocalSaleDataSource mockLocalDs;
  late MockLocalProductDataSource mockProductDs;

  setUpAll(() {
    registerFallbackValue(FakeSale());
    // Draft checkout runs inside a real Drift transaction (in-memory DB).
    if (Platform.isLinux) {
      open.overrideFor(OperatingSystem.linux, () {
        try {
          return DynamicLibrary.open('libsqlite3.so');
        } catch (_) {
          return DynamicLibrary.open('libsqlite3.so.0');
        }
      });
    }
  });

  setUp(() {
    mockRepo = MockSaleRepository();
    mockLocalDs = MockLocalSaleDataSource();
    mockProductDs = MockLocalProductDataSource();
  });

  ProviderContainer createContainer(
      {List<CartItem> cart = const [], String? role = 'OWNER'}) {
    final container = ProviderContainer(
      overrides: [
        // Gate is open (0 days — no sync required)
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue(role),
        appDatabaseProvider.overrideWith((ref) {
          final db = AppDatabase.forTesting();
          ref.onDispose(db.close);
          return db;
        }),
        saleRepositoryProvider.overrideWithValue(mockRepo),
        localSaleDataSourceProvider.overrideWithValue(mockLocalDs),
        localProductDataSourceProvider.overrideWithValue(mockProductDs),
        recordSaleUseCaseProvider
            .overrideWithValue(RecordSaleUseCase(mockRepo)),
      ],
    );

    addTearDown(container.dispose);

    // Pre-populate cart
    final notifier = container.read(cartProvider.notifier);
    for (final item in cart) {
      notifier.addItem(item);
    }

    return container;
  }

  group('RecordSaleNotifier', () {
    test('submit success clears cart', () async {
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());
      expect(container.read(cartProvider), isEmpty);
    });

    // v1s-15-8: the notifier itself refuses an empty cart (not only the CTA).
    test('submit with an empty cart records nothing', () async {
      final container = createContainer();
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: const [],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleError>());
      expect((state as RecordSaleError).message, 'EMPTY_CART');
      verifyNever(() => mockRepo.recordSale(any()));
      verifyNever(() => mockLocalDs.getAvailableStock(any(), any()));
    });

    test('submit insufficient stock returns error', () async {
      when(() => mockLocalDs.getAvailableStock('p1', 'store-1'))
          .thenAnswer((_) async => 0);

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleError>());
      expect((state as RecordSaleError).message, 'INSUFFICIENT_STOCK');
      verifyNever(() => mockRepo.recordSale(any()));
    });

    test('submit offline still records locally via use case', () async {
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      // The repository recordSale will be called — it handles
      // offline internally (catches DioException in background push).
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.mobileMoney,
        storeId: 'store-1',
        employeeId: 'emp-1',
        mobileRef: 'ref-123',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());
      verify(() => mockRepo.recordSale(any())).called(1);
    });

    test('submit_blockedByGate_returnsBlockedByGateState', () async {
      // Gate is BLOCKED — 7+ days without sync. SyncGateGuard.assertWriteAllowed()
      // throws WriteBlockedException before any repo or stock check is called.
      final container = ProviderContainer(
        overrides: [
          daysSinceLastSyncProvider.overrideWithValue(7),
          saleRepositoryProvider.overrideWithValue(mockRepo),
          localSaleDataSourceProvider.overrideWithValue(mockLocalDs),
          recordSaleUseCaseProvider
              .overrideWithValue(RecordSaleUseCase(mockRepo)),
        ],
      );
      addTearDown(container.dispose);
      container.read(cartProvider.notifier).addItem(_item());

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
        container.read(recordSaleNotifierProvider),
        isA<RecordSaleBlockedByGate>(),
        reason: 'Gate blocked must set RecordSaleBlockedByGate state',
      );
      verifyNever(() => mockLocalDs.getAvailableStock(any(), any()));
      verifyNever(() => mockRepo.recordSale(any()));
    });

    // ── AC3: Draft product → inline stock + promotion + COMPLETED ─────────

    test('submit_withDraftItem_appliesInitialStockAndPromotesProduct',
        () async {
      final draftItem = CartItem(
        id: 'd1',
        productId: 'd1',
        productName: 'Brouillon',
        unitPrice: 3000,
        appliedUnitPrice: 3000,
        quantity: 1,
        productStatus: ProductStatus.draft,
      );

      // Pre-fill draftInitialStocksProvider
      final container = createContainer(cart: [draftItem]);
      container
          .read(draftInitialStocksProvider.notifier)
          .update((_) => {'d1': 5});

      // Stub local datasources
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      // After stock applied, draft product has 5 stock
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 5);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [draftItem],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());

      // AC3: applyInitialStockEntries called with exact map
      verify(() => mockLocalDs.applyInitialStockEntries(
          {'d1': 5}, 'store-1', 'emp-1',
          movementIds: any(named: 'movementIds'))).called(1);

      // AC3: promoteToActive called for each draft product
      verify(() => mockProductDs.promoteToActive('d1')).called(1);

      // AC3: Sale has status COMPLETED and originalDraftProductIds
      final captured = verify(() => mockRepo.recordSale(captureAny()))
          .captured
          .single as Sale;
      expect(captured.status, 'COMPLETED');
      expect(captured.originalDraftProductIds, ['d1']);

      // AC3: draftInitialStocksProvider cleared after submit
      expect(container.read(draftInitialStocksProvider), isEmpty);
    });

    // ── v1s-12-10: promotion is OWNER-only ─────────────────────────────────

    // Fail closed: an unknown (null) role does not promote either.
    for (final role in <String?>['EMPLOYEE', null]) {
      test('submit_withDraftItem_as${role}_appliesInitialStockButNeverPromotes',
          () async {
        final draftItem = CartItem(
          id: 'd1',
          productId: 'd1',
          productName: 'Brouillon',
          unitPrice: 3000,
          appliedUnitPrice: 3000,
          quantity: 1,
          productStatus: ProductStatus.draft,
        );
        final container = createContainer(cart: [draftItem], role: role);
        container
            .read(draftInitialStocksProvider.notifier)
            .update((_) => {'d1': 5});
        when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
            movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
        when(() => mockLocalDs.getAvailableStock('d1', any()))
            .thenAnswer((_) async => 0);
        when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

        await container.read(recordSaleNotifierProvider.notifier).submit(
          cart: [draftItem],
          mode: PaymentModeEnum.cash,
          storeId: 'store-1',
          employeeId: 'emp-1',
        );

        expect(container.read(recordSaleNotifierProvider),
            isA<RecordSaleSuccess>());
        verify(() => mockLocalDs.applyInitialStockEntries(
            {'d1': 5}, 'store-1', 'emp-1',
            movementIds: any(named: 'movementIds'))).called(1);
        verifyNever(() => mockProductDs.promoteToActive(any()));
        final captured = verify(() => mockRepo.recordSale(captureAny()))
            .captured
            .single as Sale;
        // The owner is still notified about the sold drafts.
        expect(captured.originalDraftProductIds, ['d1']);
        expect(captured.promoteDraftProducts, isFalse);
      });
    }

    test('submit_withDraftItem_asOwner_flagsPromotion', () async {
      final draftItem = CartItem(
        id: 'd1',
        productId: 'd1',
        productName: 'Brouillon',
        unitPrice: 3000,
        appliedUnitPrice: 3000,
        quantity: 1,
        productStatus: ProductStatus.draft,
      );
      final container = createContainer(cart: [draftItem]);
      container
          .read(draftInitialStocksProvider.notifier)
          .update((_) => {'d1': 5});
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draftItem],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      verify(() => mockProductDs.promoteToActive('d1')).called(1);
      final captured = verify(() => mockRepo.recordSale(captureAny()))
          .captured
          .single as Sale;
      expect(captured.promoteDraftProducts, isTrue);
    });

    // ── AC4: No-draft cart — never applies stock or promotes ───────────────

    test('submit_withoutDraftItems_neverAppliesInitialStockOrPromotes',
        () async {
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      final container = createContainer(cart: [_item()]);
      final notifier = container.read(recordSaleNotifierProvider.notifier);

      await notifier.submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect(state, isA<RecordSaleSuccess>());

      // AC4: Never called when no draft items
      verifyNever(() => mockLocalDs.applyInitialStockEntries(
          any(), any(), any(),
          movementIds: any(named: 'movementIds')));
      verifyNever(() => mockProductDs.promoteToActive(any()));
    });
    // ── Draft checkout: stock check must run BEFORE any local mutation ────

    CartItem draft({int qty = 2}) => CartItem(
          id: 'd1',
          productId: 'd1',
          productName: 'Brouillon',
          unitPrice: 3000,
          appliedUnitPrice: 3000,
          quantity: qty,
          productStatus: ProductStatus.draft,
        );

    test('draft with insufficient initial stock leaves everything untouched',
        () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 1};
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final state = container.read(recordSaleNotifierProvider);
      expect((state as RecordSaleError).message, 'INSUFFICIENT_STOCK');
      verifyNever(() => mockLocalDs.applyInitialStockEntries(
          any(), any(), any(),
          movementIds: any(named: 'movementIds')));
      verifyNever(() => mockProductDs.promoteToActive(any()));
      verifyNever(() => mockRepo.recordSale(any()));
      expect(container.read(draftInitialStocksProvider), {'d1': 1});
    });

    test('draft: available + initial entry covers the quantity', () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 2};
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleSuccess>());
      verify(() => mockLocalDs.applyInitialStockEntries(
          {'d1': 2}, 'store-1', 'emp-1',
          movementIds: any(named: 'movementIds'))).called(1);
    });

    test('draft: local movement id and queued sale share the same id',
        () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 2};
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      final ids = verify(() => mockLocalDs.applyInitialStockEntries(
              any(), any(), any(),
              movementIds: captureAny(named: 'movementIds'))).captured.single
          as Map<String, String>;
      final sale = verify(() => mockRepo.recordSale(captureAny()))
          .captured
          .single as Sale;
      expect(ids.keys, ['d1']);
      expect(sale.initialStockMovementIds, ids);
    });

    // v1s-13-8: an entry for a product no longer in the cart (or not a draft
    // in it) is neither applied locally nor queued; the map is cleared.
    test('draft: stale initial stock entries are filtered out and cleared',
        () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {
        'd1': 2,
        'gone': 5,
      };
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleSuccess>());
      final applied = verify(() => mockLocalDs.applyInitialStockEntries(
              captureAny(), any(), any(),
              movementIds: captureAny(named: 'movementIds')))
          .captured;
      expect(applied[0], {'d1': 2});
      expect((applied[1] as Map<String, String>).keys, ['d1']);
      final sale = verify(() => mockRepo.recordSale(captureAny()))
          .captured
          .single as Sale;
      expect(sale.initialStockEntries, {'d1': 2});
      expect(sale.initialStockMovementIds.keys, ['d1']);
      expect(container.read(draftInitialStocksProvider), isEmpty);
    });

    test('no draft in cart: stale entry neither applied nor queued, map cleared',
        () async {
      final container = createContainer(cart: [_item()]);
      container.read(draftInitialStocksProvider.notifier).state = {'gone': 5};
      when(() => mockLocalDs.getAvailableStock(any(), any()))
          .thenAnswer((_) async => 10);
      when(() => mockRepo.recordSale(any())).thenAnswer((_) async {});

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [_item()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleSuccess>());
      verifyNever(() => mockLocalDs.applyInitialStockEntries(
          any(), any(), any(),
          movementIds: any(named: 'movementIds')));
      final sale = verify(() => mockRepo.recordSale(captureAny()))
          .captured
          .single as Sale;
      expect(sale.initialStockEntries, isEmpty);
      expect(sale.initialStockMovementIds, isEmpty);
      expect(container.read(draftInitialStocksProvider), isEmpty);
    });

    // The stubbed datasources above write nothing, so they cannot show a
    // rollback: here the real ones write to the in-memory DB, and a failing
    // sale must leave no credited stock, no movement, and the product DRAFT
    // (otherwise a retry would credit the initial stock twice).
    test('draft: recordSale failure rolls back the real stock and promotion',
        () async {
      final db = AppDatabase.forTesting();
      addTearDown(db.close);
      final now = DateTime.now();
      await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'd1',
          name: 'Brouillon',
          status: const Value('DRAFT'),
          createdAt: now,
          updatedAt: now));
      final container = ProviderContainer(overrides: [
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        appDatabaseProvider.overrideWithValue(db),
        saleRepositoryProvider.overrideWithValue(mockRepo),
        localSaleDataSourceProvider.overrideWithValue(LocalSaleDataSource(db)),
        localProductDataSourceProvider
            .overrideWithValue(LocalProductDataSource(db, _NoopSyncService())),
        recordSaleUseCaseProvider
            .overrideWithValue(RecordSaleUseCase(mockRepo)),
      ]);
      addTearDown(container.dispose);
      container.read(cartProvider.notifier).addItem(draft());
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 5};
      when(() => mockRepo.recordSale(any())).thenThrow(Exception('disk'));

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleError>());
      expect(await db.select(db.stockLevels).get(), isEmpty);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      final product = await (db.select(db.products)
            ..where((p) => p.id.equals('d1')))
          .getSingle();
      expect(product.status, 'DRAFT');
    });

    test('draft: recordSale failure keeps the initial stock entries', () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 5};
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
          movementIds: any(named: 'movementIds'))).thenAnswer((_) async {});
      when(() => mockProductDs.promoteToActive(any())).thenAnswer((_) async {});
      when(() => mockRepo.recordSale(any())).thenThrow(Exception('disk'));

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleError>());
      expect(container.read(draftInitialStocksProvider), {'d1': 5});
    });

    // v1s-15-8 (deferred from CR v1s-13-6): failure inside the draft
    // transaction before the sale is recorded.
    test('draft: applyInitialStockEntries failure records nothing, keeps cart '
        'and entries', () async {
      final container = createContainer(cart: [draft()]);
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 5};
      when(() => mockLocalDs.getAvailableStock('d1', any()))
          .thenAnswer((_) async => 0);
      when(() => mockLocalDs.applyInitialStockEntries(any(), any(), any(),
              movementIds: any(named: 'movementIds')))
          .thenThrow(Exception('disk full'));

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleError>());
      verifyNever(() => mockProductDs.promoteToActive(any()));
      verifyNever(() => mockRepo.recordSale(any()));
      expect(container.read(cartProvider), hasLength(1));
      expect(container.read(draftInitialStocksProvider), {'d1': 5});
    });

    test('draft: promoteToActive failure rolls back the credited stock',
        () async {
      final db = AppDatabase.forTesting();
      addTearDown(db.close);
      final productDs = MockLocalProductDataSource();
      when(() => productDs.promoteToActive(any()))
          .thenThrow(Exception('constraint'));
      final container = ProviderContainer(overrides: [
        daysSinceLastSyncProvider.overrideWithValue(0),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        appDatabaseProvider.overrideWithValue(db),
        saleRepositoryProvider.overrideWithValue(mockRepo),
        localSaleDataSourceProvider.overrideWithValue(LocalSaleDataSource(db)),
        localProductDataSourceProvider.overrideWithValue(productDs),
        recordSaleUseCaseProvider
            .overrideWithValue(RecordSaleUseCase(mockRepo)),
      ]);
      addTearDown(container.dispose);
      container.read(cartProvider.notifier).addItem(draft());
      container.read(draftInitialStocksProvider.notifier).state = {'d1': 5};

      await container.read(recordSaleNotifierProvider.notifier).submit(
        cart: [draft()],
        mode: PaymentModeEnum.cash,
        storeId: 'store-1',
        employeeId: 'emp-1',
      );

      expect(
          container.read(recordSaleNotifierProvider), isA<RecordSaleError>());
      verify(() => productDs.promoteToActive('d1')).called(1);
      verifyNever(() => mockRepo.recordSale(any()));
      expect(await db.select(db.stockLevels).get(), isEmpty);
      expect(await db.select(db.stockMovements).get(), isEmpty);
      expect(container.read(cartProvider), hasLength(1));
      expect(container.read(draftInitialStocksProvider), {'d1': 5});
    });
  });
}
