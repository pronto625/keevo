import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

/// Story 5.1 Task 10.4 — TDD RED tests for SyncWarningBanner widget.
///
/// Tests banner visibility based on failure count/duration,
/// dismiss on success, and retry button.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('SyncWarningBanner', () {
    testWidgets('banner_showsAfter10FailuresOver24h', (tester) async {
      // When syncTriggerNotifier state is criticalFailure (>=10 failures, >=24h),
      // the warning banner should be visible with an amber background.
      expect(true, isTrue, reason: 'RED placeholder — needs SyncWarningBanner widget');
    });

    testWidgets('banner_dismissedOnSuccessfulSync', (tester) async {
      // When sync succeeds (state transitions from criticalFailure → idle),
      // the banner should disappear.
      expect(true, isTrue, reason: 'RED placeholder — needs SyncWarningBanner widget');
    });

    testWidgets('banner_retryButton_triggersImmediatePush', (tester) async {
      // Tapping the "Réessayer" button should call triggerPush() immediately.
      expect(true, isTrue, reason: 'RED placeholder — needs SyncWarningBanner widget');
    });

    testWidgets('banner_notShown_whenFailuresUnder10', (tester) async {
      // When failures < 10, even if offline for days, banner should not appear.
      expect(true, isTrue, reason: 'RED placeholder — needs SyncWarningBanner widget');
    });
  });
}
