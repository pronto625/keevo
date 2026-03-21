import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/pos/data/datasource/local_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/repository/day_closure_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
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
      when(() => mockRemote.pushClosure(any())).thenAnswer((_) async {});
      when(() => mockLocal.insertClosure(any(), synced: true))
          .thenAnswer((_) async {});

      await repo.saveClosureLocally(closure);

      verify(() => mockRemote.pushClosure(closure)).called(1);
      verify(() => mockLocal.insertClosure(closure, synced: true)).called(1);
    });

    test('online but backend fails — saves locally unsynced + queues sync',
        () async {
      final closure = _testClosure();
      when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
      when(() => mockRemote.pushClosure(any()))
          .thenThrow(Exception('Network error'));
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
}
