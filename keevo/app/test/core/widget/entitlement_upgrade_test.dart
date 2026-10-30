import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/network/module_entitlement_exception.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/core/widget/entitlement_upgrade.dart';

// 13.10b — module refusals show a French message and « Passer à Premium »
// (owner), which opens the subscription screen.

const _notEntitled =
    ModuleEntitlementException(ModuleRefusalReason.notEntitled);
const _readOnly = ModuleEntitlementException(ModuleRefusalReason.readOnly);

Widget _app(Widget page, {bool owner = true}) {
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (_, __) => Scaffold(body: page)),
    GoRoute(
      path: '/settings/subscription',
      builder: (_, __) => const Scaffold(body: Text('ÉCRAN ABONNEMENT')),
    ),
  ]);
  return ProviderScope(
    overrides: [isOwnerSessionProvider.overrideWithValue(owner)],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets(
      'full-page refusal: premium title, message, upgrade opens '
      'the subscription screen', (tester) async {
    await tester.pumpWidget(_app(AppErrorWidget(
      error: _notEntitled,
      title: 'Impossible de charger les stocks',
      onRetry: () {},
    )));
    await tester.pumpAndSettle();

    expect(find.text('Fonctionnalité Premium'), findsOneWidget);
    expect(find.text(ModuleEntitlementException.notEntitledMessage),
        findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);

    await tester.tap(find.text('Passer à Premium'));
    await tester.pumpAndSettle();
    expect(find.text('ÉCRAN ABONNEMENT'), findsOneWidget);
  });

  testWidgets('read-only refusal: expired title and message', (tester) async {
    await tester.pumpWidget(_app(AppErrorWidget(
      error: _readOnly,
      onRetry: () {},
    )));
    await tester.pumpAndSettle();

    expect(find.text('Abonnement expiré'), findsOneWidget);
    expect(
        find.text(ModuleEntitlementException.readOnlyMessage), findsOneWidget);
    expect(find.text('Passer à Premium'), findsOneWidget);
  });

  testWidgets('employee: message without the upgrade action', (tester) async {
    await tester.pumpWidget(_app(
      AppErrorWidget(error: _notEntitled, onRetry: () {}),
      owner: false,
    ));
    await tester.pumpAndSettle();

    expect(find.text(ModuleEntitlementException.notEntitledMessage),
        findsOneWidget);
    expect(find.text('Passer à Premium'), findsNothing);
  });

  testWidgets('other errors: no upgrade action', (tester) async {
    await tester.pumpWidget(_app(AppErrorWidget(
      error: Exception('Base locale indisponible'),
      onRetry: () {},
    )));
    await tester.pumpAndSettle();

    expect(find.text('Un problème est survenu'), findsOneWidget);
    expect(find.text('Passer à Premium'), findsNothing);
  });

  testWidgets('inline refusal carries the compact action', (tester) async {
    await tester.pumpWidget(_app(const AppErrorInline(error: _readOnly)));
    await tester.pumpAndSettle();

    expect(
        find.text(ModuleEntitlementException.readOnlyMessage), findsOneWidget);
    expect(find.text('Passer à Premium'), findsOneWidget);
  });

  testWidgets(
      'snackbar: French message + action for a refusal, plain '
      'otherwise', (tester) async {
    await tester.pumpWidget(_app(Builder(
      builder: (context) => Column(children: [
        TextButton(
          onPressed: () => ScaffoldMessenger.of(context)
              .showSnackBar(appErrorSnackBar(context, _notEntitled)),
          child: const Text('refus'),
        ),
        TextButton(
          onPressed: () => ScaffoldMessenger.of(context).showSnackBar(
              appErrorSnackBar(context, Exception('Stock introuvable'))),
          child: const Text('autre'),
        ),
      ]),
    )));
    await tester.pumpAndSettle();

    await tester.tap(find.text('autre'));
    await tester.pump();
    expect(find.text('Stock introuvable'), findsOneWidget);
    expect(find.text('Passer à Premium'), findsNothing);
    ScaffoldMessenger.of(tester.element(find.text('autre')))
        .removeCurrentSnackBar();
    await tester.pumpAndSettle();

    await tester.tap(find.text('refus'));
    await tester.pumpAndSettle();
    expect(find.text(ModuleEntitlementException.notEntitledMessage),
        findsOneWidget);
    await tester.tap(find.text('Passer à Premium'));
    await tester.pumpAndSettle();
    expect(find.text('ÉCRAN ABONNEMENT'), findsOneWidget);
  });

  testWidgets('snackbar builder captured before an await works once the '
      'context is gone', (tester) async {
    AppErrorSnackBarBuilder? builder;
    ScaffoldMessengerState? messenger;
    final show = ValueNotifier(true);
    await tester.pumpWidget(_app(ValueListenableBuilder<bool>(
      valueListenable: show,
      builder: (_, visible, __) => visible
          ? Builder(builder: (context) {
              builder = appErrorSnackBarFor(context);
              messenger = ScaffoldMessenger.of(context);
              return const Text('éphémère');
            })
          : const SizedBox.shrink(),
    )));
    await tester.pumpAndSettle();
    show.value = false; // the widget that captured goes away (the await)
    await tester.pumpAndSettle();
    expect(find.text('éphémère'), findsNothing);

    messenger!.showSnackBar(builder!(_readOnly));
    await tester.pumpAndSettle();
    expect(find.text(ModuleEntitlementException.readOnlyMessage),
        findsOneWidget);
    await tester.tap(find.text('Passer à Premium'));
    await tester.pumpAndSettle();
    expect(find.text('ÉCRAN ABONNEMENT'), findsOneWidget);
  });
}
