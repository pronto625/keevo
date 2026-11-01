import 'package:dio/dio.dart';

/// The body of a refused call: a [DioException] response body, or the body
/// map itself.
Map<dynamic, dynamic>? _body(Object? error) {
  final Object? data = error is DioException ? error.response?.data : error;
  return data is Map ? data : null;
}

/// 16.8 — whether [error] is the server's `PLAN_LIMIT_EXCEEDED` refusal
/// (domainCode of the response body).
bool isPlanLimitError(Object? error) =>
    _body(error)?['domainCode'] == 'PLAN_LIMIT_EXCEEDED';

Map<dynamic, dynamic>? _details(Object? error) {
  if (!isPlanLimitError(error)) return null;
  final details = _body(error)!['details'];
  return details is Map ? details : null;
}

/// 16.8 — the limit the server reports in a refused call: `details.limit` of
/// a `PLAN_LIMIT_EXCEEDED` error. Plan limits are configured server-side: the
/// app never hard-codes them. `null` when the error carries no number.
int? planLimitFromError(Object? error) {
  final raw = _details(error)?['limit'];
  final limit = raw is num ? raw.toInt() : int.tryParse('$raw');
  return limit != null && limit > 0 ? limit : null;
}

/// 16.8 — the tenant's plan type the server reports in a refused call
/// (`details.plan`: FREE | PREMIUM_TRIAL | PREMIUM), `null` when absent.
String? planTypeFromError(Object? error) {
  final plan = _details(error)?['plan'];
  return plan is String && plan.isNotEmpty ? plan : null;
}
