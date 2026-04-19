import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../domain/model/sector_type.dart';
import '../provider/onboarding_provider.dart';

/// ShopNamePage — Step 2 of the onboarding wizard.
///
/// The user enters the store name (2–100 chars).
/// On submit the [Onboarding] notifier calls the backend.
/// On success: writes [kOnboardingWizardSeenKey]=true then navigates to /pos.
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

  /// Called when ONBOARDING_ALREADY_COMPLETED is received from the backend.
  /// Marks the wizard locally as complete and navigates to /pos silently.
  Future<void> _completeLocallyAndNavigate() async {
    final router = GoRouter.of(context);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kOnboardingWizardSeenKey, true);
    await prefs.setString(kSectorTypeKey, widget.sectorType.apiCode);
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
        if (result != null) {
          // Capture router before async gaps (use_build_context_synchronously)
          final router = GoRouter.of(context);
          // Persist onboarding completion state + selected sector for POS empty state (AC6)
          final prefs = await SharedPreferences.getInstance();
          await prefs.setBool(kOnboardingWizardSeenKey, true);
          await prefs.setString(kSectorTypeKey, widget.sectorType.apiCode);
          if (!mounted) return;
          // AC2 (HF-1): Route OWNER to /dashboard; EMPLOYEE stays on /pos.
          final role = prefs.getString(kUserRoleKey);
          router.go(role == 'OWNER' ? '/dashboard' : '/pos');
        }
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('Nom de votre commerce'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Comment s\'appelle votre commerce ?',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 24),
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
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 32),
                FilledButton(
                  key: const Key('submitButton'),
                  onPressed: isLoading ? null : _onSubmit,
                  child: isLoading
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Terminer'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
