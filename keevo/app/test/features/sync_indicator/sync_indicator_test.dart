import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';

Widget buildWithStatus(SyncStatus status, {int days = 0}) {
  return ProviderScope(
    overrides: [
      syncStatusProvider.overrideWith((ref) => Stream.value(status)),
      daysOfflineProvider.overrideWithValue(days),
      // Gate is open in these tests (0 days since last sync)
      daysSinceLastSyncProvider.overrideWithValue(0),
    ],
    child: MaterialApp(
      home: Scaffold(
        appBar: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: AppBar(actions: const [SyncIndicator()]),
        ),
        body: const SizedBox.shrink(),
      ),
    ),
  );
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('shows "En ligne" when online', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.online));
    await tester.pumpAndSettle();
    expect(find.text('En ligne'), findsOneWidget);
  });

  testWidgets('dot is green (#51CF66) when online', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.online));
    await tester.pumpAndSettle();
    final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
    expect((dot.decoration as BoxDecoration).color, const Color(0xFF51CF66));
  });

  testWidgets('shows "Hors-ligne — Jour 3/7" when offlineOk day 3', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.offlineOk, days: 3));
    await tester.pumpAndSettle();
    expect(find.text('Hors-ligne — Jour 3/7'), findsOneWidget);
  });

  testWidgets('dot is amber (#FCC419) when offlineOk', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.offlineOk, days: 2));
    await tester.pumpAndSettle();
    final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
    expect((dot.decoration as BoxDecoration).color, const Color(0xFFFCC419));
  });

  testWidgets('shows "Hors-ligne critique — Jour 5/7" when offlineCritical',
      (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.offlineCritical, days: 5));
    await tester.pumpAndSettle();
    expect(find.text('Hors-ligne critique — Jour 5/7'), findsOneWidget);
  });

  testWidgets('dot is red (#FA5252) when offlineCritical', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.offlineCritical, days: 5));
    await tester.pumpAndSettle();
    final dot = tester.widget<Container>(find.byKey(const Key('sync_dot')));
    expect((dot.decoration as BoxDecoration).color, const Color(0xFFFA5252));
  });

  testWidgets('shows CircularProgressIndicator when syncing', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.syncing));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('tapping opens bottom sheet with "Synchroniser maintenant"',
      (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.online));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(SyncIndicator));
    await tester.pumpAndSettle();
    expect(find.text('Synchroniser maintenant'), findsOneWidget);
  });

  testWidgets('"Synchroniser maintenant" is disabled when offline', (tester) async {
    await tester.pumpWidget(buildWithStatus(SyncStatus.offlineOk, days: 2));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(SyncIndicator));
    await tester.pumpAndSettle();
    final btn = tester.widget<ElevatedButton>(
        find.widgetWithText(ElevatedButton, 'Synchroniser maintenant'));
    expect(btn.onPressed, isNull);
  });
}
