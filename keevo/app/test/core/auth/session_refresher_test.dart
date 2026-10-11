import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/auth/session_refresher.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';

class _MockTokenStorage extends Mock implements TokenStorage {}

class _MockDio extends Mock implements Dio {}

Response<Map<String, dynamic>> _tokens(String access, String refresh) => Response(
      requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
      statusCode: 200,
      data: {'accessToken': access, 'refreshToken': refresh},
    );

DioException _httpError(int status) => DioException(
      requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
      response: Response(
        requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
        statusCode: status,
      ),
    );

void main() {
  late _MockTokenStorage storage;
  late _MockDio dio;
  late List<String> renewed;
  late SessionRefresher refresher;

  setUp(() {
    storage = _MockTokenStorage();
    dio = _MockDio();
    renewed = [];
    refresher = SessionRefresher(
      storage: storage,
      refreshDio: dio,
      onRenewed: (token) async => renewed.add(token),
    );
    when(() => storage.getRefreshToken()).thenAnswer((_) async => 'r1');
    when(() => storage.saveToken(any())).thenAnswer((_) async {});
    when(() => storage.saveRefreshToken(any())).thenAnswer((_) async {});
  });

  void refreshAnswers(Future<Response<Map<String, dynamic>>> Function() answer) {
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenAnswer((_) => answer());
  }

  test('renewed: both tokens persisted, listener told', () async {
    refreshAnswers(() async => _tokens('a2', 'r2'));

    expect(await refresher.refresh(), RefreshOutcome.renewed);

    verify(() => storage.saveToken('a2')).called(1);
    verify(() => storage.saveRefreshToken('r2')).called(1);
    expect(renewed, ['a2']);
  });

  test('concurrent callers share one refresh call', () async {
    final gate = Completer<Response<Map<String, dynamic>>>();
    refreshAnswers(() => gate.future);

    final first = refresher.refresh();
    final second = refresher.refresh();
    gate.complete(_tokens('a2', 'r2'));

    expect(await Future.wait([first, second]),
        [RefreshOutcome.renewed, RefreshOutcome.renewed]);
    verify(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .called(1);
  });

  test('a later call after completion refreshes again', () async {
    refreshAnswers(() async => _tokens('a2', 'r2'));

    await refresher.refresh();
    await refresher.refresh();

    verify(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .called(2);
  });

  test('4xx from the server: rejected, nothing persisted', () async {
    refreshAnswers(() async => throw _httpError(401));

    expect(await refresher.refresh(), RefreshOutcome.rejected);
    verifyNever(() => storage.saveToken(any()));
  });

  test('no network: unreachable (the session must survive)', () async {
    refreshAnswers(() async => throw DioException(
          requestOptions: RequestOptions(path: '/api/v1/auth/refresh'),
          type: DioExceptionType.connectionError,
        ));

    expect(await refresher.refresh(), RefreshOutcome.unreachable);
  });

  // A throttle or timeout from a proxy says nothing about the token: signing
  // the shopkeeper out for it would cost them their session.
  for (final status in [408, 409, 429]) {
    test('$status: unreachable, the session is kept', () async {
      refreshAnswers(() async => throw _httpError(status));

      expect(await refresher.refresh(), RefreshOutcome.unreachable);
    });
  }

  test('5xx: unreachable, not a verdict on the token', () async {
    refreshAnswers(() async => throw _httpError(503));

    expect(await refresher.refresh(), RefreshOutcome.unreachable);
  });

  test('no refresh token stored: rejected without calling the server', () async {
    when(() => storage.getRefreshToken()).thenAnswer((_) async => null);

    expect(await refresher.refresh(), RefreshOutcome.rejected);
    verifyNever(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
  });
}
