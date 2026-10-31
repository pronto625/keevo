import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 15.4b — the app is on Riverpod 3: state lives in `Notifier` /
/// `AsyncNotifier` (or `@riverpod`), never in the legacy providers that
/// Riverpod 3 moved to `legacy.dart`. Nothing under `lib/` nor
/// `packages/*/lib/` may use `StateNotifier`, `StateNotifierProvider`,
/// `StateProvider`, `StateController`, `ChangeNotifierProvider` or import a
/// `legacy.dart` of riverpod. Comments are ignored (they may name history).
const _roots = ['lib', 'packages'];

final _legacySymbol = RegExp(
  r'\b(StateNotifier|StateNotifierProvider|StateProvider|StateController|'
  r'ChangeNotifierProvider)\b',
);

final _legacyImport = RegExp(
  r'''['"]package:(?:flutter_|hooks_)?riverpod/legacy\.dart['"]''',
);

final _lineComment = RegExp(r'//.*$', multiLine: true);
final _blockComment = RegExp(r'/\*.*?\*/', dotAll: true);

/// Legacy Riverpod usages of [source] (a file at [path]) — empty when clean.
List<String> legacyRiverpodUsages(String path, String source) {
  final code =
      source.replaceAll(_blockComment, '').replaceAll(_lineComment, '');
  return [
    for (final m in _legacyImport.allMatches(code)) '$path → ${m.group(0)}',
    for (final m in _legacySymbol.allMatches(code)) '$path → ${m.group(1)}',
  ];
}

bool _isLibFile(String path) =>
    path.endsWith('.dart') &&
    (path.startsWith('lib/') || RegExp(r'^packages/[^/]+/lib/').hasMatch(path));

void main() {
  test('lib/ and packages/*/lib/ use no legacy Riverpod provider', () {
    for (final dir in _roots) {
      expect(Directory(dir).existsSync(), isTrue,
          reason: '$dir not found — run from keevo/app');
    }
    final files = _roots
        .expand((d) => Directory(d).listSync(recursive: true))
        .whereType<File>()
        .map((f) => (path: f.path.replaceAll(r'\', '/'), file: f))
        .where((f) => _isLibFile(f.path))
        .toList();
    expect(files.where((f) => f.path.startsWith('lib/')).length,
        greaterThan(100));
    expect(files.where((f) => f.path.startsWith('packages/')).length,
        greaterThan(50));

    final usages = [
      for (final f in files)
        ...legacyRiverpodUsages(f.path, f.file.readAsStringSync()),
    ];
    expect(usages, isEmpty,
        reason: 'Riverpod 3: use Notifier / AsyncNotifier (or @riverpod; '
            'SettableNotifier of keevo_core for a plain settable value) '
            'instead of:\n${usages.join('\n')}');
  });

  test('the guard flags legacy providers and imports, not look-alikes', () {
    const path = 'lib/features/x/x.dart';
    expect(
        legacyRiverpodUsages(
            path, "import 'package:flutter_riverpod/legacy.dart';"),
        hasLength(1));
    expect(
        legacyRiverpodUsages(path, "import 'package:riverpod/legacy.dart';"),
        hasLength(1));
    expect(
        legacyRiverpodUsages(
            path, 'final p = StateProvider<int>((ref) => 0);'),
        hasLength(1));
    expect(
        legacyRiverpodUsages(
            path, 'class A extends StateNotifier<int> { A() : super(0); }'),
        hasLength(1));
    expect(
        legacyRiverpodUsages(path,
            'final p = StateNotifierProvider<A, int>((ref) => A());'),
        hasLength(1));
    expect(
        legacyRiverpodUsages(path,
            'final p = ChangeNotifierProvider((ref) => Model());'),
        hasLength(1));
    expect(
        legacyRiverpodUsages(path, 'late final StateController<int> c;'),
        hasLength(1));
    expect(
        legacyRiverpodUsages(path,
            'ref.watch(syncGateStateProvider);\n'
            'ref.invalidate(dayClosureStateProvider);\n'
            '// was a StateProvider before Riverpod 3\n'
            '/* StateNotifier */\n'
            'final p = NotifierProvider<N, int>(N.new);'),
        isEmpty);
  });
}
