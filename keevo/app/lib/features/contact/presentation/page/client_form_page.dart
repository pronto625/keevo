import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/model/client_model.dart';
import '../provider/contact_provider.dart';

/// ClientFormPage — création et édition d'un client (Story 2.5).
class ClientFormPage extends ConsumerStatefulWidget {
  final ClientModel? existing;
  const ClientFormPage({super.key, this.existing});

  @override
  ConsumerState<ClientFormPage> createState() => _ClientFormPageState();
}

class _ClientFormPageState extends ConsumerState<ClientFormPage>
    with SingleTickerProviderStateMixin {

  late final TextEditingController _name;
  late final TextEditingController _phone;
  late final TextEditingController _email;
  late final TextEditingController _notes;
  late final AnimationController _animCtrl;
  late final Animation<double> _fadeAnim;

  bool _saving = false;

  bool get _isEdit => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final c = widget.existing;
    _name  = TextEditingController(text: c?.name ?? '');
    _phone = TextEditingController(text: c?.phone ?? '');
    _email = TextEditingController(text: c?.email ?? '');
    _notes = TextEditingController(text: c?.notes ?? '');

    _animCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _fadeAnim = CurvedAnimation(parent: _animCtrl, curve: Curves.easeOut);
    _animCtrl.forward();
  }

  @override
  void dispose() {
    _name.dispose(); _phone.dispose(); _email.dispose(); _notes.dispose();
    _animCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty || _phone.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: const Row(children: [
          Icon(Icons.warning_amber_rounded, color: Colors.white, size: 18),
          SizedBox(width: 8),
          Text('Nom et téléphone sont requis'),
        ]),
        backgroundColor: Theme.of(context).colorScheme.error,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        margin: const EdgeInsets.all(16),
      ));
      return;
    }
    setState(() => _saving = true);
    try {
      final notifier = ref.read(clientListNotifierProvider.notifier);
      if (_isEdit) {
        await notifier.patchClient(
          id:    widget.existing!.id,
          name:  _name.text.trim(),
          phone: _phone.text.trim(),
          email: _email.text.trim().isEmpty ? null : _email.text.trim(),
          notes: _notes.text.trim().isEmpty ? null : _notes.text.trim(),
        );
      } else {
        await notifier.create(
          name:  _name.text.trim(),
          phone: _phone.text.trim(),
          email: _email.text.trim().isEmpty ? null : _email.text.trim(),
          notes: _notes.text.trim().isEmpty ? null : _notes.text.trim(),
        );
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Row(children: [
            const Icon(Icons.check_circle_rounded, color: Colors.white, size: 18),
            const SizedBox(width: 8),
            Text(_isEdit ? 'Client mis à jour' : 'Client ajouté'),
          ]),
          backgroundColor: AppTheme.primary,
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          margin: const EdgeInsets.all(16),
        ));
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Row(children: [
            const Icon(Icons.error_rounded, color: Colors.white, size: 18),
            const SizedBox(width: 8),
            Expanded(child: Text(appErrorMessage(e))),
          ]),
          backgroundColor: Theme.of(context).colorScheme.error,
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          margin: const EdgeInsets.all(16),
        ));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: FadeTransition(
        opacity: _fadeAnim,
        child: CustomScrollView(
          slivers: [
            // En-tête épuré — fond neutre, sans dégradé ni cercle décoratif.
            SliverAppBar(
              expandedHeight: 140,
              pinned: true,
              elevation: 0,
              scrolledUnderElevation: 0,
              backgroundColor: theme.colorScheme.surface,
              iconTheme: IconThemeData(color: theme.colorScheme.onSurface),
              flexibleSpace: FlexibleSpaceBar(
                background: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(72, 0, 20, 20),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _isEdit ? 'Modifier le client' : 'Nouveau client',
                          style: theme.textTheme.headlineSmall?.copyWith(
                            color: theme.colorScheme.onSurface,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.3,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _isEdit
                            ? 'Mettez à jour les informations'
                            : 'Renseignez les coordonnées',
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

            // ── Form ────────────────────────────────────────────────
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Identité
                    _SectionHeader(icon: Icons.person_outline_rounded, label: 'Identité', color: primary),
                    const SizedBox(height: 12),
                    _ModernField(
                      controller: _name,
                      label: 'Nom complet *',
                      icon: Icons.badge_outlined,
                      textCapitalization: TextCapitalization.words,
                    ),
                    const SizedBox(height: 14),

                    // Contact
                    _SectionHeader(icon: Icons.contact_phone_outlined, label: 'Contact', color: primary),
                    const SizedBox(height: 12),
                    _ModernField(
                      controller: _phone,
                      label: 'Téléphone *',
                      icon: Icons.phone_outlined,
                      keyboardType: TextInputType.phone,
                    ),
                    const SizedBox(height: 12),
                    _ModernField(
                      controller: _email,
                      label: 'Email (optionnel)',
                      icon: Icons.email_outlined,
                      keyboardType: TextInputType.emailAddress,
                    ),
                    const SizedBox(height: 12),
                    const SizedBox(height: 14),

                    // Notes
                    _SectionHeader(icon: Icons.notes_rounded, label: 'Notes', color: primary),
                    const SizedBox(height: 12),
                    _ModernField(
                      controller: _notes,
                      label: 'Notes internes (optionnel)',
                      icon: Icons.sticky_note_2_outlined,
                      maxLines: 4,
                    ),
                    const SizedBox(height: 28),

                    // Save button
                    _SaveButton(saving: _saving, isEdit: _isEdit, onPressed: _save),
                    const SizedBox(height: 32),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Section header ─────────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  const _SectionHeader({required this.icon, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(children: [
      SoftIconBadge(icon: icon, size: 28),
      const SizedBox(width: 10),
      Text(
        label,
        style: theme.textTheme.titleSmall?.copyWith(
          fontWeight: FontWeight.w700,
          color: color,
          letterSpacing: 0.5,
        ),
      ),
      const SizedBox(width: 12),
      Expanded(
        child: Divider(color: theme.colorScheme.outlineVariant),
      ),
    ]);
  }
}

// ── Modern text field ──────────────────────────────────────────────────────────

class _ModernField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final IconData icon;
  final TextInputType? keyboardType;
  final TextCapitalization textCapitalization;
  final int maxLines;

  const _ModernField({
    required this.controller,
    required this.label,
    required this.icon,
    this.keyboardType,
    this.textCapitalization = TextCapitalization.none,
    this.maxLines = 1,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // Hérite du style de champ global (AppTheme) : plus d'ombre ni de
    // bordures redéfinies localement.
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      textCapitalization: textCapitalization,
      maxLines: maxLines,
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(color: theme.colorScheme.onSurfaceVariant),
        prefixIcon: Icon(icon, size: 20),
      ),
    );
  }
}

// ── Save button ────────────────────────────────────────────────────────────────

class _SaveButton extends StatelessWidget {
  final bool saving;
  final bool isEdit;
  final VoidCallback onPressed;
  const _SaveButton({required this.saving, required this.isEdit, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;

    // Couleur pleine + ombre douce plutôt qu'un dégradé.
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: primary,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: primary.withValues(alpha: 0.25),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: saving ? null : onPressed,
          child: Center(
            child: saving
                  ? const SizedBox(
                      width: 24, height: 24,
                      child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2.5),
                    )
                  : Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.save_outlined, color: Colors.white),
                        const SizedBox(width: 10),
                        Text(
                          isEdit ? 'Enregistrer les modifications' : 'Ajouter le client',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
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
