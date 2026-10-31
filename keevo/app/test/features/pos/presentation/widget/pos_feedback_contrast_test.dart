import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/page/checkout_page.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/pos/presentation/widget/cart_bottom_sheet.dart';
import 'package:keevo/features/pos/presentation/widget/pos_speed_dial.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/theme/app_theme.dart';

/// v1s-16-17 — POS feedback fills: every speed-dial option icon and the
/// disabled cart CTA label use the foreground matching their own fill
/// (AA) under the dark theme.
double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return ((la > lb ? la : lb) + 0.05) / ((la > lb ? lb : la) + 0.05);
}

Color _iconColor(WidgetTester tester, IconData icon) =>
    tester.widget<Icon>(find.byIcon(icon)).color!;

Color _circleFill(WidgetTester tester, IconData icon) {
  final box = tester.widget<Container>(
    find.ancestor(of: find.byIcon(icon), matching: find.byType(Container)).first,
  );
  return (box.decoration! as BoxDecoration).color!;
}

Future<void> _pumpOpenSpeedDial(
  WidgetTester tester,
  ThemeData theme,
  DayClosureState state,
) async {
  await tester.pumpWidget(
    ProviderScope(
      retry: noProviderRetry,
      overrides: [
        currentUserIdProvider.overrideWith((ref) => Future.value('u1')),
        dayClosureStateProvider('s1').overrideWith((ref) async => state),
      ],
      child: MaterialApp(
        theme: theme,
        home: Scaffold(
          floatingActionButton:
              PosSpeedDial(storeId: 's1', onCreateDraft: () {}),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byIcon(Icons.more_vert_rounded));
  await tester.pumpAndSettle();
}

Future<void> _pumpCartSheet(
  WidgetTester tester,
  ThemeData theme, {
  bool withItem = false,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      retry: noProviderRetry,
      child: MaterialApp(
        theme: theme,
        home: Scaffold(
          body: Consumer(builder: (context, ref, _) {
            return ElevatedButton(
              onPressed: () {
                if (withItem) {
                  ref.read(cartProvider.notifier).addItem(CartItem(
                        id: 'p1',
                        productId: 'p1',
                        productName: 'Savon',
                        unitPrice: 500,
                        appliedUnitPrice: 500,
                        quantity: 1,
                      ));
                }
                CartBottomSheet.show(context, onEncaisser: () {});
              },
              child: const Text('Open'),
            );
          }),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
}

/// Label colour + fill of the CTA [DecoratedBox] around [text].
(Color, Color) _cta(WidgetTester tester, String text) {
  final label = tester.widget<Text>(find.text(text));
  final box = tester.widget<DecoratedBox>(find
      .ancestor(of: find.text(text), matching: find.byType(DecoratedBox))
      .first);
  return (label.style!.color!, (box.decoration as BoxDecoration).color!);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  GoogleFonts.config.allowRuntimeFetching = false;

  final themes = {'light': AppTheme.light(), 'dark': AppTheme.dark()};

  testWidgets('speed-dial day open (dark): Clôturer violetDeep + onBrand, '
      'Produit warning + onWarning', (tester) async {
    await _pumpOpenSpeedDial(
        tester, themes['dark']!, DayClosureState.available);

    expect(_circleFill(tester, Icons.nightlight_round), AppTheme.violetDeep);
    expect(_iconColor(tester, Icons.nightlight_round), AppTheme.onBrand);
    expect(_circleFill(tester, Icons.add_rounded), AppTheme.warning);
    expect(_iconColor(tester, Icons.add_rounded), AppTheme.onWarning);
    expect(
        _contrast(_iconColor(tester, Icons.add_rounded),
            _circleFill(tester, Icons.add_rounded)),
        greaterThanOrEqualTo(4.5));
  });

  for (final entry in themes.entries) {
    final theme = entry.value;
    final cs = theme.colorScheme;

    testWidgets('speed-dial day closed (${entry.key}): onSurfaceVariant + '
        'surface', (tester) async {
      await _pumpOpenSpeedDial(tester, theme, DayClosureState.closed);

      final fill = _circleFill(tester, Icons.check_circle_rounded);
      final fg = _iconColor(tester, Icons.check_circle_rounded);
      expect(fill, cs.onSurfaceVariant);
      expect(fg, cs.surface);
      expect(_contrast(fg, fill), greaterThanOrEqualTo(4.5));
    });

    testWidgets('disabled cart CTA (${entry.key}): onSurfaceVariant on '
        'surfaceContainerHighest', (tester) async {
      await _pumpCartSheet(tester, theme);

      final (fg, fill) = _cta(tester, 'Encaisser');
      expect(fg, cs.onSurfaceVariant);
      expect(fill, cs.surfaceContainerHighest);
      expect(_contrast(fg, fill), greaterThanOrEqualTo(4.5));
    });

    testWidgets('disabled checkout CTA, no payment mode (${entry.key})',
        (tester) async {
      await tester.pumpWidget(ProviderScope(
        retry: noProviderRetry,
        child: MaterialApp(theme: theme, home: const CheckoutPage()),
      ));
      await tester.pumpAndSettle();

      final (fg, fill) = _cta(tester, 'Valider la vente');
      expect(fg, cs.onSurfaceVariant);
      expect(fill, cs.surfaceContainerHighest);
      expect(_contrast(fg, fill), greaterThanOrEqualTo(4.5));
    });
  }

  testWidgets('enabled cart CTA (dark): primary + onBrand', (tester) async {
    await _pumpCartSheet(tester, themes['dark']!, withItem: true);

    final (fg, fill) = _cta(tester, 'Encaisser');
    expect(fill, AppTheme.primary);
    expect(fg, AppTheme.onBrand);
  });
}
