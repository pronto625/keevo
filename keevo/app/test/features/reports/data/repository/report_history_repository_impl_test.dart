import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/reports/data/datasource/local_report_history_datasource.dart';
import 'package:keevo/features/reports/data/datasource/remote_report_history_datasource.dart';
import 'package:keevo/features/reports/data/repository/report_history_repository_impl.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:mocktail/mocktail.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────────

class MockLocalReportHistoryDataSource extends Mock
    implements LocalReportHistoryDataSource {}

class MockRemoteReportHistoryDataSource extends Mock
    implements RemoteReportHistoryDataSource {}

class FakeReportHistoryModel extends Fake implements ReportHistoryModel {}

// ── Fixtures ──────────────────────────────────────────────────────────────────

ReportHistoryModel _testReport({String id = 'rpt-1'}) => ReportHistoryModel(
      id: id,
      tenantId: 'tenant-abc',
      storeId: 'store-1',
      storeName: 'Boutique Test',
      reportType: 'DAILY',
      reportDate: DateTime(2025, 6, 20),
      content: 'Rapport du jour',
      deliveryStatus: 'SENT',
      deliveryAttempts: 1,
      lastAttemptAt: null,
      totalRevenue: 1500000,
      totalSales: 12,
      isAutomatic: true,
      createdAt: DateTime(2025, 6, 20, 22, 0),
    );

void main() {
  late MockLocalReportHistoryDataSource mockLocal;
  late MockRemoteReportHistoryDataSource mockRemote;
  late ReportHistoryRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeReportHistoryModel()));

  setUp(() {
    mockLocal = MockLocalReportHistoryDataSource();
    mockRemote = MockRemoteReportHistoryDataSource();
    repo = ReportHistoryRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
    );
  });

  group('getReportHistory', () {
    test('online — fetches from remote and caches locally', () async {
      final remoteReports = [_testReport(id: 'rpt-1'), _testReport(id: 'rpt-2')];
      when(() => mockRemote.fetchHistory(page: any(named: 'page'), size: any(named: 'size'), type: any(named: 'type')))
          .thenAnswer((_) async => remoteReports);
      when(() => mockLocal.upsertReport(any())).thenAnswer((_) async {});

      final result = await repo.getReportHistory(page: 0, size: 20);

      expect(result, equals(remoteReports));
      verify(() => mockRemote.fetchHistory(page: 0, size: 20, type: null)).called(1);
      verify(() => mockLocal.upsertReport(any())).called(2);
    });

    test('remote fails — falls back to local cache', () async {
      final localReports = [_testReport()];
      when(() => mockRemote.fetchHistory(page: any(named: 'page'), size: any(named: 'size'), type: any(named: 'type')))
          .thenThrow(Exception('Network error'));
      when(() => mockLocal.getHistory(page: any(named: 'page'), size: any(named: 'size'), type: any(named: 'type')))
          .thenAnswer((_) async => localReports);

      final result = await repo.getReportHistory(page: 0, size: 20);

      expect(result, equals(localReports));
      verify(() => mockLocal.getHistory(page: 0, size: 20, type: null)).called(1);
    });

    test('passes type filter to remote', () async {
      when(() => mockRemote.fetchHistory(page: any(named: 'page'), size: any(named: 'size'), type: any(named: 'type')))
          .thenAnswer((_) async => []);
      when(() => mockLocal.upsertReport(any())).thenAnswer((_) async {});

      await repo.getReportHistory(page: 0, size: 10, type: 'DAILY');

      verify(() => mockRemote.fetchHistory(page: 0, size: 10, type: 'DAILY')).called(1);
    });
  });

  group('getReportById', () {
    test('online — fetches from remote and caches', () async {
      final report = _testReport();
      when(() => mockRemote.fetchById(any())).thenAnswer((_) async => report);
      when(() => mockLocal.upsertReport(any())).thenAnswer((_) async {});

      final result = await repo.getReportById('rpt-1');

      expect(result, equals(report));
      verify(() => mockRemote.fetchById('rpt-1')).called(1);
      verify(() => mockLocal.upsertReport(report)).called(1);
    });

    test('remote fails — returns from local cache', () async {
      final localReport = _testReport();
      when(() => mockRemote.fetchById(any())).thenThrow(Exception('not found'));
      when(() => mockLocal.getById(any())).thenAnswer((_) async => localReport);

      final result = await repo.getReportById('rpt-1');

      expect(result, equals(localReport));
      verify(() => mockLocal.getById('rpt-1')).called(1);
    });
  });

  group('resendReport', () {
    test('delegates to remote resend', () async {
      when(() => mockRemote.resend(any())).thenAnswer((_) async {});

      await repo.resendReport('rpt-1');

      verify(() => mockRemote.resend('rpt-1')).called(1);
      verifyNever(() => mockLocal.upsertReport(any()));
    });

    test('propagates exception when remote fails', () async {
      when(() => mockRemote.resend(any())).thenThrow(Exception('Server error'));

      expect(
        () => repo.resendReport('rpt-1'),
        throwsA(isA<Exception>()),
      );
    });
  });
}
