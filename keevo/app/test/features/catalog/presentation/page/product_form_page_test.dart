import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';
import 'package:keevo/core/photo/product_photo_providers.dart';
import 'package:keevo/features/catalog/presentation/page/product_form_page.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';
import 'package:keevo_core/photo/product_photo_compressor.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockImagePicker extends Mock implements ImagePicker {}

class _FakeCompressor extends ProductPhotoCompressor {
  const _FakeCompressor();

  static final received = <int>[];
  static final thumb =
      img.encodeJpg(img.Image(width: 400, height: 300), quality: 70);

  @override
  Future<Uint8List> compress(Uint8List source) async {
    received.add(source.length);
    return thumb;
  }
}

/// Opens the form on top of a home route, so "back" has somewhere to go.
Future<void> _openForm(WidgetTester tester,
    {List<Override> overrides = const []}) async {
  await tester.pumpWidget(
    ProviderScope(
      retry: noProviderRetry,
      overrides: [
        categoriesProvider.overrideWith((_) async => []),
        ...overrides,
      ],
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

  testWidgets(
      'photo: picked at 400 px / quality 70, compressed on the device before '
      'anything else, its size shown; « Supprimer la photo » clears it',
      (tester) async {
    registerFallbackValue(ImageSource.camera);
    final picker = _MockImagePicker();
    when(() => picker.pickImage(
          source: any(named: 'source'),
          maxWidth: any(named: 'maxWidth'),
          maxHeight: any(named: 'maxHeight'),
          imageQuality: any(named: 'imageQuality'),
        )).thenAnswer((_) async => XFile.fromData(Uint8List(300 * 1024)));
    _FakeCompressor.received.clear();
    await _openForm(tester, overrides: [
      productPhotoPickerProvider.overrideWithValue(picker),
      productPhotoCompressorProvider.overrideWithValue(const _FakeCompressor()),
    ]);

    tester.view.physicalSize = const Size(1080, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpAndSettle();
    final tile = find.byIcon(Icons.add_photo_alternate_rounded);
    await tester.ensureVisible(tile);
    await tester.pumpAndSettle();
    await tester.tap(tile);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Choisir depuis la galerie'));
    await tester.pumpAndSettle();

    verify(() => picker.pickImage(
          source: ImageSource.gallery,
          maxWidth: 400,
          maxHeight: 400,
          imageQuality: 70,
        )).called(1);
    expect(_FakeCompressor.received, [300 * 1024]);
    final ko = (_FakeCompressor.thumb.length / 1024).toStringAsFixed(1);
    expect(find.text('Taille : $ko Ko'), findsOneWidget);

    await tester.tap(find.byTooltip('Supprimer la photo'));
    await tester.pumpAndSettle();
    expect(find.text('Ajouter une photo'), findsWidgets);
    expect(find.textContaining('Taille'), findsNothing);
  });
}
