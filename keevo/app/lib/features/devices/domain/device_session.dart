/// Story 16.11 — one device signed in to the account (« Appareils connectés »).
///
/// [sessionId] is opaque: used to close the session, never shown.
class DeviceSession {
  final String sessionId;
  final String label;
  final DateTime? lastUsedAt;
  final bool current;

  const DeviceSession({
    required this.sessionId,
    required this.label,
    this.lastUsedAt,
    this.current = false,
  });

  factory DeviceSession.fromJson(Map<String, dynamic> json) {
    final last = json['lastUsedAt'] as String?;
    return DeviceSession(
      sessionId: json['sessionId'] as String,
      label: (json['deviceLabel'] as String?) ?? 'Appareil inconnu',
      lastUsedAt: last == null ? null : DateTime.tryParse(last),
      current: json['current'] == true,
    );
  }
}
