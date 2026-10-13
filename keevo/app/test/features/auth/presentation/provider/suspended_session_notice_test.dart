import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/network/auth_interceptor.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';

import 'package:keevo/core/widget/notice_throttle.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

class _MockTokenStorage extends Mock implements TokenStorage {}

class _MockDio extends Mock implements Dio {}

class _MockErrorHandler extends Mock implements ErrorInterceptorHandler {}

/// v1s-16-13: N concurrent SESSION_REVOKED → exactly one SnackBar.
void main() {
  testWidgets('5 reports in the window show one SnackBar', (tester) async {
    final key = GlobalKey<ScaffoldMessengerState>();
    await tester.pumpWidget(MaterialApp(
      scaffoldMessengerKey: key,
      home: const Scaffold(body: SizedBox()),
    ));

    final throttle = NoticeThrottle();
    for (var i = 0; i < 5; i++) {
      showSuspendedSessionNotice(throttle, key.currentState);
    }
    await tester.pump();

    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.text('Votre accès a été suspendu, veuillez vous reconnecter.'),
        findsOneWidget);

    // Nothing queued behind it.
    for (var i = 0; i < 3; i++) {
      await tester.pump(const Duration(seconds: 5));
    }
    await tester.pumpAndSettle();
    expect(find.byType(SnackBar), findsNothing);
  });

  // v1s-16-13 follow-up: through the real AuthInterceptor, with the app-wide
  // throttle from its provider (what `dioProvider` wires).
  testWidgets('5 concurrent SESSION_REVOKED through the interceptor → one '
      'SnackBar', (tester) async {
    registerFallbackValue(DioException(requestOptions: RequestOptions(path: '/')));
    final key = GlobalKey<ScaffoldMessengerState>();
    await tester.pumpWidget(MaterialApp(
      scaffoldMessengerKey: key,
      home: const Scaffold(body: SizedBox()),
    ));
    final container = ProviderContainer();
    addTearDown(container.dispose);
    expect(container.read(suspendedSessionNoticeThrottleProvider),
        same(container.read(suspendedSessionNoticeThrottleProvider)),
        reason: 'one throttle shared by every request');

    final storage = _MockTokenStorage();
    when(() => storage.clearAll()).thenAnswer((_) async {});
    final interceptor = AuthInterceptor(
      storage: storage,
      dio: _MockDio(),
      refreshDio: _MockDio(),
      onSessionExpired: () {},
      onAccountSuspended: () => showSuspendedSessionNotice(
          container.read(suspendedSessionNoticeThrottleProvider),
          key.currentState),
    );
    final handler = _MockErrorHandler();
    when(() => handler.next(any())).thenReturn(null);

    for (var i = 0; i < 5; i++) {
      final request = RequestOptions(path: '/api/v1/products/$i');
      interceptor.onError(
          DioException(
            requestOptions: request,
            response: Response(
                requestOptions: request,
                statusCode: 401,
                data: {'domainCode': 'SESSION_REVOKED'}),
          ),
          handler);
    }
    await tester.pump();
    await tester.pump();

    verify(() => handler.next(any())).called(5);
    expect(find.byType(SnackBar), findsOneWidget);
    for (var i = 0; i < 3; i++) {
      await tester.pump(const Duration(seconds: 5));
    }
    await tester.pumpAndSettle();
    expect(find.byType(SnackBar), findsNothing);
  });

  test('no messenger mounted: the window is not used up', () {
    final throttle = NoticeThrottle();
    showSuspendedSessionNotice(throttle, null);
    expect(throttle.tryAcquire(), isTrue,
        reason: 'the next report can still show the notice');
  });

  // v1s-16-13 follow-up: the wiring of dioProvider itself (shared throttle,
  // root messenger), not a hand-built interceptor.
  testWidgets('dioProvider: 5 suspended-session reports → one SnackBar',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      scaffoldMessengerKey: rootScaffoldMessengerKey,
      home: const Scaffold(body: SizedBox()),
    ));
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final interceptor = container
        .read(dioProvider)
        .interceptors
        .whereType<AuthInterceptor>()
        .single;

    for (var i = 0; i < 5; i++) {
      interceptor.onAccountSuspended!();
    }
    await tester.pump();

    expect(find.byType(SnackBar), findsOneWidget);
    for (var i = 0; i < 3; i++) {
      await tester.pump(const Duration(seconds: 5));
    }
    await tester.pumpAndSettle();
    expect(find.byType(SnackBar), findsNothing);
  });
}
