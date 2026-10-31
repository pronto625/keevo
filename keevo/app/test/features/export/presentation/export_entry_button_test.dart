import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';
import 'package:keevo/features/export/presentation/widget/export_entry_button.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// v1s-18-4 — « Exporter » entry button (used as-is by Rapports, whose page
/// is too heavy to pump here): OWNER only, pushes its prefill.
void main() {
  const prefill = ExportPrefill(
    datasets: {'sales', 'sale_items', 'day_closures', 'reports'},
    storeId: 's-1',
  );

  Future<ExportPrefill?> pump(WidgetTester tester, String role) async {
    ExportPrefill? pushed;
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [currentUserRoleProvider.overrideWithValue(role)],
      child: MaterialApp.router(
        routerConfig: GoRouter(routes: [
          GoRoute(
            path: '/',
            builder: (_, __) => Scaffold(
              appBar: AppBar(actions: const [
                ExportEntryButton(key: Key('reports-export'), prefill: prefill),
              ]),
            ),
          ),
          GoRoute(
            path: '/exports/new',
            builder: (_, state) {
              pushed = state.extra as ExportPrefill?;
              return const Scaffold(body: Text('EXPORTER'));
            },
          ),
        ]),
      ),
    ));
    await tester.pumpAndSettle();
    if (find.byTooltip('Exporter').evaluate().isEmpty) return null;
    await tester.tap(find.byTooltip('Exporter'));
    await tester.pumpAndSettle();
    return pushed;
  }

  testWidgets('OWNER: shown, pushes /exports/new with its prefill',
      (tester) async {
    final pushed = await pump(tester, 'OWNER');
    expect(find.text('EXPORTER'), findsOneWidget);
    expect(identical(pushed, prefill), isTrue);
  });

  testWidgets('EMPLOYEE: hidden', (tester) async {
    expect(await pump(tester, 'EMPLOYEE'), isNull);
    expect(find.byTooltip('Exporter'), findsNothing);
  });
}
