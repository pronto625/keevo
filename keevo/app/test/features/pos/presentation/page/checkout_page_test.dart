import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:keevo/features/contact/presentation/provider/contact_provider.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/page/checkout_page.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/pos/presentation/provider/record_sale_notifier.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';

class _FixedStore extends ActiveStoreNotifier {
  @override
  String? build() => 'store-1';
}

class _FakeClients extends ClientListNotifier {
  @override
  Future<List<ClientModel>> build() async => [
        ClientModel(
          id: 'c1',
          name: 'Hélène Ngo',
          phone: '690000001',
          createdAt: DateTime(2026, 1, 1),
          updatedAt: DateTime(2026, 1, 1),
        ),
        ClientModel(
          id: 'c2',
          name: 'Paul Biya',
          phone: '690000002',
          createdAt: DateTime(2026, 1, 1),
          updatedAt: DateTime(2026, 1, 1),
        ),
      ];
}

class _CapturingRecordSale extends RecordSaleNotifier {
  static PaymentModeEnum? mode;
  static String? mobileRef;
  static bool called = false;

  @override
  Future<void> submit({
    required List<CartItem> cart,
    required PaymentModeEnum mode,
    required String storeId,
    required String employeeId,
    String? clientId,
    String? mobileRef,
  }) async {
    called = true;
    _CapturingRecordSale.mode = mode;
    _CapturingRecordSale.mobileRef = mobileRef;
  }
}

CartItem _item({
  String id = 'p1',
  int price = 10000,
  int qty = 2,
  ProductStatus productStatus = ProductStatus.active,
}) =>
    CartItem(
      id: id,
      productId: id,
      productName: 'Produit',
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
      productStatus: productStatus,
    );

/// Wraps CheckoutPage with the required ProviderScope + MaterialApp.
/// Prepopulates the cart and optionally applies a discount.
Widget _buildApp({
  required List<CartItem> items,
  int discount = 0,
  List<Override> overrides = const [],
}) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp(
      home: Consumer(builder: (context, ref, _) {
        // Populate cart on first build
        WidgetsBinding.instance.addPostFrameCallback((_) {
          final notifier = ref.read(cartProvider.notifier);
          for (final item in items) {
            notifier.addItem(item);
          }
          if (discount > 0) {
            notifier.setDiscount(discount);
          }
        });
        return const CheckoutPage();
      }),
    ),
  );
}

