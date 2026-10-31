import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:keevo_core/theme/app_theme.dart';

// v1s-16-2 / v1s-16-18: iOS push and release configuration that cannot be
// checked by a build on the Linux host.
void main() {
  String read(String path) => File(path).readAsStringSync();
  final pbxproj = read('ios/Runner.xcodeproj/project.pbxproj');

  /// Build settings of every XCBuildConfiguration block, keyed by
  /// `<configuration name>` and holding the raw settings text.
  List<({String name, String settings})> buildConfigurations() {
    final block = RegExp(
      r'isa = XCBuildConfiguration;.*?buildSettings = \{(.*?)\n\t\t\t\};\s*name = (\w+);',
      dotAll: true,
    );
    return block
        .allMatches(pbxproj)
        .map((m) => (name: m.group(2)!, settings: m.group(1)!))
        .toList();
  }

  String? setting(String settings, String key) =>
      RegExp('$key = ([^;]+);').firstMatch(settings)?.group(1)?.trim();

  String apsEnvironment(String entitlementsPath) {
    final match = RegExp(
      r'<key>aps-environment</key>\s*<string>(\w+)</string>',
    ).firstMatch(read('ios/$entitlementsPath'));
    return match!.group(1)!;
  }

  List<String> plistArray(String plist, String key) {
    final match = RegExp(
      '<key>${RegExp.escape(key)}</key>\\s*<array>(.*?)</array>',
      dotAll: true,
    ).firstMatch(plist)!;
    return RegExp(r'<string>([^<]+)</string>')
        .allMatches(match.group(1)!)
        .map((m) => m.group(1)!)
        .toList();
  }

  test('Info.plist declares the remote-notification background mode', () {
    final plist = read('ios/Runner/Info.plist');
    expect(plist, contains('<key>UIBackgroundModes</key>'));
    expect(plist, contains('<string>remote-notification</string>'));
  });

  test('AppDelegate sets the notification-center delegate', () {
    expect(read('ios/Runner/AppDelegate.swift'),
        contains('UNUserNotificationCenter.current().delegate'));
  });

  group('release identity (v1s-16-18)', () {
    test('bundle ids are com.keevo.keevo, never com.example', () {
      expect(pbxproj, isNot(contains('com.example')));
      final ids = buildConfigurations()
          .map((c) => setting(c.settings, 'PRODUCT_BUNDLE_IDENTIFIER'))
          .whereType<String>()
          .toList();
      expect(ids.where((id) => id == 'com.keevo.keevo').length, 3);
      expect(ids.where((id) => id == 'com.keevo.keevo.RunnerTests').length, 3);
      expect(ids.length, 6);
    });

    test('Release uses production APNs, Debug and Profile use development',
        () {
      final runner = {
        for (final c in buildConfigurations())
          if (setting(c.settings, 'CODE_SIGN_ENTITLEMENTS') case final path?)
            c.name: path,
      };
      expect(runner.keys, unorderedEquals(['Debug', 'Profile', 'Release']));
      expect(apsEnvironment(runner['Release']!), 'production');
      expect(apsEnvironment(runner['Debug']!), 'development');
      expect(apsEnvironment(runner['Profile']!), 'development');
    });

    test('both entitlements files are referenced by the Runner group', () {
      for (final name in [
        'Runner.entitlements',
        'RunnerDevelopment.entitlements',
      ]) {
        expect(
          RegExp('/\\* ${RegExp.escape(name)} \\*/ = \\{isa = PBXFileReference')
              .hasMatch(pbxproj),
          isTrue,
          reason: '$name file reference',
        );
        expect(
          RegExp('/\\* ${RegExp.escape(name)} \\*/,').hasMatch(pbxproj),
          isTrue,
          reason: '$name group entry',
        );
      }
    });

    test('every AppIcon is an opaque brand PNG of the declared size', () {
      const dir = 'ios/Runner/Assets.xcassets/AppIcon.appiconset';
      final contents =
          jsonDecode(read('$dir/Contents.json')) as Map<String, dynamic>;
      final images = (contents['images'] as List).cast<Map<String, dynamic>>();
      expect(images.any((e) => e['idiom'] == 'ios-marketing'), isTrue);
      for (final entry in images) {
        final file = File('$dir/${entry['filename']}');
        expect(file.existsSync(), isTrue, reason: file.path);
        final png = img.decodePng(file.readAsBytesSync())!;
        final points = double.parse((entry['size'] as String).split('x').first);
        final scale = int.parse((entry['scale'] as String).replaceAll('x', ''));
        final pixels = (points * scale).round();
        expect(png.width, pixels, reason: file.path);
        expect(png.height, pixels, reason: file.path);
        expect(png.hasAlpha, isFalse, reason: '${file.path} has alpha');
        // Full-bleed Keevo blue up to the corner (Flutter default and the
        // white-cornered source would fail here).
        final corner = png.getPixel(0, 0);
        expect(corner.b > corner.r + 80 && corner.b > 150, isTrue,
            reason: '${file.path} corner is not brand blue: $corner');
      }
    });

    test('launch screen uses the brand mark on the scaffold colours', () {
      final storyboard = read('ios/Runner/Base.lproj/LaunchScreen.storyboard');
      expect(storyboard,
          contains('<color key="backgroundColor" name="LaunchBackground"/>'));
      expect(storyboard,
          contains('<image name="LaunchImage" width="96" height="96"/>'));

      final colorset = jsonDecode(read(
              'ios/Runner/Assets.xcassets/LaunchBackground.colorset/Contents.json'))
          as Map<String, dynamic>;
      final colors = (colorset['colors'] as List).cast<Map<String, dynamic>>();
      Color rgb(Map<String, dynamic> entry) {
        final c = (entry['color'] as Map)['components'] as Map;
        int channel(String k) => int.parse(c[k] as String);
        return Color.fromARGB(
            255, channel('red'), channel('green'), channel('blue'));
      }

      final any = colors.singleWhere((e) => e['appearances'] == null);
      final dark = colors.singleWhere((e) =>
          (e['appearances'] as List?)?.any((a) => a['value'] == 'dark') ??
          false);
      expect(rgb(any), AppTheme.scaffoldLight);
      expect(rgb(dark), AppTheme.darkSurface);

      const launchDir = 'ios/Runner/Assets.xcassets/LaunchImage.imageset';
      for (final (name, size) in [
        ('LaunchImage.png', 96),
        ('LaunchImage@2x.png', 192),
        ('LaunchImage@3x.png', 288),
      ]) {
        final png = img.decodePng(File('$launchDir/$name').readAsBytesSync())!;
        expect((png.width, png.height), (size, size), reason: name);
        final centre = png.getPixel(size ~/ 2, size ~/ 2);
        expect(centre.a, 255, reason: '$name centre is not opaque');
        expect(centre.b > centre.r + 80 && centre.b > 150, isTrue,
            reason: '$name centre is not brand blue: $centre');
      }
    });

    // Story 15.8 — onboarding / terms screens overflow in landscape: the app
    // stays portrait when the phone is rotated.
    test('the app is locked to portrait, iOS matching Android', () {
      final dartLocks = [Directory('lib'), Directory('packages/keevo_core/lib')]
          .expand((d) => d.listSync(recursive: true))
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'))
          .where((f) => f.readAsStringSync().contains('setPreferredOrientations'))
          .map((f) => f.path)
          .toList();
      expect(dartLocks, isEmpty,
          reason: 'lib/ now locks orientation from Dart; redo the iOS/Android '
              'orientation parity by hand (Info.plist, AndroidManifest) and '
              'update this test');
      expect(
        read('android/app/src/main/AndroidManifest.xml'),
        contains('android:screenOrientation="portrait"'),
      );
      final plist = read('ios/Runner/Info.plist');
      expect(plistArray(plist, 'UISupportedInterfaceOrientations'),
          ['UIInterfaceOrientationPortrait']);
      // No tablet layout: portrait on iPad too (full screen, so no
      // multitasking requirement for all orientations).
      expect(
        plistArray(plist, 'UISupportedInterfaceOrientations~ipad'),
        unorderedEquals([
          'UIInterfaceOrientationPortrait',
          'UIInterfaceOrientationPortraitUpsideDown',
        ]),
      );
      expect(
        RegExp(r'<key>UIRequiresFullScreen</key>\s*<true/>').hasMatch(plist),
        isTrue,
      );
    });
  });
}
