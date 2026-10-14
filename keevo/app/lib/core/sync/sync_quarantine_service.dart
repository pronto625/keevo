import 'dart:convert';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../di/providers.dart';
import '../storage/app_database.dart';
import 'sync_event_logger.dart';
import 'sync_rejection_messages.dart';
import 'sync_rejection_policy.dart';

/// Actions on quarantined ("bloquées") sync operations (v1s-16-15).
///
/// A money / stock-ledger operation refused for good by the server is kept
/// in `sync_queue` with `quarantined_at` set (see `sync_rejection_policy.dart`).
/// Every action works on the operation's *chain*: a quarantined `CREATE_SALE`
/// plus its quarantined `VALIDATE_SALE` / `CANCEL_SALE` (same `entityId`).
/// Each action runs in one Drift transaction. Nothing is deleted here without
/// the caller having exported the chain first ([exportText]) — the UI only
/// calls [abandon] after a share sheet that was not dismissed.
class SyncQuarantineService {
  final AppDatabase _db;
  final SyncEventLogger _logger;

  SyncQuarantineService(this._db, this._logger);

  /// The quarantined chain [opId] belongs to, head first (the `CREATE_SALE`
  /// when there is one), then the chained operations oldest first. Empty when
  /// [opId] is not quarantined (any more).
  Future<List<SyncQueueData>> chainOf(String opId) async {
    final op = await (_db.select(_db.syncQueue)
          ..where((t) => t.id.equals(opId) & t.quarantinedAt.isNotNull()))
        .getSingleOrNull();
    if (op == null) return const [];
    final entityId = op.entityId;
    if (entityId == null) return [op];

    var head = op;
    if (saleChainOperations.contains(op.operation)) {
      final sale = await (_db.select(_db.syncQueue)
            ..where((t) =>
                t.quarantinedAt.isNotNull() &
                t.entityId.equals(entityId) &
                t.operation.equals('CREATE_SALE'))
            ..limit(1))
          .getSingleOrNull();
      if (sale != null) head = sale;
    }
    if (head.operation != 'CREATE_SALE') return [head];
    final chained = await (_db.select(_db.syncQueue)
          ..where((t) =>
              t.quarantinedAt.isNotNull() &
              t.entityId.equals(entityId) &
              t.operation.isIn(saleChainOperations))
          ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
        .get();
    return [head, ...chained];
  }

  /// *Renvoyer*: takes the chain out of quarantine. The caller triggers the
  /// push; a new refusal puts it back (with a new notice).
  Future<void> release(String opId) => _db.transaction(() async {
        await _releaseChain(await chainOf(opId));
      });

  Future<void> _releaseChain(List<SyncQueueData> chain) async {
    if (chain.isEmpty) return;
    await (_db.update(_db.syncQueue)
          ..where((t) => t.id.isIn(chain.map((o) => o.id))))
        .write(const SyncQueueCompanion(quarantinedAt: Value(null)));
  }

  /// *Rattacher* (OWNER, `CREATE_SALE` only): re-points the sale lines whose
  /// product is a key of [productIds] to the mapped local product (the
  /// variant is dropped), and the sale to [storeId] when given — in the
  /// queued payload and in the local `sale_items` / `sales` rows — then
  /// releases the chain. The caller triggers the push.
  Future<void> reattachSale(
    String opId, {
    Map<String, String> productIds = const {},
    String? storeId,
  }) =>
      _db.transaction(() async {
        final chain = await chainOf(opId);
        if (chain.isEmpty) return;
        final head = chain.first;
        if (head.operation != 'CREATE_SALE') {
          throw StateError('Only a sale can be re-attached');
        }
        final payload = Map<String, dynamic>.of(_payloadOf(head));
        // The line takes the chosen product's name: the ghost one is gone.
        final names = {
          for (final p in await (_db.select(_db.products)
                ..where((t) => t.id.isIn(productIds.values)))
              .get())
            p.id: p.name,
        };
        final items = payload['items'];
        if (items is List) {
          payload['items'] = [
            for (final item in items)
              if (item is Map && productIds.containsKey(item['productId']))
                (Map<String, dynamic>.of(item.cast<String, dynamic>())
                  ..['productId'] = productIds[item['productId']]
                  ..['productName'] =
                      names[productIds[item['productId']]] ?? item['productName']
                  ..remove('variantId'))
              else
                item,
          ];
        }
        if (storeId != null) payload['storeId'] = storeId;
        await (_db.update(_db.syncQueue)..where((t) => t.id.equals(head.id)))
            .write(SyncQueueCompanion(payload: Value(jsonEncode(payload))));
        // A chained validation still names the ghost products: a draft
        // remapping / initial stock entry for a product the sale no longer
        // has would be refused again (PRODUCT_NOT_FOUND) and re-quarantine
        // the chain. The line now points to an existing product, which keeps
        // its own stock: those entries are dropped.
        // Any queued validation of the sale (chained, or queued since).
        final validations = head.entityId == null || productIds.isEmpty
            ? const <SyncQueueData>[]
            : await (_db.select(_db.syncQueue)
                  ..where((t) =>
                      t.synced.equals(false) &
                      t.entityId.equals(head.entityId!) &
                      t.operation.equals('VALIDATE_SALE')))
                .get();
        for (final op in validations) {
          final validate = Map<String, dynamic>.of(_payloadOf(op));
          final dropped = <Object?>{...productIds.keys};
          final remaps = validate['productIdRemappings'];
          if (remaps is Map) {
            final kept = <String, dynamic>{};
            for (final MapEntry(:key, :value) in remaps.entries) {
              if (productIds.containsKey(key) ||
                  productIds.containsKey(value)) {
                dropped.add(value);
              } else {
                kept['$key'] = value;
              }
            }
            if (kept.isEmpty) {
              validate.remove('productIdRemappings');
            } else {
              validate['productIdRemappings'] = kept;
            }
          }
          final entries = validate['initialStockEntries'];
          if (entries is Map) {
            final kept = {
              for (final MapEntry(:key, :value) in entries.entries)
                if (!dropped.contains(key)) '$key': value,
            };
            if (kept.isEmpty) {
              validate.remove('initialStockEntries');
            } else {
              validate['initialStockEntries'] = kept;
            }
          }
          await (_db.update(_db.syncQueue)..where((t) => t.id.equals(op.id)))
              .write(SyncQueueCompanion(payload: Value(jsonEncode(validate))));
        }

        final saleId = head.entityId ?? payload['saleId'] as String?;
        if (saleId != null) {
          for (final MapEntry(key: from, value: to) in productIds.entries) {
            // The sale's local stock movements share the sale item ids.
            await _db.customStatement(
              'UPDATE stock_movements SET product_id = ?, variant_id = NULL '
              'WHERE product_id = ? AND id IN '
              '(SELECT id FROM sale_items WHERE sale_id = ?)',
              [to, from, saleId],
            );
            final name = names[to];
            await _db.customStatement(
              'UPDATE sale_items SET product_id = ?, variant_id = NULL'
              '${name != null ? ', product_name = ?' : ''} '
              'WHERE sale_id = ? AND product_id = ?',
              [to, if (name != null) name, saleId, from],
            );
          }
          if (storeId != null) {
            await _db.customStatement(
                'UPDATE sales SET store_id = ? WHERE id = ?', [storeId, saleId]);
          }
        }
        await _releaseChain(chain);
      });

  /// *Abandonner* (OWNER, after a successful export): deletes the chain from
  /// the queue and the local rows it would have created (sale + items, or an
  /// unsynced day closure), and records "Opération abandonnée" in the local
  /// sync history. Local stock is left untouched: the goods physically left,
  /// the next server refresh sets the true level. Returns false when the
  /// chain was no longer quarantined (nothing done).
  Future<bool> abandon(String opId) => _db.transaction(() async {
        final chain = await chainOf(opId);
        if (chain.isEmpty) return false;
        final head = chain.first;
        final label = await _abandonLabel(head);
        await (_db.delete(_db.syncQueue)
              ..where((t) => t.id.isIn(chain.map((o) => o.id))))
            .go();
        final entityId = head.entityId ?? _payloadOf(head)['saleId'] as String?;
        if (entityId != null) {
          switch (head.operation) {
            case 'CREATE_SALE':
              // Validations/cancellations still queued for this sale would
              // reach the server orphaned.
              await (_db.delete(_db.syncQueue)
                    ..where((t) =>
                        t.synced.equals(false) &
                        t.entityId.equals(entityId) &
                        t.operation.isIn(saleChainOperations)))
                  .go();
              // Its local stock movements share the sale item ids.
              await _db.customStatement(
                  'DELETE FROM stock_movements WHERE synced = 0 AND id IN '
                  '(SELECT id FROM sale_items WHERE sale_id = ?)',
                  [entityId]);
              await _db.customStatement(
                  'DELETE FROM sale_items WHERE sale_id = ?', [entityId]);
              await _db
                  .customStatement('DELETE FROM sales WHERE id = ?', [entityId]);
            case 'CREATE_DAY_CLOSURE':
              await _db.customStatement(
                  'DELETE FROM day_closures WHERE id = ? AND synced = 0',
                  [entityId]);
            case 'STOCK_ADJUST' || 'RECORD_STOCK_ENTRY':
              await _db.customStatement(
                  'DELETE FROM stock_movements WHERE id = ? AND synced = 0',
                  [entityId]);
            case 'STOCK_TRANSFER':
              await _db.customStatement(
                  'DELETE FROM stock_transfers WHERE id = ?', [entityId]);
          }
        }
        await _logger.logOperationAbandoned(label);
        return true;
      });

  Future<String> _abandonLabel(SyncQueueData head) async {
    final buf = StringBuffer(friendlySyncOperationLabel(head.operation))
      ..write(' du ${_date(head.createdAt)}');
    if (head.operation == 'CREATE_SALE') {
      final total = await _saleTotal(head);
      if (total != null) buf.write(' — ${formatQuarantineAmount(total)}');
    }
    buf.write(' (${friendlySyncRejectionReason(head.lastError)})');
    return buf.toString();
  }

  /// *Exporter*: a French, human-readable description of the chain — what it
  /// is, when, why it was refused, the store, the sale lines, the payment —
  /// so the operation can be re-entered by hand. No raw payload, id or code
  /// (v1s-16-20): the ids stay in the local queue for Renvoyer/Rattacher.
  Future<String> exportText(String opId) async {
    final chain = await chainOf(opId);
    if (chain.isEmpty) return '';
    final head = chain.first;
    final payload = _payloadOf(head);
    final buf = StringBuffer()
      ..writeln('Keevo — opération bloquée')
      ..writeln('Opération : ${friendlySyncOperationLabel(head.operation)}')
      ..writeln('Date : ${_date(_occurredAt(head))}')
      ..writeln(
          'Motif du refus : ${friendlySyncRejectionReason(head.lastError)}');

    final storeId = (payload['storeId'] ?? payload['sourceStoreId']) as String?;
    if (storeId != null) {
      buf.writeln('Boutique : ${await storeLabel(storeId)}');
    }
    final destination = payload['destinationStoreId'] as String?;
    if (destination != null) {
      buf.writeln('Boutique de destination : ${await storeLabel(destination)}');
    }

    if (head.operation == 'CREATE_SALE') {
      final lines = saleLinesOf(head);
      if (lines.isNotEmpty) {
        buf.writeln('Articles :');
        for (final l in lines) {
          final name = l.name == _unnamedLine && l.productId != null
              ? await _productLabel(l.productId!)
              : l.name;
          buf.writeln('- $name : ${l.quantity} × '
              '${formatQuarantineAmount(l.unitPrice)} = '
              '${formatQuarantineAmount(l.total)}');
        }
      }
      final discount = (payload['discountAmount'] as num?)?.toInt() ?? 0;
      if (discount > 0) {
        buf.writeln('Remise : ${formatQuarantineAmount(discount)}');
      }
      final total = await _saleTotal(head);
      if (total != null) buf.writeln('Total : ${formatQuarantineAmount(total)}');
      final mode = payload['paymentMode'] as String?;
      if (mode != null) buf.writeln('Paiement : ${paymentModeLabel(mode)}');
    } else {
      var productName = payload['productName'] as String?;
      final productId = payload['productId'] as String?;
      if (productName == null && productId != null) {
        productName = await _productLabel(productId);
      }
      final quantity = payload['quantity'] ?? payload['quantityDelta'];
      if (productName != null) buf.writeln('Produit : $productName');
      if (quantity != null) buf.writeln('Quantité : $quantity');
    }

    if (chain.length > 1) {
      buf.writeln('Opérations liées :');
      for (final op in chain.skip(1)) {
        buf.writeln('- ${friendlySyncOperationLabel(op.operation)} du '
            '${_date(_occurredAt(op))}');
      }
    }
    return buf.toString();
  }

  /// J26: one-line human summary of a non-sale operation for the blocked
  /// list — « Savon de Marseille × 5 » — or null when there is no product.
  /// Never an id.
  Future<String?> productSummary(SyncQueueData op) async {
    if (op.operation == 'CREATE_SALE') return null;
    final payload = _payloadOf(op);
    var productName = payload['productName'] as String?;
    final productId = payload['productId'] as String?;
    if (productName == null && productId != null) {
      productName = await _productLabel(productId);
    }
    if (productName == null) return null;
    final quantity = payload['quantity'] ?? payload['quantityDelta'];
    return quantity == null ? productName : '$productName × $quantity';
  }

  /// J26: « Source → Destination » for a stock transfer, else null.
  Future<String?> transferRoute(SyncQueueData op) async {
    final payload = _payloadOf(op);
    final source = payload['sourceStoreId'] as String?;
    final destination = payload['destinationStoreId'] as String?;
    if (source == null || destination == null) return null;
    return '${await storeLabel(source)} → ${await storeLabel(destination)}';
  }

  /// Local store name of [storeId], or a "boutique inconnue" label (never
  /// the id).
  Future<String> storeLabel(String storeId) async {
    final store = await (_db.select(_db.stores)
          ..where((t) => t.id.equals(storeId)))
        .getSingleOrNull();
    return store?.name ?? 'Boutique inconnue sur cet appareil';
  }

  /// Local product name of [productId], or a "produit inconnu" label (never
  /// the id).
  Future<String> _productLabel(String productId) async {
    final product = await (_db.select(_db.products)
          ..where((t) => t.id.equals(productId)))
        .getSingleOrNull();
    return product?.name ?? 'Produit inconnu sur cet appareil';
  }

  /// French label of a sale payment mode (`CASH` → « Espèces »); an unknown
  /// mode is humanized, never shown raw.
  static String paymentModeLabel(String mode) => switch (mode) {
        'CASH' => 'Espèces',
        'MOBILE_MONEY' => 'Mobile Money',
        _ => _humanize(mode),
      };

  static String _humanize(String code) {
    final s = code.replaceAll('_', ' ').trim().toLowerCase();
    return s.isEmpty ? code : s[0].toUpperCase() + s.substring(1);
  }

  static const _unnamedLine = 'Article';

  /// The sale lines of a queued `CREATE_SALE` payload.
  static List<QuarantinedSaleLine> saleLinesOf(SyncQueueData op) {
    final items = _payloadOf(op)['items'];
    if (items is! List) return const [];
    return [
      for (final item in items)
        if (item is Map)
          QuarantinedSaleLine(
            productId: item['productId'] as String?,
            name: (item['productName'] as String?) ?? _unnamedLine,
            quantity: (item['quantity'] as num?)?.toInt() ?? 0,
            unitPrice: ((item['appliedUnitPrice'] ??
                        item['catalogueUnitPrice']) as num?)
                    ?.toInt() ??
                0,
          ),
    ];
  }

  /// Products a sale line can be re-attached to: active, not archived, and
  /// known to the server (no creation still queued — the released sale is
  /// pushed before newer operations).
  Future<List<Product>> reattachableProducts() async {
    final pending = await _pendingCreations(
        const {'CREATE_PRODUCT', 'CREATE_DRAFT_PRODUCT'});
    final rows = await (_db.select(_db.products)
          ..where((t) => t.archived.equals(false) & t.status.equals('ACTIVE'))
          ..orderBy([(t) => OrderingTerm.asc(t.name)]))
        .get();
    return rows.where((p) => !pending.contains(p.id)).toList();
  }

  /// Stores a sale can be re-attached to: active and known to the server.
  Future<List<Store>> reattachableStores() async {
    final pending = await _pendingCreations(const {'CREATE_STORE'});
    final rows = await (_db.select(_db.stores)
          ..where((t) => t.isActive.equals(true))
          ..orderBy([(t) => OrderingTerm.asc(t.name)]))
        .get();
    return rows.where((s) => !pending.contains(s.id)).toList();
  }

  Future<Set<String>> _pendingCreations(Set<String> operations) async {
    final ops = await (_db.select(_db.syncQueue)
          ..where((t) => t.synced.equals(false) & t.operation.isIn(operations)))
        .get();
    return ops.map((o) => o.entityId).whereType<String>().toSet();
  }

  /// Total of a quarantined sale: the local sale row, else its payload lines.
  Future<int?> saleTotal(SyncQueueData head) => _saleTotal(head);

  Future<int?> _saleTotal(SyncQueueData head) async {
    final saleId = head.entityId ?? _payloadOf(head)['saleId'] as String?;
    if (saleId != null) {
      final sale = await (_db.select(_db.sales)
            ..where((t) => t.id.equals(saleId)))
          .getSingleOrNull();
      if (sale != null) return sale.totalAmount;
    }
    final lines = saleLinesOf(head);
    if (lines.isEmpty) return null;
    final discount =
        (_payloadOf(head)['discountAmount'] as num?)?.toInt() ?? 0;
    return lines.fold<int>(0, (sum, l) => sum + l.total) - discount;
  }

  static DateTime _occurredAt(SyncQueueData op) {
    final raw = _payloadOf(op)['occurredAt'] ?? _payloadOf(op)['closedAt'];
    final parsed = raw is String ? DateTime.tryParse(raw) : null;
    return parsed?.toLocal() ?? op.createdAt;
  }

  static String _date(DateTime d) => DateFormat('dd/MM/yyyy HH:mm').format(d);

  static Map<String, dynamic> _payloadOf(SyncQueueData op) {
    try {
      final decoded = jsonDecode(op.payload);
      return decoded is Map<String, dynamic> ? decoded : const {};
    } catch (_) {
      return const {};
    }
  }
}

/// One line of a quarantined sale.
class QuarantinedSaleLine {
  final String? productId;
  final String name;
  final int quantity;
  final int unitPrice;

  const QuarantinedSaleLine({
    required this.productId,
    required this.name,
    required this.quantity,
    required this.unitPrice,
  });

  int get total => quantity * unitPrice;
}

/// Amount with a space as thousands separator, e.g. "12 500 XAF".
String formatQuarantineAmount(int amount) {
  final negative = amount < 0;
  final str = amount.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < str.length; i++) {
    if (i > 0 && (str.length - i) % 3 == 0) buf.write(' ');
    buf.write(str[i]);
  }
  return '${negative ? '-' : ''}$buf XAF';
}

final syncQuarantineServiceProvider = Provider<SyncQuarantineService>((ref) {
  return SyncQuarantineService(
    ref.watch(appDatabaseProvider),
    ref.watch(syncEventLoggerProvider),
  );
});
