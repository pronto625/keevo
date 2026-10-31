import 'dart:convert';

import 'package:dio/dio.dart';

import '../domain/model/invoice.dart';
import '../domain/model/invoicing_models.dart';

/// An invoice PDF as downloaded.
typedef InvoicePdf = ({List<int> bytes, String fileName});

/// Story 15.3 — HTTP adapter to `/api/v1/invoices` (read side used by the
/// checkout « Facturer » action and the invoice screen). Story 15.5: the
/// whole invoicing API used by the module screens (online only — nothing is
/// queued offline, Story 15.5b).
class RemoteInvoiceDataSource {
  static const _base = '/api/v1/invoices';
  static const _creditNotes = '/api/v1/credit-notes';
  static const _settings = '/api/v1/invoicing/settings';

  final Dio _dio;

  RemoteInvoiceDataSource({required Dio dio}) : _dio = dio;

  /// `GET /api/v1/invoices?saleId=` — the sale's automatic invoice: the
  /// active one when a correction replaced an earlier one (else the most
  /// recently created), null while the
  /// POS → Invoicing bridge has not created it (sale not synced yet).
  Future<Invoice?> findBySale(String saleId) async {
    final response = await _dio.get<Map<String, dynamic>>(
      _base,
      queryParameters: {'saleId': saleId, 'page': 0, 'size': 20},
    );
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
      (a, b) => (b.createdAt ?? epoch).isAfter(a.createdAt ?? epoch) ? b : a,
    );
  }

  /// `GET /api/v1/invoices/{id}`.
  Future<Invoice> get(String id) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$_base/${Uri.encodeComponent(id)}',
    );
    final data = response.data?['data'];
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Réponse de la facture illisible');
    }
    return Invoice.fromJson(data);
  }

  /// `GET /api/v1/invoices?status=&search=&from=&to=&page=&size=` — newest
  /// first (Story 15.5). [from] / [to]: issue date range, calendar days.
  Future<InvoicePage> list({
    String? status,
    String? search,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int size = 20,
  }) async {
    final query = <String, Object>{'page': page, 'size': size};
    if (status != null) query['status'] = status;
    final text = search?.trim();
    if (text != null && text.isNotEmpty) query['search'] = text;
    if (from != null) query['from'] = isoDay(from);
    if (to != null) query['to'] = isoDay(to);
    final response = await _dio.get<Map<String, dynamic>>(
      _base,
      queryParameters: query,
    );
    final data = response.data?['data'];
    if (data is! List) {
      throw const FormatException('Réponse des factures illisible');
    }
    final items = [
      for (final item in data)
        if (item is Map<String, dynamic>) Invoice.fromJson(item),
    ];
    final meta = response.data?['meta'];
    final total = meta is Map && meta['totalElements'] is num
        ? (meta['totalElements'] as num).toInt()
        : page * size + items.length;
    return InvoicePage(items: items, totalElements: total);
  }

  /// `POST /api/v1/invoices` — a new draft.
  Future<Invoice> create(InvoiceDraftInput input) async => _invoice(
    await _dio.post<Map<String, dynamic>>(_base, data: input.toJson()),
  );

  /// `PUT /api/v1/invoices/{id}` — replaces the content of a draft.
  Future<Invoice> update(String id, InvoiceDraftInput input) async => _invoice(
    await _dio.put<Map<String, dynamic>>(_path(id), data: input.toJson()),
  );

  /// `DELETE /api/v1/invoices/{id}` — a draft only.
  Future<void> delete(String id) async {
    await _dio.delete<void>(_path(id));
  }

  /// `POST /api/v1/invoices/{id}/issue` — number, issue date, seller.
  Future<Invoice> issue(String id) async =>
      _invoice(await _dio.post<Map<String, dynamic>>('${_path(id)}/issue'));

  /// `POST /api/v1/invoices/{id}/pay` — [paidOn]: the day the client paid
  /// (today when null), [paymentMethod]: « Espèces »…
  Future<Invoice> pay(
    String id, {
    String? paymentMethod,
    DateTime? paidOn,
  }) async {
    final method = paymentMethod?.trim();
    return _invoice(
      await _dio.post<Map<String, dynamic>>(
        '${_path(id)}/pay',
        data: {
          if (method != null && method.isNotEmpty) 'paymentMethod': method,
          if (paidOn != null) 'paidAt': isoDay(paidOn),
        },
      ),
    );
  }

  /// `POST /api/v1/invoices/{id}/cancel` — reason required (owner).
  Future<Invoice> cancel(String id, String reason) async => _invoice(
    await _dio.post<Map<String, dynamic>>(
      '${_path(id)}/cancel',
      data: {'reason': reason.trim()},
    ),
  );

  /// `POST /api/v1/invoices/{id}/send` — WhatsApp message with a link to the
  /// PDF; [phone] null = the client's phone.
  Future<Invoice> send(String id, {String? phone}) async {
    final number = phone?.trim();
    return _invoice(
      await _dio.post<Map<String, dynamic>>(
        '${_path(id)}/send',
        data: {if (number != null && number.isNotEmpty) 'phone': number},
      ),
    );
  }

  /// `GET /api/v1/invoices/clients?search=` — CRM clients for the picker.
  Future<ClientSuggestions> clients(String search) async {
    final text = search.trim();
    final response = await _dio.get<Map<String, dynamic>>(
      '$_base/clients',
      queryParameters: {if (text.isNotEmpty) 'search': text, 'limit': 20},
    );
    final data = response.data?['data'];
    if (data is! Map) {
      throw const FormatException('Réponse des clients illisible');
    }
    return ClientSuggestions.fromJson(data);
  }

  /// `GET /api/v1/invoicing/settings`.
  Future<InvoicingSettings> settings() async =>
      _settingsOf(await _dio.get<Map<String, dynamic>>(_settings));

  /// `PUT /api/v1/invoicing/settings` (owner).
  Future<InvoicingSettings> saveSettings(InvoicingSettings settings) async =>
      _settingsOf(
        await _dio.put<Map<String, dynamic>>(
          _settings,
          data: settings.toJson(),
        ),
      );

  /// `POST /api/v1/invoices/{id}/credit-notes` — [lines] null = everything
  /// still to credit (owner).
  Future<CreditNote> createCreditNote(
    String invoiceId, {
    required String reason,
    List<CreditNoteSelection>? lines,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '${_path(invoiceId)}/credit-notes',
      data: {
        'reason': reason.trim(),
        if (lines != null)
          'lines': [
            for (final l in lines)
              {'position': l.position, 'quantity': l.quantity},
          ],
      },
    );
    return _creditNote(response);
  }

  /// `GET /api/v1/credit-notes/{id}`.
  Future<CreditNote> creditNote(String id) async => _creditNote(
    await _dio.get<Map<String, dynamic>>(
      '$_creditNotes/${Uri.encodeComponent(id)}',
    ),
  );

  /// `GET /api/v1/credit-notes/{id}/pdf`.
  Future<InvoicePdf> creditNotePdf(String id, {required String fallbackName}) =>
      _pdf(
        '$_creditNotes/${Uri.encodeComponent(id)}/pdf',
        fallbackName: fallbackName,
        emptyMessage: 'Le PDF de l\'avoir est vide',
      );

  static String _path(String id) => '$_base/${Uri.encodeComponent(id)}';

  /// `yyyy-MM-dd` of [day] (its calendar day, no time zone shift).
  static String isoDay(DateTime day) =>
      '${day.year.toString().padLeft(4, '0')}-'
      '${day.month.toString().padLeft(2, '0')}-'
      '${day.day.toString().padLeft(2, '0')}';

  static Invoice _invoice(Response<Map<String, dynamic>> response) {
    final data = response.data?['data'];
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Réponse de la facture illisible');
    }
    return Invoice.fromJson(data);
  }

  static InvoicingSettings _settingsOf(Response<Map<String, dynamic>> r) {
    final data = r.data?['data'];
    if (data is! Map) {
      throw const FormatException('Réponse des paramètres illisible');
    }
    return InvoicingSettings.fromJson(data);
  }

  static CreditNote _creditNote(Response<Map<String, dynamic>> response) {
    final data = response.data?['data'];
    if (data is! Map) {
      throw const FormatException('Réponse de l\'avoir illisible');
    }
    return CreditNote.fromJson(data);
  }

  /// `GET /api/v1/invoices/{id}/pdf`. An error body (bytes) is decoded so the
  /// French message of the API reaches `appErrorMessage`.
  Future<InvoicePdf> pdf(String id, {required String fallbackName}) => _pdf(
    '${_path(id)}/pdf',
    fallbackName: fallbackName,
    emptyMessage: 'Le PDF de la facture est vide',
  );

  Future<InvoicePdf> _pdf(
    String path, {
    required String fallbackName,
    required String emptyMessage,
  }) async {
    try {
      final response = await _dio.get<List<int>>(
        path,
        options: Options(responseType: ResponseType.bytes),
      );
      final name =
          fileNameOf(response.headers.value('content-disposition')) ??
          fallbackName;
      final bytes = response.data;
      if (bytes == null || bytes.isEmpty) {
        throw FormatException(emptyMessage);
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
        ),
      );
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
