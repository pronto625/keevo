import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../auth/domain/model/account_profile.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../provider/account_deletion_provider.dart';
import '../util/deletion_date_format.dart';
import '../widget/delete_account_warning_dialog.dart';

/// DeleteAccountPage — step 2 of the FR91 flow (Story 14.5, AC7).
///
/// Route: /settings/account/delete (OWNER only — an EMPLOYEE landing here
/// sees the AC9 explanation and no action).
///
/// The owner must type their own phone number (compared to
/// `profile.phoneNumber`); "Confirmer" stays disabled until it matches, then
/// `POST /api/v1/account/delete` is called and a SnackBar confirms.
/// Stateful on purpose: the controller is created in `initState()` and
/// released in `dispose()` (not the leaky dialog-builder pattern).
class DeleteAccountPage extends ConsumerStatefulWidget {
  const DeleteAccountPage({super.key});

  @override
  ConsumerState<DeleteAccountPage> createState() => _DeleteAccountPageState();
}

class _DeleteAccountPageState extends ConsumerState<DeleteAccountPage> {
  late final TextEditingController _phoneController;
  bool _submitting = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _phoneController = TextEditingController()..addListener(_onTyped);
  }

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  void _onTyped() => setState(() => _error = null);

  static String _normalize(String s) => s.replaceAll(RegExp(r'\s+'), '');

  bool _matches(AccountProfile profile) =>
      _normalize(_phoneController.text) == _normalize(profile.phoneNumber);

  Future<void> _submit() async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    final messenger = ScaffoldMessenger.of(context);
    try {
      final status =
          await ref.read(accountDeletionRepositoryProvider).requestDeletion();
      ref.invalidate(deletionPendingStatusProvider);
      if (!mounted) return;
      final date = status.scheduledAt;
      messenger.showSnackBar(SnackBar(
        content: Text(date == null
            ? 'Votre demande de suppression a été enregistrée.'
            : 'Votre demande de suppression a été enregistrée. Vos données '
                'seront supprimées le ${formatDeletionDate(date)}.'),
        duration: const Duration(seconds: 6),
      ));
      context.pop();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final profileAsync = ref.watch(accountProfileProvider);

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      appBar: AppBar(
        title: const Text('Supprimer mon compte'),
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: cs.onSurface,
        ),
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          onPressed: () => context.pop(),
        ),
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
      ),
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: AppErrorWidget(
            error: e,
            onRetry: () => ref.invalidate(accountProfileProvider),
          ),
        ),
        data: (profile) => profile.role == 'OWNER'
            ? _buildOwnerForm(context, profile)
            : _buildEmployeeNotice(context, profile),
      ),
    );
  }

  Widget _buildOwnerForm(BuildContext context, AccountProfile profile) {
    final cs = Theme.of(context).colorScheme;
    final canConfirm = !_submitting && _matches(profile);

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: SoftIconBadge(
              icon: Icons.delete_forever_outlined,
              color: cs.error,
              size: 56,
            ),
          ),
          const SizedBox(height: 20),
          Text(
            'Dernière étape',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Pour confirmer la suppression définitive de votre compte et de '
            'toutes vos données, saisissez votre numéro de téléphone :',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 14, color: cs.muted, height: 1.4),
          ),
          const SizedBox(height: 6),
          Text(
            profile.phoneNumber,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 24),
          TextField(
            controller: _phoneController,
            keyboardType: TextInputType.phone,
            autofocus: true,
            enabled: !_submitting,
            decoration: const InputDecoration(
              labelText: 'Votre numéro de téléphone',
              prefixIcon: Icon(Icons.phone_outlined),
            ),
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: cs.errorContainer_,
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final item in deletedDataItems)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(
                      '• $item',
                      style: TextStyle(fontSize: 13, color: cs.onSurface),
                    ),
                  ),
                const SizedBox(height: 4),
                Text(
                  'Suppression effective 30 jours après votre demande. '
                  'Annulable jusque-là depuis Paramètres > Mon Compte.',
                  style: TextStyle(fontSize: 12, color: cs.muted),
                ),
              ],
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            AppErrorInline(error: _error!),
          ],
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: canConfirm ? _submit : null,
            icon: _submitting
                ? SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: cs.onSurfaceVariant,
                    ),
                  )
                : const Icon(Icons.delete_forever_rounded),
            label: const Text('Confirmer la suppression'),
            style: FilledButton.styleFrom(
              backgroundColor: cs.error,
              foregroundColor: cs.onError,
              minimumSize: const Size.fromHeight(56),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(AppTheme.radiusL),
              ),
            ),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: _submitting ? null : () => context.pop(),
            child: const Text('Annuler'),
          ),
        ],
      ),
    );
  }

  Widget _buildEmployeeNotice(BuildContext context, AccountProfile profile) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: Text(
          employeeDeletionNotice(profile),
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, color: cs.onSurface, height: 1.4),
        ),
      ),
    );
  }
}

/// AC9 — explanation shown to an EMPLOYEE instead of any deletion action.
/// The profile carries the assigned store, the closest thing the app has to
/// the business name.
String employeeDeletionNotice(AccountProfile profile) {
  final business = profile.storeName ?? 'la boutique de votre propriétaire';
  return 'Votre compte est lié à $business. Pour supprimer vos données, '
      'demandez à votre propriétaire de vous retirer de l\'équipe.';
}
