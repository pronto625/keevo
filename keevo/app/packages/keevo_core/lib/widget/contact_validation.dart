/// Phone / e-mail rules of client and supplier forms, aligned on the backend
/// (`Client` / `Supplier`: phone `^\+[1-9]\d{7,14}$`, e-mail with `@`).
///
/// A number typed the local way ("6 99 11 22 33") used to be sent as is and
/// rejected by the server on sync while the form said « Client ajouté » —
/// the contact was silently lost (device test).
library;

final _canonicalPhone = RegExp(r'^\+[1-9]\d{7,14}$');
final _cameroonNational = RegExp(r'^[26]\d{8}$');
final _email = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

/// Canonical `+<digits>` form of [raw], or null when it cannot be one.
/// Spaces, dashes, dots and parentheses are ignored; `00` becomes `+`; a
/// 9-digit Cameroonian number (6…/2…) gets `+237`; `237…` gets `+`.
String? normalizeContactPhone(String raw) {
  var v = raw.trim().replaceAll(RegExp(r'[\s\-.()]'), '');
  if (v.isEmpty) return null;
  if (v.startsWith('00')) v = '+${v.substring(2)}';
  if (!v.startsWith('+')) {
    if (_cameroonNational.hasMatch(v)) {
      v = '+237$v';
    } else if (v.startsWith('237')) {
      v = '+$v';
    } else {
      return null;
    }
  }
  return _canonicalPhone.hasMatch(v) ? v : null;
}

/// French error for the phone field, or null when [raw] is valid.
String? contactPhoneError(String? raw) {
  if (raw == null || raw.trim().isEmpty) return 'Le téléphone est requis';
  return normalizeContactPhone(raw) == null
      ? 'Numéro invalide. Exemple : 6 99 11 22 33'
      : null;
}

/// French error for an optional e-mail field, or null when empty or valid.
String? contactEmailError(String? raw) {
  if (raw == null || raw.trim().isEmpty) return null;
  return _email.hasMatch(raw.trim()) ? null : 'Adresse e-mail invalide';
}
