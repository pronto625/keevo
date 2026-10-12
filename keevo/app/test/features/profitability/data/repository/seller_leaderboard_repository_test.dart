import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/features/profitability/data/datasource/local_profitability_datasource.dart';
import 'package:keevo/features/profitability/data/datasource/remote_profitability_datasource.dart';
import 'package:keevo/features/profitability/data/repository/profitability_repository_impl.dart';
import 'package:keevo/features/profitability/domain/model/seller_leaderboard_model.dart';

class _MockLocal extends Mock implements LocalProfitabilityDatasource {}

class _MockRemote extends Mock implements RemoteProfitabilityDatasource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

/// v1s-14-2 — seller leaderboard: backend first when online; API errors are
/// rethrown, only transport failures fall back to Drift.
void main() {
  late _MockLocal local;
  late _MockRemote remote;
  late _MockConnectivity connectivity;
  late ProfitabilityRepositoryImpl sut;

  final params = SellerLeaderboardParams(
      from: DateTime(2026, 10, 1), to: DateTime(2026, 10, 7), storeId: 's1');

  const remoteRow = SellerLeaderboardEntry(
      rank: 1,
      sellerId: 'u1',
      sellerName: 'Awa',
      owner: false,
      salesCount: 2,
      totalRevenue: 10000,
      averageBasket: 5000);
  const localRow = SellerLeaderboardEntry(
      rank: 0,
      sellerId: 'u1',
      sellerName: 'Awa',
      owner: false,
      salesCount: 1,
      totalRevenue: 4000,
      averageBasket: 4000);

  void stubRemote(Future<List<SellerLeaderboardEntry>> Function() answer) =>
      when(() => remote.fetchSellerLeaderboard(
            from: any(named: 'from'),
            to: any(named: 'to'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) => answer());

  void stubLocal() => when(() => local.getSellerLeaderboard(
        from: any(named: 'from'),
        to: any(named: 'to'),
        storeId: any(named: 'storeId'),
      )).thenAnswer((_) async => [localRow]);

  setUp(() {
    local = _MockLocal();
    remote = _MockRemote();
    connectivity = _MockConnectivity();
    sut = ProfitabilityRepositoryImpl(
        local: local, remote: remote, connectivity: connectivity);
  });

  test('online → remote with formatted dates and store, local untouched',
      () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    stubRemote(() async => [remoteRow]);

    final result = await sut.getSellerLeaderboard(params: params);

    expect(result.single.totalRevenue, 10000);
    verify(() => remote.fetchSellerLeaderboard(
        from: '2026-10-01', to: '2026-10-07', storeId: 's1')).called(1);
    verifyNever(() => local.getSellerLeaderboard(
        from: any(named: 'from'),
        to: any(named: 'to'),
        storeId: any(named: 'storeId')));
  });

  test('offline → local Drift aggregation', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => false);
    stubLocal();

    final result = await sut.getSellerLeaderboard(params: params);

    expect(result.single.totalRevenue, 4000);
    verify(() => local.getSellerLeaderboard(
        from: params.from, to: params.to, storeId: 's1')).called(1);
  });

  test('transport failure (no response) → falls back to local', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    stubRemote(() async => throw DioException(
        requestOptions: RequestOptions(path: '/x'),
        type: DioExceptionType.connectionError));
    stubLocal();

    final result = await sut.getSellerLeaderboard(params: params);
    expect(result.single.totalRevenue, 4000);
  });

  test('API error (403) → rethrown, no local fallback', () async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    final opts = RequestOptions(path: '/api/v1/reporting/leaderboard');
    stubRemote(() async => throw DioException(
        requestOptions: opts,
        response: Response(requestOptions: opts, statusCode: 403)));

    await expectLater(sut.getSellerLeaderboard(params: params),
        throwsA(isA<DioException>()));
    verifyNever(() => local.getSellerLeaderboard(
        from: any(named: 'from'),
        to: any(named: 'to'),
        storeId: any(named: 'storeId')));
  });
}
