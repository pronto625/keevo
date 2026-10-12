import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/scaffold/shell_banners.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/account/domain/model/deletion_pending_status.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _IdleSyncTrigger extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerState.idle();
}

class _FailingSyncTrigger extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerCriticalFailure(duration: Duration(hours: 30));
}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('pickShellBanner', () {
    ShellBanner? pick({
      AccountStatus status = AccountStatus.active,
      bool deletion = false,
      SyncGateState gate = SyncGateState.open,
      bool syncFailure = false,
      bool dismissed = false,
    }) =>
        pickShellBanner(
          accountStatus: status,
          deletionPending: deletion,
          gate: gate,
          syncCriticalFailure: syncFailure,
          offlineWarningDismissed: dismissed,
        );

    test('nothing applies → no banner', () => expect(pick(), isNull));

    test('priority order', () {
      expect(
          pick(
              status: AccountStatus.suspended,
              deletion: true,
              gate: SyncGateState.critical,
              syncFailure: true),
          ShellBanner.suspended);
      expect(pick(deletion: true, gate: SyncGateState.critical),
          ShellBanner.deletionPending);
      expect(pick(gate: SyncGateState.critical, syncFailure: true),
          ShellBanner.offlineGateCritical);
      expect(pick(gate: SyncGateState.warning, syncFailure: true),
          ShellBanner.syncCriticalFailure);
      expect(
          pick(
              status: AccountStatus.trialExpired,
              gate: SyncGateState.warning),
          ShellBanner.offlineGateWarning);
      expect(pick(status: AccountStatus.trialExpired),
          ShellBanner.trialExpired);
    });

    test('a dismissed warning lets the next banner show', () {
      expect(
          pick(
              status: AccountStatus.trialExpired,
              gate: SyncGateState.warning,
              dismissed: true),
          ShellBanner.trialExpired);
    });

    test('critical offline gate is never dismissed', () {
      expect(pick(gate: SyncGateState.critical, dismissed: true),
          ShellBanner.offlineGateCritical);
    });
  });

  testWidgets(
      'suspension + deletion pending + offline warning: only suspension shows; '
      'clearing it reveals the deletion banner', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final status = StateProvider<AccountStatus>((_) => AccountStatus.suspended);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        accountStatusProvider.overrideWith((ref) => ref.watch(status)),
        deletionPendingStatusProvider.overrideWith((ref) async =>
            DeletionPendingStatus(
                scheduledAt: DateTime.now().add(const Duration(days: 10)))),
        syncGateStateProvider.overrideWithValue(SyncGateState.warning),
        daysSinceLastSyncProvider.overrideWithValue(5),
        syncTriggerNotifierProvider.overrideWith(_IdleSyncTrigger.new),
      ],
      child: const MaterialApp(home: Scaffold(body: ShellBanners())),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('compte est suspendu'), findsOneWidget);
    expect(find.textContaining('Suppression programmée'), findsNothing);
    expect(find.textContaining('Synchronisation requise'), findsNothing);

    final container =
        ProviderScope.containerOf(tester.element(find.byType(ShellBanners)));
    container.read(status.notifier).state = AccountStatus.active;
    await tester.pumpAndSettle();

    expect(find.textContaining('compte est suspendu'), findsNothing);
    expect(find.textContaining('Suppression programmée'), findsOneWidget);
    expect(find.textContaining('Synchronisation requise'), findsNothing);
  });

  testWidgets('dismissing the offline warning reveals the trial banner',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        accountStatusProvider.overrideWithValue(AccountStatus.trialExpired),
        deletionPendingStatusProvider.overrideWith((ref) async => null),
        syncGateStateProvider.overrideWithValue(SyncGateState.warning),
        daysSinceLastSyncProvider.overrideWithValue(5),
        syncTriggerNotifierProvider.overrideWith(_IdleSyncTrigger.new),
      ],
      child: const MaterialApp(home: Scaffold(body: ShellBanners())),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation requise'), findsOneWidget);
    expect(find.textContaining('plan Gratuit'), findsNothing);

    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation requise'), findsNothing);
    expect(find.textContaining('plan Gratuit'), findsOneWidget);
  });

  testWidgets('a critical sync failure shows the sync warning banner',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        accountStatusProvider.overrideWithValue(AccountStatus.active),
        deletionPendingStatusProvider.overrideWith((ref) async => null),
        syncGateStateProvider.overrideWithValue(SyncGateState.open),
        daysSinceLastSyncProvider.overrideWithValue(0),
        syncTriggerNotifierProvider.overrideWith(_FailingSyncTrigger.new),
      ],
      child: const MaterialApp(home: Scaffold(body: ShellBanners())),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('Synchronisation en échec'), findsOneWidget);
  });
}
