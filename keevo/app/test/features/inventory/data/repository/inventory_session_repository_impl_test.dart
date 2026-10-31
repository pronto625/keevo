import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/data/datasource/local_inventory_session_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_inventory_session_datasource.dart';
import 'package:keevo/features/inventory/data/repository/inventory_session_repository_impl.dart';
import 'package:keevo/features/inventory/domain/model/inventory_session_model.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';

class _MockLocal extends Mock implements LocalInventorySessionDataSource {
  // Runs the transactional block directly (no database behind the mock).
  @override
  Future<T> transaction<T>(Future<T> Function() action) => action();
}

class _MockRemote extends Mock implements RemoteInventorySessionDataSource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

class _MockSyncService extends Mock implements SyncService {}

/// v1s-16-12: an online session creation refused by the server (e.g. 409
/// INVENTORY_SESSION_ALREADY_ACTIVE) used to fall back to an offline create,
/// which the server then rejected at sync — a guaranteed ghost session. Only
/// an unreachable server may fall back offline.
void main() {
  late _MockLocal local;
  late _MockRemote remote;
  late _MockSyncService sync;
  late InventorySessionRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(InventorySessionModel(
      id: 'x',
      storeId: 's',
      scope: 'FULL',
      status: 'IN_PROGRESS',
      startedBy: '',
      startedAt: DateTime(2026),
      updatedAt: DateTime(2026),
    ));
  });

  setUp(() {
    local = _MockLocal();
    remote = _MockRemote();
    sync = _MockSyncService();
    final connectivity = _MockConnectivity();
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    when(() => local.insert(any())).thenAnswer((_) async {});
    when(() => local.upsert(any())).thenAnswer((_) async {});
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
    repo = InventorySessionRepositoryImpl(
      local: local,
      remote: remote,
      connectivity: connectivity,
      syncService: sync,
    );
  });

  void stubRemoteCreate(Future<InventorySessionModel> Function() answer) {
    when(() => remote.create(
          storeId: any(named: 'storeId'),
          scope: any(named: 'scope'),
          categoryIds: any(named: 'categoryIds'),
        )).thenAnswer((_) => answer());
  }

  test('a server refusal (409) is rethrown without a local row or queue entry',
      () async {
    final options = RequestOptions(path: '/api/v1/inventory/sessions');
    stubRemoteCreate(() async => throw DioException(
          requestOptions: options,
          response: Response(requestOptions: options, statusCode: 409, data: {
            'code': 'INVENTORY_SESSION_ALREADY_ACTIVE',
          }),
          type: DioExceptionType.badResponse,
        ));

    await expectLater(
      repo.create(storeId: 'store-1', scope: 'FULL'),
      throwsA(isA<DioException>()),
    );

    verifyNever(() => local.insert(any()));
    verifyNever(() => local.upsert(any()));
    verifyNever(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        ));
  });

  test('a network error falls back to an offline create + queue entry',
      () async {
    stubRemoteCreate(() async => throw DioException(
          requestOptions: RequestOptions(path: '/api/v1/inventory/sessions'),
          type: DioExceptionType.connectionError,
        ));

    final session = await repo.create(storeId: 'store-1', scope: 'FULL');

    expect(session.status, 'IN_PROGRESS');
    verify(() => local.insert(any())).called(1);
    verify(() => sync.queueOperation(
          operation: 'CREATE_INVENTORY_SESSION',
          payload: any(named: 'payload'),
          entityId: session.id,
        )).called(1);
  });

  test('a 503 from the gateway (backend down) falls back offline', () async {
    final options = RequestOptions(path: '/api/v1/inventory/sessions');
    stubRemoteCreate(() async => throw DioException(
          requestOptions: options,
          response: Response(requestOptions: options, statusCode: 503),
          type: DioExceptionType.badResponse,
        ));

    final session = await repo.create(storeId: 'store-1', scope: 'FULL');

    verify(() => local.insert(any())).called(1);
    verify(() => sync.queueOperation(
          operation: 'CREATE_INVENTORY_SESSION',
          payload: any(named: 'payload'),
          entityId: session.id,
        )).called(1);
  });

  test('an online create is stored as synced (upsert)', () async {
    final server = InventorySessionModel(
      id: 'server-1',
      storeId: 'store-1',
      scope: 'FULL',
      status: 'IN_PROGRESS',
      startedBy: 'u',
      startedAt: DateTime(2026),
      updatedAt: DateTime(2026),
    );
    stubRemoteCreate(() async => server);

    await repo.create(storeId: 'store-1', scope: 'FULL');

    verify(() => local.upsert(server)).called(1);
    verifyNever(() => local.insert(any()));
  });

  // 13.10b — a Free tenant: 403 MODULE_NOT_ENTITLED reaches the screen, no
  // local fallback / ghost session.
  group('module refusal', () {
    DioException refusal() {
      final options = RequestOptions(path: '/api/v1/inventory/sessions');
      return DioException(
        requestOptions: options,
        response: Response(
            requestOptions: options,
            statusCode: 403,
            data: {'domainCode': 'MODULE_NOT_ENTITLED'}),
      );
    }

    final isRefusal =
        throwsA(predicate((e) => ModuleEntitlementException.of(e) != null));

    test('create: rethrown, nothing written or queued', () async {
      stubRemoteCreate(() async => throw refusal());
      await expectLater(
          repo.create(storeId: 's1', scope: 'FULL'), isRefusal);
      verifyNever(() => local.insert(any()));
      verifyNever(() => sync.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
    });

    test('getActive / getHistory / cancel: rethrown, no local fallback',
        () async {
      when(() => remote.getActive(any())).thenThrow(refusal());
      when(() => remote.getHistory(
          page: any(named: 'page'),
          size: any(named: 'size'))).thenThrow(refusal());
      when(() => remote.cancel(any())).thenThrow(refusal());

      await expectLater(repo.getActiveByStoreId('s1'), isRefusal);
      await expectLater(repo.getHistory(), isRefusal);
      await expectLater(repo.cancel('session-1'), isRefusal);
      verifyNever(() => local.findActiveByStoreId(any()));
      verifyNever(() => local.updateStatus(any(), any(),
          cancelledBy: any(named: 'cancelledBy'),
          cancelledAt: any(named: 'cancelledAt')));
    });
  });
}
