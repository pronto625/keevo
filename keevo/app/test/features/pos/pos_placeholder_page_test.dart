import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/pos/presentation/page/pos_placeholder_page.dart';
import 'package:keevo/features/sync_indicator/presentation/widget/sync_indicator.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('PosPlaceholderPage has SyncIndicator in AppBar', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        syncStatusProvider.overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        sharedPreferencesProvider.overrideWithValue(prefs),
      ],
      child: const MaterialApp(home: PosPlaceholderPage()),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(SyncIndicator), findsOneWidget);
  });
}
