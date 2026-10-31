import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';

Widget _wrap(Widget child, {Brightness brightness = Brightness.light}) =>
    MaterialApp(
      theme: ThemeData(brightness: brightness),
      home: Scaffold(body: Center(child: child)),
    );

Color? _backgroundOf(WidgetTester tester, Type type) {
  final container = tester.widget<Container>(
    find.descendant(of: find.byType(type), matching: find.byType(Container)).first,
  );
  return (container.decoration! as BoxDecoration).color;
}

void main() {
  group('SoftIconButton', () {
    testWidgets('invokes onPressed and exposes its tooltip', (tester) async {
      var taps = 0;
      await tester.pumpWidget(_wrap(SoftIconButton(
        icon: Icons.sync_rounded,
        tooltip: 'Synchroniser',
        onPressed: () => taps++,
      )));

      await tester.tap(find.byIcon(Icons.sync_rounded));
      expect(taps, 1);
      expect(find.byTooltip('Synchroniser'), findsOneWidget);
    });

    testWidgets('is disabled when onPressed is null', (tester) async {
      await tester.pumpWidget(_wrap(const SoftIconButton(
        icon: Icons.sync_rounded,
        tooltip: 'Synchroniser',
        onPressed: null,
      )));

      final button = tester.widget<IconButton>(find.byType(IconButton));
      expect(button.onPressed, isNull);
    });

    testWidgets('pastille follows brightness', (tester) async {
      await tester.pumpWidget(_wrap(
        SoftIconButton(icon: Icons.add, tooltip: 'Ajouter', onPressed: () {}),
      ));
      expect(_backgroundOf(tester, SoftIconButton), AppTheme.primarySoftTint);

      await tester.pumpWidget(_wrap(
        SoftIconButton(icon: Icons.add, tooltip: 'Ajouter', onPressed: () {}),
        brightness: Brightness.dark,
      ));
      await tester.pumpAndSettle();
      expect(
        _backgroundOf(tester, SoftIconButton),
        AppTheme.primarySoftTintDark,
      );
    });
  });

  group('SoftIconBadge', () {
    testWidgets('defaults to the brand tint and primary icon', (tester) async {
      await tester.pumpWidget(_wrap(const SoftIconBadge(icon: Icons.store)));

      expect(_backgroundOf(tester, SoftIconBadge), AppTheme.primarySoftTint);
      final icon = tester.widget<Icon>(find.byIcon(Icons.store));
      final context = tester.element(find.byType(SoftIconBadge));
      expect(icon.color, Theme.of(context).colorScheme.primary);
    });

    testWidgets('a semantic color tints both background and icon',
        (tester) async {
      await tester.pumpWidget(_wrap(const SoftIconBadge(
        icon: Icons.warning_amber_rounded,
        color: AppTheme.errorColor,
      )));

      expect(
        _backgroundOf(tester, SoftIconBadge),
        AppTheme.errorColor.withValues(alpha: 0.12),
      );
      final icon = tester.widget<Icon>(find.byIcon(Icons.warning_amber_rounded));
      expect(icon.color, AppTheme.errorColor);
    });
  });

  group('AppTheme soft surfaces', () {
    test('softCardDecoration uses the surface, a large radius and one shadow',
        () {
      final cs = AppTheme.light().colorScheme;
      final decoration = AppTheme.softCardDecoration(cs);

      expect(decoration.color, cs.surface);
      expect(decoration.borderRadius, BorderRadius.circular(AppTheme.radiusL));
      expect(decoration.boxShadow, AppTheme.softShadow(cs));
      expect(decoration.boxShadow, hasLength(1));
    });
  });
}
