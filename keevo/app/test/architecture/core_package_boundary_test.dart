import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 15.4a — `keevo_core` is the feature-free kernel of the app: nothing
/// under `packages/keevo_core/lib` (nor its `test/`) may import the app
/// (`package:keevo/…`), a feature (`features/…`) or reach outside the package
/// with a relative path.
const _corePackage = 'packages/keevo_core';
const _coreLib = '$_corePackage/lib';
const _coreTest = '$_corePackage/test';

final _directive = RegExp(
  r'''^\s*(?:import|export|part)\s+['"]([^'"]+)['"]''',
  multiLine: true,
);

/// Offending directives of [source] (a file at [path], under
/// [_corePackage]) — empty when it respects the boundary.
List<String> boundaryViolations(String path, String source) {
  final violations = <String>[];
  final depth = path.substring(_corePackage.length + 1).split('/').length - 1;
  for (final match in _directive.allMatches(source)) {
    final uri = match.group(1)!;
    if (uri.startsWith('package:keevo/')) {
      violations.add('$path → $uri (app package)');
    } else if (uri.contains('features/')) {
      violations.add('$path → $uri (feature)');
    } else if (!uri.contains(':')) {
      final ups = RegExp(r'\.\./').allMatches(uri).length;
      if (ups > depth) violations.add('$path → $uri (outside keevo_core)');
    }
  }
  return violations;
}

void main() {
  test('keevo_core lib and test import neither the app nor a feature', () {
    for (final dir in [_coreLib, _coreTest]) {
      expect(Directory(dir).existsSync(), isTrue,
          reason: '$dir not found — run from keevo/app');
    }
    final files = [Directory(_coreLib), Directory(_coreTest)]
        .expand((d) => d.listSync(recursive: true))
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .toList();
    expect(files.length, greaterThan(100));

    final violations = [
      for (final file in files)
        ...boundaryViolations(
            file.path.replaceAll(r'\', '/'), file.readAsStringSync()),
    ];
    expect(violations, isEmpty,
        reason: 'keevo_core must stay feature-free (move the file back to '
            'lib/core or invert the dependency):\n${violations.join('\n')}');
  });

  test('the guard flags app, feature and out-of-package imports', () {
    const path = '$_coreLib/sync/sample.dart';
    expect(
        boundaryViolations(
            path, "import 'package:keevo/core/di/providers.dart';"),
        hasLength(1));
    expect(
        boundaryViolations(path,
            "import '../../../../lib/features/auth/presentation/x.dart';"),
        isNotEmpty);
    expect(boundaryViolations(path, "import '../../../lib/main.dart';"),
        hasLength(1));
    expect(
        boundaryViolations('$_coreTest/sync/sample_test.dart',
            "import 'package:keevo/core/sync/rest_sync_service.dart';"),
        hasLength(1));
    expect(
        boundaryViolations(path,
            "import 'package:keevo_core/storage/app_database.dart';\n"
            "import '../storage/app_database.dart';\n"
            "import 'package:drift/drift.dart';"),
        isEmpty);
  });
}
