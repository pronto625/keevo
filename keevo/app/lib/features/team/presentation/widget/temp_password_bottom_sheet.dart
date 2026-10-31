import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import '../util/employee_whatsapp.dart';

/// Shows the one-time temporary password bottom sheet (Story 3.5, AC1).
///
/// Non-dismissible — user must tap "Continuer" to close.
/// The temporary password is displayed ONCE and never persisted.
///
/// v1s-14-6 (FR65): when [invitePhone] is known, an « Inviter via WhatsApp »
/// button opens a pre-filled `wa.me` invitation (store, phone, app link). The
/// invitation NEVER contains the password — the owner communicates it
/// separately and the employee changes it at first login.
Future<void> showTempPasswordBottomSheet({
  required BuildContext context,
  required String employeeName,
  required String storeName,
  required String temporaryPassword,
  String? invitePhone,
  String? firstName,
  String? inviteStoreName,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isDismissible: false,
    enableDrag: false,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    // Taller content since the invite button: scroll on small screens.
    isScrollControlled: true,
    builder: (ctx) => _TempPasswordSheet(
      employeeName: employeeName,
      storeName: storeName,
      temporaryPassword: temporaryPassword,
      invitePhone: invitePhone,
      firstName: firstName,
      inviteStoreName: inviteStoreName,
    ),
  );
}

class _TempPasswordSheet extends StatefulWidget {
  final String employeeName;
  final String storeName;
  final String temporaryPassword;
  final String? invitePhone;
  final String? firstName;

  /// Store name for the WhatsApp text ('' = unknown); defaults to [storeName].
  final String? inviteStoreName;

  const _TempPasswordSheet({
    required this.employeeName,
    required this.storeName,
    required this.temporaryPassword,
    this.invitePhone,
    this.firstName,
    this.inviteStoreName,
  });

  @override
  State<_TempPasswordSheet> createState() => _TempPasswordSheetState();
}

class _TempPasswordSheetState extends State<_TempPasswordSheet> {
  bool _copied = false;

  bool get _canInvite => (widget.invitePhone ?? '').trim().isNotEmpty;

  Future<void> _invite() async {
    final phone = widget.invitePhone!.trim();
    final text = buildInviteMessage(
      firstName: widget.firstName ?? '',
      storeName: widget.inviteStoreName ?? widget.storeName,
      phone: phone,
    );
    await openWhatsApp(ScaffoldMessenger.of(context), whatsAppUri(phone, text));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    // Teintes foncées en clair (lisibles sur fond pâle), claires en sombre.
    final successColor = cs.successStrong;
    final warningColor = cs.warningStrong;

    return PopScope(
      canPop: false,
      child: SafeArea(
        child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // ── Success icon ──────────────────────────────────────
              SoftIconBadge(
                icon: Icons.check_circle_outline_rounded,
                color: successColor,
                size: 56,
              ),
              const SizedBox(height: 16),

              // ── Title ─────────────────────────────────────────────
              Text(
                'Employé créé avec succès',
                style: theme.textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),

              // ── Employee info ─────────────────────────────────────
              Text(
                '${widget.employeeName} — ${widget.storeName}',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: cs.onSurfaceVariant),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),

              // ── Temporary password ────────────────────────────────
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: cs.surfaceContainerLowest,
                  borderRadius: BorderRadius.circular(AppTheme.radiusM),
                  border: Border.all(color: cs.outlineVariant),
                ),
                child: Column(
                  children: [
                    Text(
                      'Mot de passe temporaire',
                      style: TextStyle(
                        fontSize: 12,
                        color: cs.onSurfaceVariant,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 8),
                    SelectableText(
                      widget.temporaryPassword,
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 2,
                        fontFamily: 'monospace',
                        color: cs.onSurface,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),

              // ── Copy button ───────────────────────────────────────
              OutlinedButton.icon(
                onPressed: () async {
                  await Clipboard.setData(
                    ClipboardData(text: widget.temporaryPassword),
                  );
                  setState(() => _copied = true);
                },
                icon: Icon(_copied ? Icons.check : Icons.copy_rounded,
                    size: 18),
                label: Text(
                    _copied ? 'Copié !' : 'Copier le mot de passe'),
                style: OutlinedButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
              const SizedBox(height: 16),

              // ── Warning ───────────────────────────────────────────
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: warningColor.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(AppTheme.radiusM),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.warning_amber_rounded,
                        color: warningColor, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Ce mot de passe ne sera plus affiché. '
                        'Il n\'est jamais inclus dans l\'invitation WhatsApp : '
                        'communiquez-le à l\'employé séparément. Il le changera '
                        'à sa première connexion.',
                        style: TextStyle(
                          fontSize: 12,
                          color: warningColor,
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // ── WhatsApp invitation (v1s-14-6, FR65) ──────────────
              if (_canInvite) ...[
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: _invite,
                    icon: const Icon(Icons.chat_outlined, size: 18),
                    label: const Text('Inviter via WhatsApp'),
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(48),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(AppTheme.radiusL),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
              ],

              // ── Continue button ───────────────────────────────────
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => Navigator.pop(context),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(56),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusL),
                    ),
                  ),
                  child: const Text('Continuer'),
                ),
              ),
            ],
          ),
        ),
        ),
      ),
    );
  }
}
