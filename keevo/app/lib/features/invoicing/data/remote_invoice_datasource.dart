import 'dart:convert';

import 'package:dio/dio.dart';

import '../domain/model/invoice.dart';

/// An invoice PDF as downloaded.
typedef InvoicePdf = ({List<int> bytes, String fileName});

/// Story 15.3 — HTTP adapter to `/api/v1/invoices` (read side used by the
/// checkout « Facturer » action and the invoice screen).
class RemoteInvoiceDataSource {
  static const _base = '/api/v1/invoices';

  final Dio _dio;

  RemoteInvoiceDataSource({required Dio dio}) : _dio = dio;

  /// `GET /api/v1/invoices?saleId=` — the sale's automatic invoice: the
  /// active one when a correction replaced an earlier one (else the most
  /// recently created), null while the
  /// POS → Invoicing bridge has not created it (sale not synced yet).
  Future<Invoice?> findBySale(String saleId) async {
    final response = await _dio.get<Map<String, dynamic>>(_base,
        queryParameters: {'saleId': saleId, 'page': 0, 'size': 20});
    final data = response.data?['data'];
    if (data is! List) {
      throw const FormatException('Réponse des factures illisible');
    }
    final invoices = [
      for (final item in data)
        if (item is Map<String, dynamic>) Invoice.fromJson(item),
    ];
    if (invoices.isEmpty) return null;
    for (final i in invoices) {
      if (i.supersededAt == null) return i;
    }
    final epoch = DateTime.fromMillisecondsSinceEpoch(0);
    return invoices.reduce(
        (a, b) => (b.createdAt ?? epoch).isAfter(a.createdAt ?? epoch) ? b : a);
  }

  /// `GET /api/v1/invoices/{id}`.
  Future<Invoice> get(String id) async {
    final response = await _dio
        .get<Map<String, dynamic>>('$_base/${Uri.encodeComponent(id)}');
    final data = response.data?['data'];
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Réponse de la facture illisible');
    }
    return Invoice.fromJson(data);
  }

  /// `GET /api/v1/invoices/{id}/pdf`. An error body (bytes) is decoded so the
  /// French message of the API reaches `appErrorMessage`.
  Future<InvoicePdf> pdf(String id, {required String fallbackName}) async {
    try {
      final response = await _dio.get<List<int>>(
          '$_base/${Uri.encodeComponent(id)}/pdf',
          options: Options(responseType: ResponseType.bytes));
      final name = fileNameOf(response.headers.value('content-disposition')) ??
          fallbackName;
      final bytes = response.data;
      if (bytes == null || bytes.isEmpty) {
        throw const FormatException('Le PDF de la facture est vide');
      }
      return (bytes: bytes, fileName: name);
    } on DioException catch (e) {
      final body = e.response?.data;
      final decoded = body is List<int> ? _decodeJson(body) : null;
      if (decoded == null) rethrow;
      throw e.copyWith(
          response: Response(
        requestOptions: e.response!.requestOptions,
        statusCode: e.response!.statusCode,
        headers: e.response!.headers,
        data: decoded,
      ));
    }
  }

  static Object? _decodeJson(List<int> bytes) {
    if (bytes.isEmpty) return null;
    try {
      return jsonDecode(utf8.decode(bytes, allowMalformed: true));
    } on FormatException {
      return null;
    }
  }

  /// File name of a `Content-Disposition` header, null when absent. Only
  /// characters safe in a file name are kept.
  static String? fileNameOf(String? header) {
    if (header == null) return null;
    final match = RegExp(r'filename="?([^";]+)"?').firstMatch(header);
    final raw = match?.group(1)?.trim();
    if (raw == null || raw.isEmpty) return null;
    final safe = raw.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    // '.' / '..' would name the folder itself, not a file.
    if (safe.isEmpty || RegExp(r'^\.+$').hasMatch(safe)) return null;
    return safe;
  }
}
