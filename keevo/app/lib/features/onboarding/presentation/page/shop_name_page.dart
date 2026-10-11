import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../domain/model/sector_type.dart';
import '../provider/onboarding_provider.dart';
import '../widget/onboarding_progress_dots.dart';

/// ShopNamePage — Step 2 of the onboarding wizard.
///
/// The user enters the store name (2–100 chars).
/// On submit the [Onboarding] notifier calls the backend.
/// On success: marks the wizard completed, renews the session (the token then
/// says onboardingCompleted=true) and navigates to /dashboard or /pos.
/// On error: shows a SnackBar with the error message.
class ShopNamePage extends ConsumerStatefulWidget {
  final SectorType sectorType;

  const ShopNamePage({super.key, required this.sectorType});

  @override
  ConsumerState<ShopNamePage> createState() => _ShopNamePageState();
}

class _ShopNamePageState extends ConsumerState<ShopNamePage> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  String? _validateStoreName(String? value) {
    if (value == null || value.trim().isEmpty) return 'Le nom du commerce est requis';
    if (value.trim().length < 2) return 'Le nom doit contenir au moins 2 caractères';
    if (value.trim().length > 100) return 'Le nom ne peut pas dépasser 100 caractères';
    return null;
  }

  Future<void> _onSubmit() async {
    if (!_formKey.currentState!.validate()) return;
    await ref.read(onboardingProvider.notifier).complete(
          sectorType: widget.sectorType,
          storeName: _nameController.text.trim(),
        );
  }

  /// Called when ONBOARDING_ALREADY_COMPLETED is received from the backend:
  /// the tenant is already set up, so the wizard is done.
  Future<void> _completeLocallyAndNavigate() => _finishWizard();

  /// Marks the wizard done and leaves it. The session is renewed so the
  /// access token carries onboardingCompleted=true (the registration token
  /// says false); the local flag covers the case where the server cannot be
  /// reached.
  Future<void> _finishWizard() async {
    // Capture router before async gaps (use_build_context_synchronously)
    final router = GoRouter.of(context);
    final refresher = ref.read(sessionRefresherProvider);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kOnboardingWizardSeenKey, true);
    await prefs.setBool(kOnboardingWizardCompletedKey, true);
    // Selected sector for the POS empty state (AC6)
    await prefs.setString(kSectorTypeKey, widget.sectorType.apiCode);
    try {
      await refresher.refresh();
    } catch (_) {
      // Best effort: the local flag already keeps the wizard closed.
    }
    if (!mounted) return;
    // AC2 (HF-1): Route OWNER to /dashboard; EMPLOYEE stays on /pos.
    final role = prefs.getString(kUserRoleKey);
    router.go(role == 'OWNER' ? '/dashboard' : '/pos');
  }

  /// Returns a user-friendly error message, OR null for errors that are
  /// handled programmatically (e.g. ONBOARDING_ALREADY_COMPLETED → navigate).
  String? _errorMessage(AsyncValue<dynamic> state) {
    final error = state.error;
    if (error == null) return null;
    final msg = error.toString();
    // Map known backend domain codes to user-friendly French messages
    if (msg.contains('SECTOR_TEMPLATE_NOT_FOUND')) {
      return 'Secteur d\'activité non reconnu. Veuillez retourner et sélectionner un secteur valide.';
    }
    if (msg.contains('ONBOARDING_ALREADY_COMPLETED')) {
      // The tenant is already set up on the backend — treat as success:
      // mark wizard done locally and navigate to /pos without user action.
      return null; // handled in listener below
    }
    if (msg.contains('401') || msg.contains('Unauthorized')) {
      return 'Session expirée. Veuillez vous reconnecter.';
    }
    if (msg.contains('SocketException') || msg.contains('Connection refused') || msg.contains('network')) {
      return 'Pas de connexion internet. Vérifiez votre réseau et réessayez.';
    }
    // Generic fallback — never expose a stack trace or class name
    return 'Une erreur est survenue. Veuillez réessayer.';
  }

  @override
  Widget build(BuildContext context) {
    final asyncState = ref.watch(onboardingProvider);
    final isLoading = asyncState.isLoading;

    ref.listen(onboardingProvider, (previous, next) {
      next.whenData((result) async {
        if (result != null) await _finishWizard();
      });
      if (next.hasError) {
        final errMsg = next.error?.toString() ?? '';
        // ONBOARDING_ALREADY_COMPLETED: the tenant is already set up on the backend.
        // Mark locally as done and navigate to /pos — no user action required.
        if (errMsg.contains('ONBOARDING_ALREADY_COMPLETED')) {
          _completeLocallyAndNavigate();
          return;
        }
        final msg = _errorMessage(next);
        if (msg != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              key: const Key('errorSnackBar'),
              content: Text(msg),
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
          );
        }
      }
    });

    final cs = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Scaffold(
      appBar: AppBar(
        title: const Center(child: OnboardingProgressDots(step: 4, total: 4)),
      ),
      extendBodyBehindAppBar: true,
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Comment s\'appelle\nvotre commerce ?',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                        height: 1.15,
                        letterSpacing: -0.3,
                      ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Ce nom apparaîtra sur vos reçus et rapports',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                ),
                const SizedBox(height: 28),
                // Décoration héritée du thème global (fill + radius doux) —
                // pas de `border` explicite ici, contrairement à l'ancienne
                // version qui écrasait le style thémé par un défaut carré.
                TextFormField(
                  key: const Key('shopNameField'),
                  controller: _nameController,
                  autofocus: true,
                  textInputAction: TextInputAction.done,
                  onFieldSubmitted: (_) => _onSubmit(),
                  validator: _validateStoreName,
                  decoration: const InputDecoration(
                    labelText: 'Nom du commerce',
                    hintText: 'Ex: Boutique Élégance',
                  ),
                ),
                const SizedBox(height: 32),
                SizedBox(
                  height: 56,
                  child: FilledButton(
                    key: const Key('submitButton'),
                    onPressed: isLoading ? null : _onSubmit,
                    style: FilledButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(28),
                      ),
                    ),
                    child: isLoading
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Text('Terminer'),
                  ),
                ),
              ],
            ),
          ),
        ),
        ),
      ),
    );
  }
}
