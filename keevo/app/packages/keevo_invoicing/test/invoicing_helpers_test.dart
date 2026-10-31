import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_invoicing/data/remote_invoice_datasource.dart';
import 'package:keevo_invoicing/domain/model/invoice.dart';
import 'package:keevo_invoicing/presentation/invoicing_format.dart';
import 'package:keevo_invoicing/presentation/provider/invoicing_providers.dart';

/// Serves a credit note PDF; records the asked id and fallback name.
class _PdfDataSource implements RemoteInvoiceDataSource {
  final List<(String, String)> asked = [];

  @override
  Future<InvoicePdf> creditNotePdf(
    String id, {
    required String fallbackName,
  }) async {
    asked.add((id, fallbackName));
    return (bytes: const [37, 80, 68, 70], fileName: 'AV-2026-000001.pdf');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  group('quantities', () {
    test('formatQuantity: at most 3 decimals, French comma', () {
      expect(formatQuantity(2), '2');
      expect(formatQuantity(1.5), '1,5');
      expect(formatQuantity(0.125), '0,125');
      expect(formatQuantity(10.0), '10');
      expect(formatQuantity(0), '0');
    });

    test(
      'parseDecimal: comma or dot, spaces ignored, null when not a number',
      () {
        expect(parseDecimal('1,5'), 1.5);
        expect(parseDecimal(' 2.25 '), 2.25);
        expect(parseDecimal('1 000'), 1000);
        expect(parseDecimal('1 000'), 1000);
        expect(parseDecimal(''), isNull);
        expect(parseDecimal('abc'), isNull);
      },
    );

    test('remaining quantity in thousandths: 0.3 − 0.1 = 0.2 exactly', () {
      expect(toThousandths(0.3) - toThousandths(0.1), toThousandths(0.2));
      expect(remainingQuantity(0.3, 0.1), 0.2);
      expect(
        toThousandths(0.2) > toThousandths(remainingQuantity(0.3, 0.1)),
        isFalse,
      );
      expect(remainingQuantity(3, 0.1 + 0.2), 2.7);
      expect(remainingQuantity(1, 2), 0);
    });

    test('doualaToday: UTC+1 calendar day, whatever the device zone', () {
      expect(
        doualaToday(now: DateTime.utc(2026, 10, 31, 23, 30)),
        DateTime(2026, 11, 1),
      );
      expect(
        doualaToday(now: DateTime.utc(2026, 10, 31, 22, 59)),
        DateTime(2026, 10, 31),
      );
    });
  });

  test(
    'creditNotePdfOpenerProvider: downloads, clears old PDFs, shares',
    () async {
      final temp = await Directory.systemTemp.createTemp('keevo-cn');
      addTearDown(() => temp.delete(recursive: true));
      final stale = File('${temp.path}/invoices/1/old.pdf');
      await stale.create(recursive: true);
      final shared = <File>[];
      final ds = _PdfDataSource();
      final c = ProviderContainer(
        retry: noProviderRetry,
        overrides: [
          invoiceDataSourceProvider.overrideWithValue(ds),
          tempDirProvider.overrideWith((ref) async => temp),
          fileSharerProvider.overrideWithValue((file) async {
            shared.add(file);
          }),
        ],
      );
      addTearDown(c.dispose);

      await c.read(creditNotePdfOpenerProvider)(
        const CreditNoteSummary(id: 'cn-1', number: 'AV 2026/1', totalTtc: 100),
      );

      expect(ds.asked.single.$1, 'cn-1');
      expect(ds.asked.single.$2, 'avoir-AV_2026_1.pdf');
      expect(await stale.exists(), isFalse);
      expect(shared.single.uri.pathSegments.last, 'AV-2026-000001.pdf');
      expect(await shared.single.readAsBytes(), [37, 80, 68, 70]);
    },
  );
}
