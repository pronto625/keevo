import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Shows the one-time temporary password bottom sheet (Story 3.5, AC1).
///
/// Non-dismissible — user must tap "Continuer" to close.
/// The temporary password is displayed ONCE and never persisted.
Future<void> showTempPasswordBottomSheet({
  required BuildContext context,
  required String employeeName,
  required String storeName,
  required String temporaryPassword,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isDismissible: false,
    enableDrag: false,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (ctx) => _TempPasswordSheet(
      employeeName: employeeName,
      storeName: storeName,
      temporaryPassword: temporaryPassword,
    ),
  );
}

class _TempPasswordSheet extends StatefulWidget {
  final String employeeName;
  final String storeName;
  final String temporaryPassword;

  const _TempPasswordSheet({
    required this.employeeName,
    required this.storeName,
    required this.temporaryPassword,
  });

  @override
  State<_TempPasswordSheet> createState() => _TempPasswordSheetState();
}

class _TempPasswordSheetState extends State<_TempPasswordSheet> {
  bool _copied = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return PopScope(
      canPop: false,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // ── Success icon ──────────────────────────────────────
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  color: const Color(0xFFD3F9D8),
                  borderRadius: BorderRadius.circular(28),
                ),
                child: const Icon(
                  Icons.check_circle_rounded,
                  color: Color(0xFF2B8A3E),
                  size: 32,
                ),
              ),
              const SizedBox(height: 16),

              // ── Title ─────────────────────────────────────────────
              Text(
                'Employé créé avec succès',
                style: theme.textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),

              // ── Employee info ─────────────────────────────────────
              Text(
                '${widget.employeeName} — ${widget.storeName}',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: const Color(0xFF868E96)),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),

              // ── Temporary password ────────────────────────────────
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFFF8F9FA),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFFDEE2E6)),
                ),
                child: Column(
                  children: [
                    const Text(
                      'Mot de passe temporaire',
                      style: TextStyle(
                        fontSize: 12,
                        color: Color(0xFF868E96),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 8),
                    SelectableText(
                      widget.temporaryPassword,
                      style: const TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 2,
                        fontFamily: 'monospace',
                        color: Color(0xFF212529),
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
                  color: const Color(0xFFFFF3E0),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.warning_amber_rounded,
                        color: Color(0xFFE65100), size: 20),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Ce mot de passe ne sera plus affiché. '
                        'Communiquez-le à l\'employé par vos propres moyens.',
                        style: TextStyle(
                          fontSize: 12,
                          color: Color(0xFFE65100),
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // ── Continue button ───────────────────────────────────
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => Navigator.pop(context),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Text('Continuer'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
