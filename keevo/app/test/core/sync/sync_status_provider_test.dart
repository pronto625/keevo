import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('syncStatusProvider', () {
    test('emits online when connectivity contains mobile', () async {
      final container = ProviderContainer(overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.mobile]),
        ),
        firstOfflineDateProvider.overrideWithValue(null),
      ]);
      addTearDown(container.dispose);
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.online);
    });

    test('emits online when connectivity contains wifi', () async {
      final container = ProviderContainer(overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.wifi]),
        ),
        firstOfflineDateProvider.overrideWithValue(null),
      ]);
      addTearDown(container.dispose);
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.online);
    });

    test('emits offlineOk when only none and 2 days offline', () async {
      final container = ProviderContainer(overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.none]),
        ),
        firstOfflineDateProvider.overrideWithValue(
          DateTime.now().subtract(const Duration(days: 2)),
        ),
      ]);
      addTearDown(container.dispose);
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.offlineOk);
    });

    test('emits offlineCritical when only none and 6 days offline', () async {
      final container = ProviderContainer(overrides: [
        connectivityStreamProvider.overrideWith(
          (ref) => Stream.value([ConnectivityResult.none]),
        ),
        firstOfflineDateProvider.overrideWithValue(
          DateTime.now().subtract(const Duration(days: 6)),
        ),
      ]);
      addTearDown(container.dispose);
      final result = await container.read(syncStatusProvider.future);
      expect(result, SyncStatus.offlineCritical);
    });
  });
}
