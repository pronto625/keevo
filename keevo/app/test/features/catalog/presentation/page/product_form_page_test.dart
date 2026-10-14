import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/presentation/page/product_form_page.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';

/// Opens the form on top of a home route, so "back" has somewhere to go.
Future<void> _openForm(WidgetTester tester) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [categoriesProvider.overrideWith((_) async => [])],
      child: MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => const ProductFormPage())),
              child: const Text('Ouvrir'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Ouvrir'));
  await tester.pumpAndSettle();
}

Future<void> _tapBack(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.arrow_back_ios_new_rounded));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('back without changes leaves without asking', (tester) async {
    await _openForm(tester);

    await _tapBack(tester);

    expect(find.text('Abandonner les modifications ?'), findsNothing);
    expect(find.text('Ouvrir'), findsOneWidget);
  });

  testWidgets('back with unsaved changes asks; Continuer stays on the form',
      (tester) async {
    await _openForm(tester);
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Nom du produit *'), 'Savon');

    await _tapBack(tester);
    expect(find.text('Abandonner les modifications ?'), findsOneWidget);

    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Nouveau produit'), findsOneWidget);
    expect(find.text('Savon'), findsOneWidget);
  });

  testWidgets('Abandonner leaves the form', (tester) async {
    await _openForm(tester);
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Nom du produit *'), 'Savon');

    await _tapBack(tester);
    await tester.tap(find.text('Abandonner'));
    await tester.pumpAndSettle();

    expect(find.text('Ouvrir'), findsOneWidget);
  });

  testWidgets('side-by-side price fields leave the whole width to the label',
      (tester) async {
    await _openForm(tester);

    // On a 720 px phone the prefix icon left « Prix de vente » cut to
    // « Prix de ve… »; the pair now carries no icon.
    for (final label in ['Prix de vente', "Prix d'achat"]) {
      final field = tester.widget<TextField>(find.descendant(
          of: find.widgetWithText(TextFormField, label),
          matching: find.byType(TextField)));
      expect(field.decoration?.prefixIcon, isNull, reason: label);
    }
  });
}
