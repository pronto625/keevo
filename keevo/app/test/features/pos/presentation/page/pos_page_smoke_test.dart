import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/pos/presentation/page/pos_page.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('PosPage renders without exception', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue('OWNER'),
      ],
      child: const MaterialApp(home: PosPage()),
    ));
    await tester.pump();
    // Should render the AppBar title without crash
    expect(find.text('Point de Vente'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
