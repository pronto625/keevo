import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/audit/presentation/page/stock_movements_page.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';
import 'package:keevo/features/audit/presentation/widget/stock_history_widget.dart';

import 'audit_test_helpers.dart';

/// v1s-18-7 — the former « Journal d'audit » is « Mouvements de stock »,
/// filtered on stock movements only.
void main() {
  testWidgets('title and StockMovement filter', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserIdProvider.overrideWith((_) async => 'owner-1'),
        auditRepositoryProvider.overrideWithValue(FakeAuditRepository()),
      ],
      child: const MaterialApp(home: StockMovementsPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Mouvements de stock'), findsOneWidget);
    final widget =
        tester.widget<StockHistoryWidget>(find.byType(StockHistoryWidget));
    expect(widget.entityType, 'StockMovement');
    expect(widget.entityId, isNull);
  });
}
