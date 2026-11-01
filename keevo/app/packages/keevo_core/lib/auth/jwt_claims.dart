import 'dart:convert';

/// Role of the Keevo platform super-admin (web admin dashboard only). The
/// mobile app never opens a session with it.
const kPlatformAdminRole = 'SUPER_ADMIN';

/// Account (schema) of the platform super-admin's sessions.
const kPlatformAdminTenant = 'public';

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

  /// Story 16.11 — device session id (« Appareils connectés »), null for tokens
  /// issued before device sessions.
  String? get sessionId => _data['sid'] as String?;

  /// Platform super-admin session: reserved for the web admin dashboard,
  /// never usable in the mobile app. Story 16.11 — told by the account the
  /// token belongs to (the super-admin's system account, schema `public`),
  /// never by a role claim: the token proves identity only. Role, store,
  /// first name, account status and the password flag come from
  /// `GET /api/v1/auth/profile`.
  bool get isPlatformAdmin => tenantId == kPlatformAdminTenant;
}