void main() {
  group('CheckoutPage', () {
    testWidgets('shows discount breakdown when discount > 0', (tester) async {
      await tester.pumpWidget(
          _buildApp(items: [_item(price: 10000, qty: 1)], discount: 2000));
      await tester.pumpAndSettle();

      // Discount shown as pill inside the hero gradient card
      expect(find.textContaining('Réduction'), findsOneWidget);
    });

    testWidgets('shows simple total when no discount', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // No discount row
      expect(find.text('Réduction'), findsNothing);
      expect(find.text('Total à payer'), findsNothing);
    });

    testWidgets('Espèces shows Montant reçu field', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // Select Espèces (no emoji in new UI)
      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      expect(find.text('Montant reçu (optionnel)'), findsOneWidget);
    });

    testWidgets('change calculation with Espèces', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 5000, qty: 2)]));
      await tester.pumpAndSettle();

      // Select Espèces
      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      // Enter montant reçu = 15000 (total = 10000)
      await tester.enterText(
          find.bySemanticsLabel('Montant reçu (optionnel)'), '15000');
      await tester.pumpAndSettle();

      expect(find.textContaining('Monnaie à rendre'), findsOneWidget);
    });

    testWidgets('Valider button disabled when no payment mode selected',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // InkWell wraps the button — when disabled its onTap is null
      final inkWell = tester.widget<InkWell>(
          find.ancestor(
            of: find.text('Valider la vente'),
            matching: find.byType(InkWell),
          ));
      expect(inkWell.onTap, isNull);
    });

    testWidgets(
        'Valider button enabled when Espèces selected even without Montant reçu',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      // Montant reçu is now optional — button should be enabled
      final inkWell = tester.widget<InkWell>(
          find.ancestor(
            of: find.text('Valider la vente'),
            matching: find.byType(InkWell),
          ));
      expect(inkWell.onTap, isNotNull);
    });

    // ── AC1: Draft cart → "Vente brouillon" button label ────────────────

    testWidgets('shows Vente brouillon label when cart has a draft item',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [
        _item(id: 'd1', price: 3000, qty: 1, productStatus: ProductStatus.draft),
      ]));
      await tester.pumpAndSettle();

      expect(find.text('Vente brouillon'), findsOneWidget);
      expect(find.text('Valider la vente'), findsNothing);
    });

    // v1s-15-8 (deferred from CR v1s-13-6): one draft item is enough.
    testWidgets('mixed DRAFT + ACTIVE cart shows Vente brouillon',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [
        _item(id: 'a1', price: 2000, qty: 1),
        _item(id: 'd1', price: 3000, qty: 1, productStatus: ProductStatus.draft),
      ]));
      await tester.pumpAndSettle();

      expect(find.text('Vente brouillon'), findsOneWidget);
      expect(find.text('Valider la vente'), findsNothing);
    });

    // v1s-15-8 (deferred from CR v1s-13-6): an empty cart cannot be sold,
    // even once a payment mode is chosen.
    testWidgets('empty cart: Valider la vente label, button stays disabled',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: const []));
      await tester.pumpAndSettle();

      expect(find.text('Valider la vente'), findsOneWidget);
      expect(find.text('Vente brouillon'), findsNothing);

      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      final inkWell = tester.widget<InkWell>(find.ancestor(
        of: find.text('Valider la vente'),
        matching: find.byType(InkWell),
      ));
      expect(inkWell.onTap, isNull);
    });

    // ── AC2: No-draft cart → "Valider la vente" (non-regression) ───────────

    testWidgets('keeps Valider la vente label when cart has no draft item',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item()]));
      await tester.pumpAndSettle();

      expect(find.text('Valider la vente'), findsOneWidget);
      expect(find.text('Vente brouillon'), findsNothing);
    });
      testWidgets('Mobile Money ref typed then Espèces chosen: no ref is sent',
        (tester) async {
      _CapturingRecordSale.called = false;
      _CapturingRecordSale.mode = null;
      _CapturingRecordSale.mobileRef = null;
      await tester.pumpWidget(_buildApp(
        items: [_item(price: 10000, qty: 1)],
        overrides: [
          activeStoreIdProvider.overrideWith(_FixedStore.new),
          activeEmployeeIdProvider.overrideWith((ref) async => 'emp-1'),
          recordSaleNotifierProvider.overrideWith(_CapturingRecordSale.new),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Mobile Money'));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.widgetWithText(TextField, 'Référence Mobile Money'), 'REF-123');
      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Valider la vente'));
      await tester.pumpAndSettle();

      expect(_CapturingRecordSale.called, isTrue);
      expect(_CapturingRecordSale.mode, PaymentModeEnum.cash);
      expect(_CapturingRecordSale.mobileRef, isNull);
    });

    testWidgets('client autocomplete is accent-insensitive (v1s-16-16)',
        (tester) async {
      await tester.pumpWidget(_buildApp(
        items: [_item(price: 10000, qty: 1)],
        overrides: [clientListNotifierProvider.overrideWith(_FakeClients.new)],
      ));
      await tester.pumpAndSettle();

      final field = find.widgetWithText(TextField, 'Client (optionnel)');
      await tester.ensureVisible(field);
      await tester.tap(field);
      await tester.enterText(field, 'helene');
      await tester.pumpAndSettle();

      expect(find.text('Hélène Ngo — 690000001'), findsOneWidget);
      expect(find.text('Paul Biya — 690000002'), findsNothing);
    });
  });
}
