import 'dart:async';
import 'dart:io';

import 'package:drift/native.dart' show SqliteException;
import 'package:flutter/services.dart' show PlatformException;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/core/widget/soft_icon_badge.dart';

import '../../helpers/no_technical_text.dart';

Widget _host(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('renders soft badge, default title, human message and retry',
      (tester) async {
    await tester.pumpWidget(_host(AppErrorWidget(
      error: Exception('Base locale indisponible'),
      onRetry: () {},
    )));

    expect(find.byType(SoftIconBadge), findsOneWidget);
    expect(find.text('Un problème est survenu'), findsOneWidget);
    expect(find.text('Base locale indisponible'), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
    expect(find.byIcon(Icons.refresh_rounded), findsOneWidget);
  });

  testWidgets('tapping "Réessayer" calls onRetry every time', (tester) async {
    var calls = 0;
    await tester.pumpWidget(_host(AppErrorWidget(
      error: Exception('boom'),
      onRetry: () => calls++,
    )));

    await tester.tap(find.byType(FilledButton));
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pump();

    expect(calls, 2);
  });

  // v1s-16-11 follow-up: a provider refreshed from an error keeps showing it
  // until the new result arrives — the tap gets its own feedback.
  testWidgets('tap shows « Nouvel essai… » until a new error arrives',
      (tester) async {
    final first = Exception('Base locale indisponible');
    await tester.pumpWidget(_host(AppErrorWidget(error: first, onRetry: () {})));

    await tester.tap(find.text('Réessayer'));
    await tester.pump();
    expect(find.text('Nouvel essai…'), findsOneWidget);
    expect(find.text('Réessayer'), findsNothing);

    // Same error rebuilt (refresh in flight) → still retrying.
    await tester.pumpWidget(_host(AppErrorWidget(error: first, onRetry: () {})));
    expect(find.text('Nouvel essai…'), findsOneWidget);

    // The retry failed again → button back to « Réessayer ».
    await tester.pumpWidget(_host(AppErrorWidget(
        error: Exception('Base locale indisponible'), onRetry: () {})));
    expect(find.text('Réessayer'), findsOneWidget);
  });

  testWidgets('only the explanation is a live region, not the button',
      (tester) async {
    await tester.pumpWidget(_host(AppErrorWidget(
      error: Exception('Base locale indisponible'),
      onRetry: () {},
    )));

    final live = find.byWidgetPredicate(
        (w) => w is Semantics && w.properties.liveRegion == true);
    expect(live, findsOneWidget);
    expect(find.descendant(of: live, matching: find.text('Un problème est survenu')),
        findsOneWidget);
    expect(find.descendant(of: live, matching: find.byType(FilledButton)),
        findsNothing);
  });

  testWidgets('custom title and message override the defaults', (tester) async {
    await tester.pumpWidget(_host(AppErrorWidget(
      error: Exception('technical detail'),
      title: 'Impossible de charger l\'abonnement',
      message: 'Vérifiez votre connexion.',
      onRetry: () {},
    )));

    expect(find.text('Impossible de charger l\'abonnement'), findsOneWidget);
    expect(find.text('Vérifiez votre connexion.'), findsOneWidget);
    expect(find.text('Un problème est survenu'), findsNothing);
    expect(find.textContaining('technical detail'), findsNothing);
  });

  testWidgets('an error carrying a UUID shows the generic message',
      (tester) async {
    await tester.pumpWidget(_host(AppErrorWidget(
      error: Exception('Row 3f2504e0-4f89-11d3-9a0c-0305e82c3301 not found'),
      onRetry: () {},
    )));

    expect(
      find.text('Une erreur inattendue est survenue. Réessayez plus tard.'),
      findsOneWidget,
    );
    expect(find.textContaining('3f2504e0'), findsNothing);
  });

  testWidgets('does not overflow in a 150px-high slot', (tester) async {
    await tester.pumpWidget(_host(Center(
      child: SizedBox(
        height: 150,
        width: 300,
        child: AppErrorWidget(
          error: Exception('Une erreur assez longue pour prendre de la place '
              'sur plusieurs lignes dans un emplacement très court'),
          onRetry: () {},
        ),
      ),
    )));

    expect(tester.takeException(), isNull);
    expect(find.byType(SingleChildScrollView), findsOneWidget);
  });

  group('appErrorMessage — v1s-16-20: no technical data', () {
    DioException dio(Object? body, {int status = 409}) => DioException(
          requestOptions: RequestOptions(path: '/x'),
          type: DioExceptionType.badResponse,
          response: Response(
              requestOptions: RequestOptions(path: '/x'),
              statusCode: status,
              data: body),
        );

    test('server message with a UUID → generic French message', () {
      final msg = appErrorMessage(dio({
        'message':
            'Transfer 7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f has status COMPLETED'
      }));
      expect(msg, isNot(contains('7f1c2b4e')));
      expect(msg, isNot(contains('COMPLETED')));
      expectNoTechnicalString(msg);
    });

    test('unknown domain code → generic, never « Erreur (CODE) »', () {
      final msg = appErrorMessage(dio({'domainCode': 'SOME_NEW_CODE'}));
      expect(msg, kAppGenericErrorMessage);
    });

    test('no body → no HTTP status code in the message', () {
      final msg = appErrorMessage(dio(null, status: 500));
      expect(msg, isNot(contains('500')));
    });

    test('app domain exception: its French message is shown', () {
      expect(appErrorMessage(_DomainException('Client introuvable')),
          'Client introuvable');
      expect(
          appErrorMessage(_DomainException('Erreur PLAN_LIMIT_EXCEEDED')),
          kAppGenericErrorMessage);
    });

    test('body fields that are not Strings never throw', () {
      expect(
          appErrorMessage(dio({
            'error': {'code': 'X'},
            'message': ['a']
          })),
          isNotEmpty);
      expect(appErrorMessage(dio({'domainCode': 42, 'message': 'Nom pris'})),
          'Nom pris');
    });

    test('readable French server message is kept', () {
      expect(appErrorMessage(dio({'message': 'Nom déjà utilisé'})),
          'Nom déjà utilisé');
    });

    test('exception text (class name, codes) → generic message', () {
      expect(appErrorMessage(const FormatException('bad')),
          kAppGenericErrorMessage);
      expect(appErrorMessage(Exception('PLAN_LIMIT_EXCEEDED')),
          kAppGenericErrorMessage);
      expect(appErrorMessage(Exception('Connexion perdue')), 'Connexion perdue');
    });

    test('English platform / IO / SQLite texts are never shown', () {
      expect(appErrorMessage(const SocketException('Failed host lookup')),
          kAppGenericErrorMessage);
      expect(appErrorMessage(SqliteException(5, 'database is locked')),
          kAppGenericErrorMessage);
      expect(appErrorMessage(PlatformException(code: 'x', message: 'denied')),
          kAppGenericErrorMessage);
      expect(appErrorMessage(_Opaque()), kAppGenericErrorMessage);
      expect(appErrorMessage(Exception('HTTP_404')), kAppGenericErrorMessage);
    });

    test('framework reason phrase in the body is not shown', () {
      expect(appErrorMessage(dio({'error': 'Not Found'})),
          isNot('Not Found'));
      expect(appErrorMessage(dio({'error': 'Bad Request', 'message': 'Nom requis'})),
          'Nom requis');
    });

    test('Dio error wrapping a socket error → connection message', () {
      final e = DioException(
        requestOptions: RequestOptions(path: '/x'),
        type: DioExceptionType.unknown,
        error: const SocketException('Network is unreachable'),
      );
      expect(appErrorMessage(e), contains('Vérifiez votre réseau'));
    });

    test('TimeoutException text is never shown', () {
      expect(appErrorMessage(TimeoutException('Future not completed')),
          kAppGenericErrorMessage);
    });

    test('error stored as text: readable French kept, technical hidden', () {
      expect(
          appErrorMessage(
              'Produit introuvable sur le serveur. Validez le brouillon d\'abord.'),
          'Produit introuvable sur le serveur. Validez le brouillon d\'abord.');
      expect(appErrorMessage('Exception: SqliteException(5): database is locked'),
          kAppGenericErrorMessage);
      expect(appErrorMessage(''), kAppGenericErrorMessage);
    });
  });
}

class _DomainException implements Exception {
  final String message;
  _DomainException(this.message);

  @override
  String toString() => 'DomainException[X_CODE]: $message';
}

/// An exception with neither a message nor a custom toString.
class _Opaque implements Exception {}
