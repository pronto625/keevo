import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 15.4a / 15.4c — dependency boundaries of the workspace packages
/// (every folder under `packages/`, `lib/` and `test/`):
/// - `keevo_core` is the feature-free kernel: it imports neither the app
///   (`package:keevo/…`), a feature (`features/…`) nor a module package;
/// - a module package (`keevo_invoicing`, …) imports `keevo_core`, its own
///   files and third-party packages only: never the app, never another
///   module package;
/// - no package reaches outside itself with a relative path;
/// - the app's `lib/` reaches a module package only through its descriptor
///   library (`package:keevo_<m>/<m>_module.dart`), never its screens;
/// - pubspecs: `keevo_core` depends on no `keevo*` package, a module package
///   on `keevo_core` only among them.
const _packagesDir = 'packages';
const _corePackage = 'keevo_core';

final _directive = RegExp(
  r'''^\s*(?:import|export|part)\s+['"]([^'"]+)['"]''',
  multiLine: true,
);

final _keevoPackage = RegExp(r'^package:(keevo_\w+)/');

/// Offending directives of [source] (a file at [path], under
/// `packages/<package>/`) — empty when it respects the boundary.
List<String> boundaryViolations(String path, String source) {
  final segments = path.split('/');
  final root = segments.indexOf(_packagesDir);
  final package = segments[root + 1];
  final depth = segments.length - root - 3;
  final isCore = package == _corePackage;
  final violations = <String>[];
  for (final match in _directive.allMatches(source)) {
    final uri = match.group(1)!;
    final other = _keevoPackage.firstMatch(uri)?.group(1);
    if (uri.startsWith('package:keevo/')) {
      violations.add('$path → $uri (app package)');
    } else if (isCore && uri.contains('features/')) {
      violations.add('$path → $uri (feature)');
    } else if (other != null && other != package && other != _corePackage) {
      violations.add('$path → $uri (module package $other)');
    } else if (!uri.contains(':')) {
      final ups = RegExp(r'\.\./').allMatches(uri).length;
      if (ups > depth) violations.add('$path → $uri (outside $package)');
    }
  }
  return violations;
}

/// Offending directives of [source] (a file of the app's `lib/` at [path]):
/// a module package imported other than through its descriptor library.
List<String> appModuleImportViolations(String path, String source) {
  final violations = <String>[];
  for (final match in _directive.allMatches(source)) {
    final uri = match.group(1)!;
    final other = _keevoPackage.firstMatch(uri)?.group(1);
    if (other == null || other == _corePackage) continue;
    final module = other.substring('keevo_'.length);
    if (uri != 'package:$other/${module}_module.dart') {
      violations.add('$path → $uri (module internals: use the descriptor)');
    }
  }
  return violations;
}

/// `keevo*` packages listed under `dependencies:` / `dev_dependencies:` of
/// [pubspec] (top-level keys of those sections only).
Set<String> keevoDependencies(String pubspec) {
  final deps = <String>{};
  var inDeps = false;
  for (final line in pubspec.split('\n')) {
    if (RegExp(r'^\S').hasMatch(line)) {
      inDeps = RegExp(r'^(dev_)?dependencies:\s*$').hasMatch(line);
      continue;
    }
    if (!inDeps) continue;
    final key = RegExp(r'^  (keevo\w*):').firstMatch(line)?.group(1);
    if (key != null) deps.add(key);
  }
  return deps;
}

/// Dependency violations of [package] whose pubspec is [pubspec].
List<String> pubspecViolations(String package, String pubspec) {
  final allowed = package == _corePackage ? <String>{} : {_corePackage};
  return [
    for (final dep in keevoDependencies(pubspec))
      if (!allowed.contains(dep)) '$package depends on $dep',
  ];
}

