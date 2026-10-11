import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/repository/category_repository.dart';
import 'package:keevo/features/catalog/presentation/page/categories_page.dart';
import 'package:keevo/features/catalog/presentation/provider/category_provider.dart';

class _MockCategoryRepository extends Mock implements CategoryRepository {}

void main() {
  testWidgets('create failure shows a clean message, not "Exception: …"',
      (tester) async {
    final repo = _MockCategoryRepository();
    when(() => repo.createCustomCategory(any(),
            parentId: any(named: 'parentId')))
        .thenThrow(Exception('Cette catégorie existe déjà'));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        categoryRepositoryProvider.overrideWithValue(repo),
        categoriesProvider.overrideWith((_) async => const []),
      ],
      child: const MaterialApp(home: CategoriesPage()),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'Boissons');
    await tester.tap(find.text('Ajouter'));
    await tester.pumpAndSettle();

    expect(find.text('Cette catégorie existe déjà'), findsOneWidget);
    expect(find.textContaining('Exception'), findsNothing);
  });
}
