import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';
import 'package:keevo/features/inventory/domain/repository/multi_store_stock_repository.dart';
import 'package:keevo/features/inventory/presentation/page/global_stock_overview_page.dart';
import 'package:keevo/features/inventory/presentation/provider/global_stock_provider.dart';

class _MockRepo extends Mock implements MultiStoreStockRepository {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockRepo repo;
  late ProviderContainer container;

  setUp(() async {
    repo = _MockRepo();
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    container = ProviderContainer(overrides: [
      multiStoreStockRepositoryProvider.overrideWithValue(repo),
      sharedPreferencesProvider.overrideWithValue(prefs),
      currentUserRoleProvider.overrideWithValue('OWNER'),
      syncStatusProvider.overrideWith((_) => const Stream<SyncStatus>.empty()),
    ]);
  });
  tearDown(() => container.dispose());

  Widget app(Widget home) => UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: home),
      );

  Finder refreshSpinner() => find.descendant(
      of: find.byTooltip('Actualiser'),
      matching: find.byType(CircularProgressIndicator));

  testWidgets('refresh spinner lasts until the overview is reloaded',
      (tester) async {
    final reload = Completer<List<StoreStockSummaryModel>>();
    var calls = 0;
    when(() => repo.getOverview()).thenAnswer((_) {
      calls++;
      return calls == 1 ? Future.value([]) : reload.future;
    });

    await tester.pumpWidget(app(const GlobalStockOverviewPage()));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Actualiser'));
    await tester.pump(const Duration(seconds: 1));
    expect(refreshSpinner(), findsOneWidget);

    reload.complete([]);
    await tester.pump();
    await tester.pump();
    expect(refreshSpinner(), findsNothing);
  });

  testWidgets('low-stock filter resets when the page is left',
      (tester) async {
    when(() => repo.getOverview()).thenAnswer((_) async => []);

    await tester.pumpWidget(
        app(const GlobalStockOverviewPage(showLowOnly: true)));
    await tester.pumpAndSettle();
    expect(container.read(showLowStockOnlyProvider), isTrue);

    await tester.pumpWidget(app(const SizedBox()));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(container.read(showLowStockOnlyProvider), isFalse);
  });
}
