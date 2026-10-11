import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/core/widget/soft_icon_badge.dart';

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
}
