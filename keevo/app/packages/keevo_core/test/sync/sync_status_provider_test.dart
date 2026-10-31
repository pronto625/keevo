import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('syncStatusProvider', () {
    test('emits online when connectivity contains mobile', () async {
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.mobile]),
        ),
        firstOfflineDateProvider.overrideWithValue(null),
      ]);
      addTearDown(container.dispose);
      // Riverpod 3 pauses an unlistened stream: listen as the UI does.
      container.listen(syncStatusProvider, (_, __) {});
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.online);
    });

    // Device test: the sync trigger listens to connectivity first; the
    // indicator then subscribed after the only event and stayed on
    // « Vérification… » for the whole session.
    test('a status is emitted when connectivity was already listened to',
        () async {
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.wifi]),
        ),
        firstOfflineDateProvider.overrideWithValue(null),
      ]);
      addTearDown(container.dispose);
      container.listen(connectivityStreamProvider, (_, __) {});
      await container.read(connectivityStreamProvider.future);
      await Future<void>.delayed(Duration.zero);

      // Riverpod 3 pauses an unlistened stream: listen as the UI does.
      container.listen(syncStatusProvider, (_, __) {});
      final result = await container
          .read(syncStatusProvider.future)
          .timeout(const Duration(seconds: 1));
      expect(result, SyncStatus.online);
    });

    test('emits online when connectivity contains wifi', () async {
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.wifi]),
        ),
        firstOfflineDateProvider.overrideWithValue(null),
      ]);
      addTearDown(container.dispose);
      // Riverpod 3 pauses an unlistened stream: listen as the UI does.
      container.listen(syncStatusProvider, (_, __) {});
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.online);
    });

    test('emits offlineOk when only none and 2 days offline', () async {
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.none]),
        ),
        firstOfflineDateProvider.overrideWithValue(
          DateTime.now().subtract(const Duration(days: 2)),
        ),
      ]);
      addTearDown(container.dispose);
      // Riverpod 3 pauses an unlistened stream: listen as the UI does.
      container.listen(syncStatusProvider, (_, __) {});
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.offlineOk);
    });

    test('emits offlineCritical when only none and 6 days offline', () async {
      final container = ProviderContainer(retry: noProviderRetry, overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.none]),
        ),
        firstOfflineDateProvider.overrideWithValue(
          DateTime.now().subtract(const Duration(days: 6)),
        ),
      ]);
      addTearDown(container.dispose);
      // Riverpod 3 pauses an unlistened stream: listen as the UI does.
      container.listen(syncStatusProvider, (_, __) {});
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.offlineCritical);
    });
  });
}
