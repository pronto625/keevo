import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

// v1s-16-2: iOS push configuration that cannot be checked by a build on the
// Linux host.
void main() {
  String read(String path) => File(path).readAsStringSync();

  test('Info.plist declares the remote-notification background mode', () {
    final plist = read('ios/Runner/Info.plist');
    expect(plist, contains('<key>UIBackgroundModes</key>'));
    expect(plist, contains('<string>remote-notification</string>'));
  });

  test('Runner.entitlements declares aps-environment', () {
    expect(read('ios/Runner/Runner.entitlements'),
        contains('<key>aps-environment</key>'));
  });

  test('every Runner configuration references the entitlements', () {
    final pbxproj = read('ios/Runner.xcodeproj/project.pbxproj');
    expect(
      'CODE_SIGN_ENTITLEMENTS = Runner/Runner.entitlements;'
          .allMatches(pbxproj)
          .length,
      3,
    );
  });

  test('AppDelegate sets the notification-center delegate', () {
    expect(read('ios/Runner/AppDelegate.swift'),
        contains('UNUserNotificationCenter.current().delegate'));
  });
}
