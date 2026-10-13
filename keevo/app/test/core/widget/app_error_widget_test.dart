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

    await tester.tap(find.text('Réessayer'));
    await tester.pump();
    await tester.tap(find.text('Réessayer'));
    await tester.pump();

    expect(calls, 2);
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
  });
}

class _DomainException implements Exception {
  final String message;
  _DomainException(this.message);

  @override
  String toString() => 'DomainException[X_CODE]: $message';
}
