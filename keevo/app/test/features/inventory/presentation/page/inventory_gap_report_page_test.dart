import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/presentation/page/inventory_gap_report_page.dart';
import 'package:keevo/features/inventory/presentation/provider/gap_report_provider.dart';

InventoryGapReportModel _makeReport({
  String sessionStatus = 'IN_PROGRESS',
  int totalShortage = 2,
  int totalSurplus = 1,
}) {
  return InventoryGapReportModel(
    sessionId: 's1',
    storeId: 'st1',
    storeName: 'Boutique Test',
    scope: 'FULL',
    sessionStatus: sessionStatus,
    summary: InventoryGapSummaryModel(
      totalCounted: 4,
      totalConcordant: 1,
      totalSurplus: totalSurplus,
      totalShortage: totalShortage,
      totalSurplusValueXaf: 3000,
      totalShortageValueXaf: 25000,
    ),
    concordantRows: [
      InventoryGapRowModel(
        productId: 'p1',
        productName: 'Concordant',
        theoretical: 10,
        physical: 10,
        ecart: 0,
        unitPriceXaf: 500,
        gapValueXaf: 0,
      ),
    ],
    surplusRows: totalSurplus > 0
        ? [
            InventoryGapRowModel(
              productId: 'p2',
              productName: 'Surplus Item',
              theoretical: 5,
              physical: 8,
              ecart: 3,
              unitPriceXaf: 1000,
              gapValueXaf: 3000,
            ),
          ]
        : [],
    shortageRows: totalShortage > 0
        ? [
            InventoryGapRowModel(
              productId: 'p3',
              productName: 'Manquant A',
              theoretical: 10,
              physical: 5,
              ecart: -5,
              unitPriceXaf: 3000,
              gapValueXaf: 15000,
            ),
            InventoryGapRowModel(
              productId: 'p4',
              productName: 'Manquant B',
              theoretical: 20,
              physical: 15,
              ecart: -5,
              unitPriceXaf: 2000,
              gapValueXaf: 10000,
            ),
          ]
        : [],
    generatedAt: DateTime(2025, 1, 15, 14, 30),
  );
}

Widget _buildPage({
  String role = 'OWNER',
  InventoryGapReportModel? report,
  List<Override> extraOverrides = const [],
}) {
  final r = report ?? _makeReport();
  return ProviderScope(
    overrides: [
      gapReportProvider('s1').overrideWith((_) async => r),
      currentUserRoleProvider.overrideWithValue(role),
      ...extraOverrides,
    ],
    child: const MaterialApp(
      home: InventoryGapReportPage(sessionId: 's1'),
    ),
  );
}

