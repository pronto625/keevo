import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

// v1s-16-18: runs tool/switch_android_application_id.sh on a temp fixture
// (never on the real android/ folder).
void main() {
  bool has(String exe) =>
      Process.runSync('bash', ['-c', 'command -v $exe']).exitCode == 0;
  final skip = (() {
    try {
      return has('python3') ? null : 'python3 unavailable';
    } on ProcessException {
      return 'bash unavailable';
    }
  })();

  late Directory root;

  String googleServices(List<String> packages) => jsonEncode({
        'client': [
          for (final p in packages)
            {
              'client_info': {
                'android_client_info': {'package_name': p},
              },
            },
        ],
      });

  File file(String rel) => File('${root.path}/$rel');

  Map<String, String> snapshot() => {
        for (final e in root.listSync(recursive: true))
          e.path.substring(root.path.length + 1):
              e is File ? base64Encode(e.readAsBytesSync()) : '<dir>',
      };

  Future<ProcessResult> runScript([List<String> args = const []]) =>
      Process.run('bash', ['${root.path}/tool/switch_android_application_id.sh', ...args]);

  setUp(() {
    root = Directory.systemTemp.createTempSync('keevo_switch_');
    file('tool/switch_android_application_id.sh')
      ..createSync(recursive: true)
      ..writeAsStringSync(
          File('tool/switch_android_application_id.sh').readAsStringSync());
    file('android/app/build.gradle.kts')
      ..createSync(recursive: true)
      ..writeAsStringSync('android {\n    namespace = "com.example.keevo"\n'
          '    defaultConfig {\n        applicationId = "com.example.keevo"\n'
          '    }\n}\n');
    file('android/app/google-services.json')
        .writeAsStringSync(googleServices(['com.example.keevo']));
    file('android/app/src/main/kotlin/com/example/keevo/MainActivity.kt')
      ..createSync(recursive: true)
      ..writeAsStringSync('package com.example.keevo\n\n'
          'import io.flutter.embedding.android.FlutterActivity\n\n'
          'class MainActivity : FlutterActivity()\n');
  });

  tearDown(() {
    if (root.existsSync()) root.deleteSync(recursive: true);
  });

  test('refuses the real run without the new Firebase client', () async {
    final before = snapshot();
    final result = await runScript();
    expect(result.exitCode, isNot(0));
    expect(result.stderr, contains('no Android client for com.keevo.keevo'));
    expect(snapshot(), before);
  }, skip: skip);

  test('--dry-run changes no file, even before the Firebase step', () async {
    final before = snapshot();
    final result = await runScript(['--dry-run']);
    expect(result.exitCode, 0, reason: '${result.stderr}');
    expect(result.stderr, contains('WARNING'));
    expect(result.stdout, contains('Dry run complete'));
    expect(snapshot(), before);
  }, skip: skip);

  test('switches gradle ids and MainActivity, then is idempotent', () async {
    file('android/app/google-services.json').writeAsStringSync(
        googleServices(['com.example.keevo', 'com.keevo.keevo']));

    final result = await runScript();
    expect(result.exitCode, 0, reason: '${result.stderr}');

    final gradle = file('android/app/build.gradle.kts').readAsStringSync();
    expect(gradle, contains('namespace = "com.keevo.keevo"'));
    expect(gradle, contains('applicationId = "com.keevo.keevo"'));
    expect(gradle, isNot(contains('com.example')));
    final activity =
        file('android/app/src/main/kotlin/com/keevo/keevo/MainActivity.kt');
    expect(activity.readAsStringSync(), startsWith('package com.keevo.keevo\n'));
    expect(
        Directory('${root.path}/android/app/src/main/kotlin/com/example')
            .existsSync(),
        isFalse);
    expect(snapshot().keys.where((k) => k.endsWith('.bak')), isEmpty);

    final before = snapshot();
    final again = await runScript();
    expect(again.exitCode, 0, reason: '${again.stderr}');
    expect(again.stdout, contains('Already switched'));
    expect(snapshot(), before);
  }, skip: skip);

  test('reports a malformed google-services.json as such', () async {
    file('android/app/google-services.json')
        .writeAsStringSync('{"client": [{"client_info": null}]}');
    final before = snapshot();
    final result = await runScript();
    expect(result.exitCode, isNot(0));
    expect(result.stderr, contains('not a valid Firebase Android config'));
    expect(snapshot(), before);
  }, skip: skip);
}
