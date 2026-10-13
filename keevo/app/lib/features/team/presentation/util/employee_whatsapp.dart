import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

/// WhatsApp helpers of the team screens (v1s-14-6, FR65 invitation, FR67
/// reminder). Client-side `wa.me` links only: the owner's own WhatsApp opens
/// with a pre-filled text — no backend send.
///
/// Security: no text built here ever contains a password. The temporary
/// password is shown once in the creation sheet and communicated separately.

/// Public download link of the app, injected at build time
/// (`--dart-define=KEEVO_APP_DOWNLOAD_URL=…`). Blank → the invitation says
/// « Installez l'application Keevo » without a link.
const String kAppDownloadUrl = String.fromEnvironment('KEEVO_APP_DOWNLOAD_URL');

/// Invitation sent to a newly created employee.
String buildInviteMessage({
  required String firstName,
  required String storeName,
  required String phone,
  String downloadUrl = kAppDownloadUrl,
}) {
  final hello = firstName.trim().isEmpty ? 'Bonjour' : 'Bonjour ${firstName.trim()}';
  final store = storeName.trim().isEmpty ? '' : ' de la boutique « ${storeName.trim()} »';
  final link = downloadUrl.trim().isEmpty ? '' : ' : ${downloadUrl.trim()}';
  return '$hello, vous avez été ajouté(e) à l\'équipe$store sur Keevo.\n\n'
      '1. Installez l\'application Keevo$link\n'
      '2. Connectez-vous avec votre numéro : $phone\n'
      '3. Saisissez le mot de passe temporaire que je vous communique '
      'séparément. Vous le changerez à la première connexion.';
}

/// Friendly reminder sent to an employee without activity for 48 h+.
String buildReminderMessage({required String firstName, String? storeName}) {
  final hello = firstName.trim().isEmpty ? 'Bonjour' : 'Bonjour ${firstName.trim()}';
  final store = (storeName == null || storeName.trim().isEmpty)
      ? ''
      : ' pour la boutique « ${storeName.trim()} »';
  return '$hello, je ne vois pas d\'activité sur Keevo$store depuis plus de '
      '48 h. Pensez à enregistrer vos ventes dans l\'application. '
      'Un souci pour vous connecter ? Répondez-moi ici.';
}

/// `https://wa.me/<digits>?text=<encoded>` — the number keeps its digits only
/// (wa.me rejects `+`, spaces and dashes).
Uri whatsAppUri(String phone, String text) {
  final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
  return Uri.parse('https://wa.me/$digits?text=${Uri.encodeComponent(text)}');
}

final _dateFmt = DateFormat('dd/MM/yyyy');

/// « il y a 3 h »-style label of a past instant; `null` → « Aucune activité ».
String formatLastSeen(DateTime? at, DateTime now) {
  if (at == null) return 'Aucune activité';
  final diff = now.difference(at);
  if (diff.inMinutes < 1) return 'à l\'instant';
  if (diff.inMinutes < 60) return 'il y a ${diff.inMinutes} min';
  if (diff.inHours < 24) return 'il y a ${diff.inHours} h';
  if (diff.inDays < 7) return 'il y a ${diff.inDays} j';
  return 'le ${_dateFmt.format(at.toLocal())}';
}

/// Opens [uri] in WhatsApp (outside the app). Shows a snackbar when no app
/// can handle it. Returns whether it opened.
Future<bool> openWhatsApp(ScaffoldMessengerState messenger, Uri uri) async {
  bool opened;
  try {
    opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {
    opened = false;
  }
  if (!opened) {
    messenger.showSnackBar(const SnackBar(
      content: Text('Impossible d\'ouvrir WhatsApp sur cet appareil.'),
    ));
  }
  return opened;
}
