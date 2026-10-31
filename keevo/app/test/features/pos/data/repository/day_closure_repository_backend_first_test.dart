import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/repository/day_closure_repository_impl.dart';
import 'package:keevo/features/pos/domain/exception/day_already_closed_exception.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalDayClosureDataSource extends Mock
    implements LocalDayClosureDataSource {}

class MockRemoteDayClosureDataSource extends Mock
    implements RemoteDayClosureDataSource {}

class MockConnectivityService extends Mock implements ConnectivityService {}

class MockSyncService extends Mock implements SyncService {}

class FakeDayClosure extends Fake implements DayClosure {}

DayClosure _testClosure({String id = 'closure-1'}) => DayClosure(
      id: id,
      storeId: 'store-1',
      actorId: 'emp-1',
      closedAt: DateTime(2026, 3, 17, 18, 0),
      summary: const DayClosureSummary(
        totalSales: 10,
        totalRevenue: 50000,
        cashAmount: 30000,
        momoAmount: 20000,
        topProductQty: 5,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      ),
      isAutomatic: false,
      synced: false,
    );

void main() {
  late MockLocalDayClosureDataSource mockLocal;
  late MockRemoteDayClosureDataSource mockRemote;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late DayClosureRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeDayClosure()));

  setUp(() {
    mockLocal = MockLocalDayClosureDataSource();
    mockRemote = MockRemoteDayClosureDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    repo = DayClosureRepositoryImpl(
      localDataSource: mockLocal,
      remoteDataSource: mockRemote,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
    );
  });

  group('saveClosureLocally — backend-first', () {
    test('online — pushes to backend first, saves locally as synced',
        () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any())).thenAnswer((_) async => null);
      when(() => mockLocal.insertClosure(any(), synced: true))
          .thenAnswer((_) async {});

      final summary = await repo.saveClosureLocally(closure);

      verify(() => mockRemote.pushClosure(closure)).called(1);
      verify(() => mockLocal.insertClosure(closure, synced: true)).called(1);
      expect(summary, same(closure.summary));
    });

    // The server's totals also count sales made offline before the previous
    // closure but synced after it: they are the ones kept and shown.
    test('online — stores and returns the summary the server computed',
        () async {
      final closure = _testClosure();
      const serverSummary = DayClosureSummary(
        totalSales: 11,
        totalRevenue: 52000,
        cashAmount: 32000,
        momoAmount: 20000,
        topProductQty: 5,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      );
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any()))
          .thenAnswer((_) async => serverSummary);
      when(() => mockLocal.insertClosure(any(), synced: true))
          .thenAnswer((_) async {});

      final summary = await repo.saveClosureLocally(closure);

      expect(summary.totalRevenue, 52000);
      final stored = verify(() => mockLocal.insertClosure(captureAny(),
              synced: true))
          .captured
          .single as DayClosure;
      expect(stored.id, closure.id);
      expect(stored.summary.totalSales, 11);
      expect(stored.summary.totalRevenue, 52000);
    });

    test('online, day already closed by another device (409) — nothing '
        'stored or queued, a pull is started, the user is told', () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any())).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/day-closures'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/day-closures'),
          statusCode: 409,
          data: {'domainCode': 'DAY_ALREADY_CLOSED'},
        ),
      ));
      when(() => mockSyncService.pull()).thenAnswer((_) async {});

      await expectLater(repo.saveClosureLocally(closure),
          throwsA(isA<DayAlreadyClosedException>()));

      verifyNever(() => mockLocal.insertClosure(any(), synced: any(named: 'synced')));
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      verify(() => mockSyncService.pull()).called(1);
    });

    test('online, other server refusal — surfaced, nothing queued', () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any())).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/day-closures'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/day-closures'),
          statusCode: 403,
        ),
      ));

      await expectLater(
          repo.saveClosureLocally(closure), throwsA(isA<DioException>()));

      verifyNever(() => mockLocal.insertClosure(any(), synced: any(named: 'synced')));
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
    });

    test('online but backend unreachable — saves locally unsynced + queues sync',
        () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any())).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/day-closures'),
        type: DioExceptionType.connectionError,
      ));
      when(() => mockLocal.insertClosure(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.saveClosureLocally(closure);

      verify(() => mockLocal.insertClosure(closure, synced: false)).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_DAY_CLOSURE',
            payload: any(named: 'payload'),
            entityId: closure.id,
          )).called(1);
    });

    test('offline — saves locally unsynced + queues sync', () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
      when(() => mockLocal.insertClosure(any(), synced: false))
          .thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      await repo.saveClosureLocally(closure);

      verifyNever(() => mockRemote.pushClosure(any()));
      verify(() => mockLocal.insertClosure(closure, synced: false)).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_DAY_CLOSURE',
            payload: any(named: 'payload'),
            entityId: closure.id,
          )).called(1);
    });
  });

  // The server closes the day the closure was made, under the same id.
  test('offline — queued payload carries id and closedAt in UTC', () async {
    final closure = _testClosure();
    when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
    when(() => mockLocal.insertClosure(any(), synced: false))
        .thenAnswer((_) async {});
    when(() => mockSyncService.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});

    await repo.saveClosureLocally(closure);

    final payload = verify(() => mockSyncService.queueOperation(
          operation: 'CREATE_DAY_CLOSURE',
          payload: captureAny(named: 'payload'),
          entityId: closure.id,
        )).captured.single as Map<String, dynamic>;
    expect(payload['id'], closure.id);
    expect(payload['storeId'], closure.storeId);
    expect(payload['closedAt'], closure.closedAt.toUtc().toIso8601String());
  });
}
