/// Story 15.6 — Keevo support on WhatsApp (activation requests: V1 has no
/// self-service activation, a module is activated by Keevo after a Mobile
/// Money payment).
///
/// The number is injected at build time with
/// `--dart-define=SUPPORT_WHATSAPP=<international number>` (release workflow
/// `.github/workflows/release-flutter.yml`, repository variable
/// `SUPPORT_WHATSAPP`). Left empty, the link opens WhatsApp with the message
/// ready and lets the owner pick the Keevo contact himself.
const String kSupportWhatsApp = String.fromEnvironment('SUPPORT_WHATSAPP');

/// `https://wa.me/<digits>?text=<encoded>` to Keevo support ([number] keeps
/// its digits only: wa.me rejects `+`, spaces and dashes, and a leading `00`
/// international prefix is dropped). A blank number gives
/// `https://wa.me/?text=…` (WhatsApp asks for the recipient).
Uri supportWhatsAppUri(String text, {String number = kSupportWhatsApp}) {
  var digits = number.replaceAll(RegExp(r'[^0-9]'), '');
  if (number.trim().startsWith('00')) digits = digits.substring(2);
  return Uri.parse('https://wa.me/$digits?text=${Uri.encodeComponent(text)}');
}
