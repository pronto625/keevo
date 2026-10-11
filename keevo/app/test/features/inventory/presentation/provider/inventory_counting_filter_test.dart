import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/presentation/provider/inventory_counting_provider.dart';

void main() {
  test('filter and search reset once the counting page stops listening',
      () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    final filterSub =
        container.listen(inventoryCountFilterProvider, (_, __) {});
    final searchSub =
        container.listen(inventoryCountSearchProvider, (_, __) {});
    container.read(inventoryCountFilterProvider.notifier).state =
        InventoryCountFilter.discrepancies;
    container.read(inventoryCountSearchProvider.notifier).state = 'riz';

    // Page closed.
    filterSub.close();
    searchSub.close();
    await container.pump();

    // Page reopened.
    expect(container.read(inventoryCountFilterProvider),
        InventoryCountFilter.uncounted);
    expect(container.read(inventoryCountSearchProvider), '');
  });
}