void main() {
  test('the app lib reaches module packages through their descriptor', () {
    final violations = [
      for (final file
          in Directory('lib')
              .listSync(recursive: true)
              .whereType<File>()
              .where((f) => f.path.endsWith('.dart')))
        ...appModuleImportViolations(
          file.path.replaceAll(r'\', '/'),
          file.readAsStringSync(),
        ),
    ];
    expect(violations, isEmpty, reason: violations.join('\n'));
  });

  test('package pubspecs: core depends on no keevo package, a module on '
      'keevo_core only', () {
    final violations = <String>[];
    var scanned = 0;
    for (final dir in Directory(
      _packagesDir,
    ).listSync().whereType<Directory>()) {
      final pubspec = File('${dir.path}/pubspec.yaml');
      if (!pubspec.existsSync()) continue;
      scanned++;
      final name = dir.path.replaceAll(r'\', '/').split('/').last;
      violations.addAll(pubspecViolations(name, pubspec.readAsStringSync()));
    }
    expect(scanned, greaterThanOrEqualTo(2));
    expect(violations, isEmpty, reason: violations.join('\n'));
  });

  test('the app and pubspec guards flag what they must', () {
    const file = 'lib/core/modules/registered_modules.dart';
    expect(
      appModuleImportViolations(
        file,
        "import 'package:keevo_invoicing/invoicing_module.dart';\n"
        "import 'package:keevo_core/module/keevo_module_ui.dart';",
      ),
      isEmpty,
    );
    expect(
      appModuleImportViolations(
        file,
        "import 'package:keevo_invoicing/presentation/page/"
        "invoice_detail_page.dart';",
      ),
      hasLength(1),
    );
    expect(
      appModuleImportViolations(
        file,
        "import 'package:keevo_crm/invoicing_module.dart';",
      ),
      hasLength(1),
    );

    const core =
        'name: keevo_core\ndependencies:\n  flutter:\n'
        '    sdk: flutter\n  dio: ^5.7.0\n';
    expect(pubspecViolations('keevo_core', core), isEmpty);
    expect(
      pubspecViolations(
        'keevo_core',
        '$core  keevo_invoicing:\n    path: ../keevo_invoicing\n',
      ),
      hasLength(1),
    );
    const module =
        'name: keevo_invoicing\ndependencies:\n'
        '  keevo_core:\n    path: ../keevo_core\n';
    expect(pubspecViolations('keevo_invoicing', module), isEmpty);
    expect(
      pubspecViolations(
        'keevo_invoicing',
        '${module}dev_dependencies:\n  keevo_crm:\n    path: ../keevo_crm\n',
      ),
      hasLength(1),
    );
    expect(
      pubspecViolations(
        'keevo_invoicing',
        '$module  keevo:\n    path: ../..\n',
      ),
      hasLength(1),
    );
  });

  test('every workspace package respects its boundary', () {
    final packages = Directory(_packagesDir)
        .listSync()
        .whereType<Directory>()
        .where((d) => File('${d.path}/pubspec.yaml').existsSync())
        .toList();
    final names = packages.map(
      (d) => d.path.replaceAll(r'\', '/').split('/').last,
    );
    expect(
      names,
      containsAll(['keevo_core', 'keevo_invoicing']),
      reason: '$_packagesDir not found — run from keevo/app',
    );

    final violations = <String>[];
    for (final package in packages) {
      final dirs = [
        for (final sub in ['lib', 'test'])
          if (Directory('${package.path}/$sub').existsSync())
            Directory('${package.path}/$sub'),
      ];
      final files = dirs
          .expand((d) => d.listSync(recursive: true))
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'))
          .toList();
      expect(files, isNotEmpty, reason: '${package.path} has no Dart file');
      for (final file in files) {
        violations.addAll(
          boundaryViolations(
            file.path.replaceAll(r'\', '/'),
            file.readAsStringSync(),
          ),
        );
      }
    }
    expect(
      violations,
      isEmpty,
      reason:
          'packages must not import the app, a feature or another '
          'module (move the code to keevo_core or add a port):\n'
          '${violations.join('\n')}',
    );
  });

  test('the kernel is scanned (sanity: it holds the moved lib/core)', () {
    final files = Directory('$_packagesDir/$_corePackage/lib')
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'));
    expect(files.length, greaterThan(50));
  });

  test('the guard flags app, feature, module and out-of-package imports', () {
    const core = '$_packagesDir/keevo_core/lib/sync/sample.dart';
    const coreTest = '$_packagesDir/keevo_core/test/sync/sample_test.dart';
    const module =
        '$_packagesDir/keevo_invoicing/lib/presentation/provider/x.dart';
    expect(
      boundaryViolations(
        core,
        "import 'package:keevo/core/di/providers.dart';",
      ),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        core,
        "import '../../../../lib/features/auth/presentation/x.dart';",
      ),
      isNotEmpty,
    );
    expect(
      boundaryViolations(core, "import '../../../lib/main.dart';"),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        coreTest,
        "import 'package:keevo/core/sync/rest_sync_service.dart';",
      ),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        core,
        "import 'package:keevo_invoicing/invoicing_module.dart';",
      ),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        core,
        "import 'package:keevo_core/storage/app_database.dart';\n"
        "import '../storage/app_database.dart';\n"
        "import 'package:drift/drift.dart';",
      ),
      isEmpty,
    );

    // Module package: app and other modules are refused, core and own files
    // are fine.
    expect(
      boundaryViolations(
        module,
        "import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';",
      ),
      hasLength(1),
    );
    expect(
      boundaryViolations(module, "import 'package:keevo_crm/crm_module.dart';"),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        module,
        "import '../../../../../lib/features/export/x.dart';",
      ),
      hasLength(1),
    );
    expect(
      boundaryViolations(
        module,
        "import 'package:keevo_core/module/module_ports.dart';\n"
        "import 'package:keevo_invoicing/domain/model/invoice.dart';\n"
        "import '../../data/remote_invoice_datasource.dart';\n"
        "import 'package:dio/dio.dart';",
      ),
      isEmpty,
    );
  });
}
