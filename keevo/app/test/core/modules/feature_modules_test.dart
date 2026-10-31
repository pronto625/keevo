import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/modules/feature_modules.dart';
import 'package:keevo/core/router/module_routes.dart';

/// Story 15.8 — every feature folder and module package is attached to a
/// backend module (manifest id), and every gated surface route belongs to its
/// module in the route registry. Run from `keevo/app` (like the other
/// contract tests).
void main() {
  const manifestsDir = '../backend/src/main/resources/keevo/modules';

  /// Every shipped manifest, by id. Fails with the file name when a manifest
  /// is not a JSON object with a string `id` and a boolean `free`.
  Map<String, Map<String, dynamic>> manifests() {
    final result = <String, Map<String, dynamic>>{};
    for (final dir in Directory(manifestsDir).listSync().whereType<Directory>()) {
      final file = File('${dir.path}/module.json');
      if (!file.existsSync()) continue;
      final json = jsonDecode(file.readAsStringSync());
      if (json is! Map<String, dynamic>) {
        fail('${file.path}: not a JSON object');
      }
      final id = json['id'];
      if (id is! String || id.isEmpty) {
        fail('${file.path}: no string "id"');
      }
      if (json['free'] is! bool) fail('${file.path}: no boolean "free"');
      result[id] = json;
    }
    return result;
  }

  Set<String> manifestIds() => manifests().keys.toSet();

  Set<String> freeIds() => {
        for (final e in manifests().entries)
          if (e.value['free'] == true) e.key,
      };

  Set<String> dirNames(String path) => Directory(path)
      .listSync()
      .whereType<Directory>()
      .map((d) => d.uri.pathSegments.where((s) => s.isNotEmpty).last)
      .toSet();

  test('the backend manifests are readable', () {
    expect(manifestIds(), containsAll(['core', 'invoicing', 'advanced-reports']));
  });

  test('every lib/features folder is mapped to a module', () {
    final missing = dirNames('lib/features').difference(kFeatureModules.keys.toSet());
    expect(missing, isEmpty, reason: 'add them to kFeatureModules');
  });

  test('every module package (except keevo_core) is mapped to a module', () {
    final packages = dirNames('packages')..remove('keevo_core');
    expect(packages, isNotEmpty);
    expect(packages.difference(kFeatureModules.keys.toSet()), isEmpty);
  });

  test('no stale entry: each key is a feature folder or a package', () {
    final known = {...dirNames('lib/features'), ...dirNames('packages')};
    expect(kFeatureModules.keys.toSet().difference(known), isEmpty);
  });

  test('every mapped module id is a manifest id', () {
    final ids = manifestIds();
    kFeatureModules.forEach((feature, mapping) {
      expect(ids, contains(mapping.module), reason: feature);
      for (final s in mapping.premium) {
        expect(ids, contains(s.module), reason: '$feature › ${s.label}');
      }
    });
  });

  test('a surface is of another module than its feature', () {
    kFeatureModules.forEach((feature, mapping) {
      for (final s in mapping.premium) {
        expect(s.module, isNot(mapping.module),
            reason: '$feature › ${s.label}');
      }
    });
  });

  test('a surface route belongs to its module in the route registry', () {
    kFeatureModules.forEach((feature, mapping) {
      for (final s in mapping.premium) {
        if (s.route == null) continue;
        expect(moduleOfPath(s.route!), s.module,
            reason: '$feature › ${s.label} (${s.route})');
      }
    });
  });

  test('every paid module is mapped (main module or surface)', () {
    final paid = manifestIds().difference(freeIds());
    expect(paid, contains('advanced-reports'));
    final covered = {
      for (final m in kFeatureModules.values) ...[
        m.module,
        for (final s in m.premium) s.module,
      ],
    };
    expect(paid.difference(covered), isEmpty);
  });

  test('every module with app routes is mapped', () {
    final covered = {
      for (final m in kFeatureModules.values) ...[
        m.module,
        for (final s in m.premium) s.module,
      ],
    };
    expect(kModuleRoutes.keys.toSet().difference(covered), isEmpty);
  });

  test('a server-side surface has no route', () {
    for (final m in kFeatureModules.values) {
      for (final s in m.premium.where((s) => s.serverSide)) {
        expect(s.route, isNull, reason: s.label);
      }
    }
  });

  test('only the debug feature is debug-only', () {
    expect(
      kFeatureModules.entries
          .where((e) => e.value.debugOnly)
          .map((e) => e.key),
      ['debug'],
    );
  });
}
