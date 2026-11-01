import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/entitlements/entitlements_refresh_trigger.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';

import '../../helpers/fake_entitlements.dart';

// Story 16.3 — module states asked again on resume / network back.

/// Records the `refreshIfStale` calls: false = resume, true = network back.
class _CountingNotifier extends FakeEntitlementsNotifier {
  final List<bool> calls = [];

  _CountingNotifier([Future<Map<String, String>> Function()? fetch])
      : super(fetch ?? () async => {'pos': 'ENABLED'});

  @override
  Future<void> refreshIfStale({bool force = false}) async => calls.add(force);
}

class _Pings extends ModuleGateRefresh {
  int count = 0;

  @override
  void ping() {
    count++;
    super.ping();
  }
}

const _toBackground = [
  AppLifecycleState.inactive,
  AppLifecycleState.hidden,
  AppLifecycleState.paused,
];
const _toForeground = [
  AppLifecycleState.hidden,
  AppLifecycleState.inactive,
  AppLifecycleState.resumed,
];

Future<void> _lifecycle(
  WidgetTester tester,
  List<AppLifecycleState> states,
) async {
  for (final s in states) {
    tester.binding.handleAppLifecycleStateChanged(s);
    await tester.pump();
  }
}

void main() {
  late StreamController<List<ConnectivityResult>> network;
  late _CountingNotifier notifier;
  late ProviderContainer container;

  /// Inside the test zone (fake async), so stream events and timers run on
  /// `tester.pump`.
  void start() {
    network = StreamController<List<ConnectivityResult>>.broadcast();
    notifier = _CountingNotifier();
    container = ProviderContainer(
      retry: noProviderRetry,
      overrides: [
        connectivityStreamProvider.overrideWith((ref) => network.stream),
        entitlementsProvider.overrideWith(() => notifier),
      ],
    );
    // Listened like KeevoApp does (Riverpod 3 pauses an unlistened provider).
    container.listen(entitlementsRefreshTriggerProvider, (_, __) {});
  }

  Future<void> stop(WidgetTester tester) async {
    container.dispose();
    await network.close();
    await tester.pump(const Duration(seconds: 1));
  }

  testWidgets('resume → refreshIfStale (once per resume, not forced)', (
    tester,
  ) async {
    await _lifecycle(tester, [AppLifecycleState.resumed]);
    start();

    await _lifecycle(tester, [..._toBackground, ..._toForeground]);
    expect(notifier.calls, [false]);
    await _lifecycle(tester, [..._toBackground, ..._toForeground]);
    expect(
        notifier.calls,
        [
          false,
          false,
        ],
        reason: 'the notifier throttles, not this');
    await stop(tester);
  });

  testWidgets('network back → one forced refresh; startup event → none', (
    tester,
  ) async {
    start();

    network.add([ConnectivityResult.wifi]); // first event at startup
    await tester.pump();
    expect(notifier.calls, isEmpty);

    network.add([ConnectivityResult.none]);
    await tester.pump();
    expect(notifier.calls, isEmpty);

    network.add([ConnectivityResult.mobile]);
    await tester.pump();
    expect(notifier.calls, [true]);

    network.add([ConnectivityResult.mobile, ConnectivityResult.wifi]);
    await tester.pump();
    expect(notifier.calls, [true], reason: 'still online: no new request');
    await stop(tester);
  });

  test(
    'EntitlementsRefreshTrigger: vpn / other-only links count as online',
    () {
      final calls = <bool>[];
      final trigger = EntitlementsRefreshTrigger(
        ({bool force = false}) async => calls.add(force),
      );
      trigger.onConnectivity([ConnectivityResult.none]);
      trigger.onConnectivity([ConnectivityResult.vpn]);
      trigger.onConnectivity([ConnectivityResult.none]);
      trigger.onConnectivity([ConnectivityResult.other]);
      trigger.onConnectivity([ConnectivityResult.ethernet]);
      expect(calls, [true, true]);
    },
  );

  test('isOnline: anything but none', () {
    expect(isOnline([ConnectivityResult.none]), isFalse);
    expect(isOnline(const []), isFalse);
    expect(isOnline([ConnectivityResult.vpn]), isTrue);
    expect(isOnline([ConnectivityResult.other]), isTrue);
    expect(
      isOnline([ConnectivityResult.none, ConnectivityResult.wifi]),
      isTrue,
    );
  });

  testWidgets(
    'listenEntitlements (KeevoApp root): resume refreshes, a new answer '
    'pings the module gate',
    (tester) async {
      await _lifecycle(tester, [AppLifecycleState.resumed]);
      network = StreamController<List<ConnectivityResult>>.broadcast();
      var answer = {'invoicing': 'ENABLED'};
      notifier = _CountingNotifier(() async => answer);
      final pings = _Pings();
      await tester.pumpWidget(
        ProviderScope(
          retry: noProviderRetry,
          overrides: [
            connectivityStreamProvider.overrideWith((ref) => network.stream),
            entitlementsProvider.overrideWith(() => notifier),
          ],
          child: Consumer(
            builder: (context, ref, _) {
              listenEntitlements(ref, refresh: pings);
              return const SizedBox();
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      // Build: unknown seed → server answer (invoicing readable).
      expect(pings.count, 1);

      await _lifecycle(tester, [..._toBackground, ..._toForeground]);
      expect(notifier.calls, [false]);

      // Server-side disable seen through a new answer → the guard re-runs.
      answer = {'invoicing': 'DISABLED'};
      await notifier.ensureServerState(); // cached: no new request
      expect(pings.count, 1);
      await tester.runAsync(() => notifier.refresh());
      await tester.pump();
      expect(pings.count, 2);

      await tester.pumpWidget(const SizedBox());
      await network.close();
      await tester.pump(const Duration(seconds: 1));
    },
  );
}
