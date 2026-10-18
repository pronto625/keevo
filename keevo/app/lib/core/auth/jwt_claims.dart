import 'dart:convert';

/// Role of the Keevo platform super-admin (web admin dashboard only). The
/// mobile app never opens a session with it.
const kPlatformAdminRole = 'SUPER_ADMIN';

/// Read-only view of an access token's payload. The signature is not checked
/// here — the server does that; the app only reads its own claims.
class JwtClaims {
  final Map<String, dynamic> _data;

  const JwtClaims._(this._data);

  /// Null for anything that is not a three-part JWT with a JSON payload.
  static JwtClaims? tryParse(String? token) {
    if (token == null) return null;
    final parts = token.split('.');
    if (parts.length != 3 || !token.startsWith('eyJ')) return null;
    try {
      final decoded = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final data = jsonDecode(decoded);
      return data is Map<String, dynamic> ? JwtClaims._(data) : null;
    } catch (_) {
      return null;
    }
  }

  DateTime? get expiresAt {
    final exp = _data['exp'];
    return exp is int ? DateTime.fromMillisecondsSinceEpoch(exp * 1000) : null;
  }

  /// True when the token is expired, or will be within [margin] — renewing a
  /// little early avoids sending a request that expires in flight.
  bool isExpired({Duration margin = Duration.zero, DateTime? now}) {
    final exp = expiresAt;
    if (exp == null) return true;
    return !(now ?? DateTime.now()).add(margin).isBefore(exp);
  }

  /// User id.
  String? get subject => _data['sub'] as String?;

  /// Tenant schema the session belongs to.
  String? get tenantId => _data['tenantId'] as String?;

  String? get role => _data['role'] as String?;

  /// Platform super-admin session: reserved for the web admin dashboard,
  /// never usable in the mobile app.
  bool get isPlatformAdmin => role == kPlatformAdminRole;
  String? get storeId => _data['storeId'] as String?;
  String? get firstName => _data['firstName'] as String?;
  String? get tenantStatus => _data['tenantStatus'] as String?;
  bool get passwordChangeRequired => _data['passwordChangeRequired'] == true;

  /// Setup wizard done. Null for tokens issued before the claim existed.
  bool? get onboardingCompleted => _data['onboardingCompleted'] as bool?;
}