void main() {
  group('WhatsApp share', () {
    const channel = MethodChannel('plugins.flutter.io/url_launcher');
    final launched = <String>[];

    setUp(() {
      launched.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'launch') {
          launched.add((call.arguments as Map)['url'] as String);
          return false; // WhatsApp not installed / no browser
        }
        return false;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('signs with the user name, never the phone number, and '
        'tells the user when WhatsApp cannot open', (tester) async {
      SharedPreferences.setMockInitialValues({'user_first_name': 'Awa'});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(_buildPage(extraOverrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserPhoneProvider.overrideWithValue('+237690000000'),
      ]));
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.share));
      await tester.pumpAndSettle();

      expect(launched, hasLength(1));
      final text = Uri.parse(launched.single).queryParameters['text']!;
      expect(text, contains('Awa'));
      expect(text, isNot(contains('690000000')));
      expect(find.byType(SnackBar), findsOneWidget);
    });
  });

  testWidgets('renders summary + sections when data loaded', (tester) async {
    await tester.pumpWidget(_buildPage());
    await tester.pumpAndSettle();

    // Summary
    expect(find.text('Résumé'), findsOneWidget);
    expect(find.text('4 comptés'), findsOneWidget);
    expect(find.text('1 concordants'), findsOneWidget);

    // Sections
    expect(find.text('Concordants : 1 produits'), findsOneWidget);
    expect(find.textContaining('Manquants'), findsWidgets);
    expect(find.textContaining('Surplus'), findsWidgets);
  });

  testWidgets('WhatsApp button visible for both OWNER and EMPLOYEE',
      (tester) async {
    await tester.pumpWidget(_buildPage(role: 'EMPLOYEE'));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.share), findsOneWidget);
  });

  testWidgets('download button visible only for OWNER', (tester) async {
    // OWNER — download visible
    await tester.pumpWidget(_buildPage(role: 'OWNER'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.download), findsOneWidget);

    // EMPLOYEE — download hidden
    await tester.pumpWidget(_buildPage(role: 'EMPLOYEE'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.download), findsNothing);
  });

  // ── Story 6.4 — Apply Adjustments Button tests ─────────────────────────

  testWidgets('shows apply button for OWNER with IN_PROGRESS session and gaps',
      (tester) async {
    await tester.pumpWidget(_buildPage());
    await tester.pumpAndSettle();

    // Scroll to the bottom
    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(find.text('Appliquer les ajustements'), findsOneWidget);
  });

  testWidgets('hides apply button for EMPLOYEE role', (tester) async {
    await tester.pumpWidget(_buildPage(role: 'EMPLOYEE'));
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(find.text('Appliquer les ajustements'), findsNothing);
  });

  testWidgets('hides apply button for VALIDATED session', (tester) async {
    await tester.pumpWidget(
      _buildPage(report: _makeReport(sessionStatus: 'VALIDATED')),
    );
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(find.text('Appliquer les ajustements'), findsNothing);
  });

  testWidgets(
      'shows concordant banner AND validate button for OWNER when all concordant',
      (tester) async {
    await tester.pumpWidget(
      _buildPage(
        role: 'OWNER',
        report: _makeReport(totalShortage: 0, totalSurplus: 0),
      ),
    );
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(
      find.text(
          'Aucun ajustement nécessaire — tous les stocks correspondent'),
      findsOneWidget,
    );
    // OWNER can still validate (close) the session
    expect(find.text('Valider l\'inventaire'), findsOneWidget);
  });

  testWidgets(
      'shows concordant banner only (no button) for EMPLOYEE when all concordant',
      (tester) async {
    await tester.pumpWidget(
      _buildPage(
        role: 'EMPLOYEE',
        report: _makeReport(totalShortage: 0, totalSurplus: 0),
      ),
    );
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(
      find.text(
          'Aucun ajustement nécessaire — tous les stocks correspondent'),
      findsOneWidget,
    );
    expect(find.text('Valider l\'inventaire'), findsNothing);
    expect(find.text('Appliquer les ajustements'), findsNothing);
  });

  testWidgets('shows confirmation dialog on button tap', (tester) async {
    await tester.pumpWidget(_buildPage());
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Appliquer les ajustements'));
    await tester.pumpAndSettle();

    expect(find.text('Confirmer les ajustements'), findsOneWidget);
    expect(find.textContaining('irréversible'), findsOneWidget);
    expect(find.text('Annuler'), findsOneWidget);
    expect(find.text('Confirmer'), findsOneWidget);
  });

  testWidgets(
      'concordant validate button shows correct dialog (no N adjustments)',
      (tester) async {
    await tester.pumpWidget(
      _buildPage(
        role: 'OWNER',
        report: _makeReport(totalShortage: 0, totalSurplus: 0),
      ),
    );
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Valider l\'inventaire'));
    await tester.pumpAndSettle();

    expect(find.text('Confirmer les ajustements'), findsOneWidget);
    expect(find.textContaining('Tous les stocks correspondent'), findsOneWidget);
    expect(find.textContaining('irréversible'), findsOneWidget);
    expect(find.text('Annuler'), findsOneWidget);
    expect(find.text('Confirmer'), findsOneWidget);
  });
}
