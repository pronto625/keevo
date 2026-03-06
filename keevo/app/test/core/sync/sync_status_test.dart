import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/sync_status.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('SyncStatus.fromDaysOffline', () {
    test('days = 0 → online', () => expect(SyncStatus.fromDaysOffline(0), SyncStatus.online));
    test('days 1-4 → offlineOk', () {
      for (var d = 1; d <= 4; d++) {
        expect(SyncStatus.fromDaysOffline(d), SyncStatus.offlineOk, reason: 'day $d');
      }
    });
    test('days 5-7 → offlineCritical', () {
      for (var d = 5; d <= 7; d++) {
        expect(SyncStatus.fromDaysOffline(d), SyncStatus.offlineCritical, reason: 'day $d');
      }
    });
  });
}
