import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/lang_toggle.dart';
import 'package:keevo_core/widget/phone_validation.dart';
import '../../domain/exception/auth_exception.dart';
import '../../domain/model/login_result.dart';
import '../provider/auth_provider.dart';
import '../provider/session_claims_applier.dart';
import '../widget/previous_account_data_dialog.dart';

/// Controls which flow [AuthPage] runs.
enum AuthMode { register, login }

/// AuthPage — unified register / login screen (HF-1 AC4-AC8).
///
/// A single page with a mode toggle that handles both registration (OWNER
/// account creation) and login (two-step flow with tenant selection).
///
/// - Register mode: "Inscription" label, CGU footer, navigates to /onboarding/sector.
/// - Login mode: "Connexion" label, no CGU footer, navigates based on role.
class AuthPage extends ConsumerStatefulWidget {
  final AuthMode initialMode;

  const AuthPage({super.key, this.initialMode = AuthMode.register});

  @override
  ConsumerState<AuthPage> createState() => _AuthPageState();
}

class _AuthPageState extends ConsumerState<AuthPage> {
  late AuthMode _mode;
  final _formKey = GlobalKey<FormState>();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;
  bool _isFrench = true;

  /// Full phone number with country code (e.g. +237600000000).
  String? _completePhone;
  bool _phoneValid = false;

  @override
  void initState() {
    super.initState();
    _mode = widget.initialMode;
  }

  // v1s-16-13: the CGU / privacy mentions open the read-only terms page.
  late final TapGestureRecognizer _termsTap = TapGestureRecognizer()
    ..onTap = () => context.push('/terms/view');
  late final TapGestureRecognizer _privacyTap = TapGestureRecognizer()
    ..onTap = () => context.push('/terms/view?section=privacy');

  @override
  void dispose() {
    _termsTap.dispose();
    _privacyTap.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  /// Automatically switches to login mode when [registrationProvider] reports
  /// USER_ALREADY_EXISTS. Intentionally does NOT reset the form so the phone
  /// number and password entered by the user are preserved — they can proceed
  /// directly to login without re-typing anything.
  void _autoSwitchToLogin() {
    ref.invalidate(registrationProvider);
    setState(() => _mode = AuthMode.login);
    // Trigger login automatically after the mode switch rebuilds the widget.
    // At this point _completePhone and _passwordController still hold the
    // values the user just typed, so _submit() can fire immediately.
    WidgetsBinding.instance.addPostFrameCallback((_) => _submit());
  }

  /// Switches between register and login modes, resetting form and providers.
  void _toggleMode() {
    final next =
        _mode == AuthMode.register ? AuthMode.login : AuthMode.register;
    // Creating an account requires the CGU to have been accepted; a returning
    // user reaching login directly may never have seen them.
    final termsAccepted =
        ref.read(sharedPreferencesProvider).getBool(kTermsAcceptedKey) ??
            false;
    if (next == AuthMode.register && !termsAccepted) {
      context.go('/terms');
      return;
    }
    _switchMode(next);
  }

  void _switchMode(AuthMode next) {
    // Clear stale errors from the provider that is being deactivated.
    ref.invalidate(registrationProvider);
    ref.invalidate(loginProvider);
    setState(() {
      _mode = next;
      _formKey.currentState?.reset();
      _passwordController.clear();
      _phoneValid = false;
      _completePhone = null;
    });
  }

  /// Login opened as the first screen (splash, logout, session expiry) has
  /// nowhere to go back to: the welcome screen would force the CGU again.
  bool get _hasBack =>
      _mode != widget.initialMode || widget.initialMode == AuthMode.register;

  void _onBack() {
    if (_mode != widget.initialMode) {
      _switchMode(widget.initialMode);
    } else {
      context.go('/terms');
    }
  }

  /// Another account's unsynced operations are still on the device: the
  /// sign-in only goes on if the user accepts losing them.
  Future<void> _askAboutPreviousAccountData(
    PreviousAccountDataException error, {
    required Future<void> Function() onDiscard,
    required Future<void> Function() onKeep,
  }) async {
    final discard =
        await confirmDiscardPreviousAccountData(context, error.pendingOperations);
    await (discard ? onDiscard() : onKeep());
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_phoneValid || _completePhone == null) return;

    if (_mode == AuthMode.register) {
      await ref.read(registrationProvider.notifier).register(
            phoneNumber: _completePhone!,
            password: _passwordController.text.trim(),
          );
    } else {
      await ref.read(loginProvider.notifier).login(
            phoneNumber: _completePhone!,
            password: _passwordController.text.trim(),
          );
    }
  }

  /// Maps provider error to a user-facing message (FR/EN per [_isFrench]).
  String? _computeErrorMessage(AsyncValue<dynamic> asyncState) {
    if (!asyncState.hasError) return null;
    final error = asyncState.error;
    // Handled by a dialog, not an inline error.
    if (error is PreviousAccountDataException) return null;
    if (error is AuthException) {
      if (_isFrench) {
        return switch (error.domainCode) {
          'USER_ALREADY_EXISTS' => 'Un compte avec ce numéro existe déjà',
          'VALIDATION_ERROR' => 'Données invalides, vérifiez vos informations',
          'INVALID_CREDENTIALS' => 'Numéro ou mot de passe incorrect',
          'ACCOUNT_LOCKED' =>
            'Compte verrouillé temporairement. Réessayez dans 15 minutes.',
          'NO_ACTIVE_MEMBERSHIP' =>
            'Votre accès a été désactivé. Contactez votre employeur.',
          'PLATFORM_ADMIN_ACCOUNT' => kPlatformAdminAccountMessage,
          _ => 'Erreur inattendue, réessayez',
        };
      }
      return switch (error.domainCode) {
        'USER_ALREADY_EXISTS' => 'An account with this number already exists',
        'VALIDATION_ERROR' => 'Invalid data, please check your information',
        'INVALID_CREDENTIALS' => 'Incorrect number or password',
        'ACCOUNT_LOCKED' => 'Account temporarily locked. Try again in 15 minutes.',
        'NO_ACTIVE_MEMBERSHIP' =>
          'Your access has been deactivated. Contact your employer.',
        'PLATFORM_ADMIN_ACCOUNT' => kPlatformAdminAccountMessageEn,
        _ => 'Unexpected error, please try again',
      };
    }
    if (error is ArgumentError) return error.message.toString();
    return _isFrench ? 'Erreur de connexion inattendue' : 'Unexpected connection error';
  }

  @override
  Widget build(BuildContext context) {
    final regState = ref.watch(registrationProvider);
    final loginState = ref.watch(loginProvider);
    final asyncState = _mode == AuthMode.register ? regState : loginState;
    final isLoading = asyncState.isLoading;
    final errorMessage = _computeErrorMessage(asyncState);
    final cs = Theme.of(context).colorScheme;

    // Navigate on successful registration (guard: skip if already in login mode).
    // Also auto-transitions to login when the phone already has an account.
    ref.listen(registrationProvider, (_, next) {
      if (_mode != AuthMode.register) return;
      next.whenData((result) {
        if (result != null) context.go('/onboarding/sector');
      });
      if (next.hasError) {
        final error = next.error;
        if (error is AuthException &&
            error.domainCode == 'USER_ALREADY_EXISTS') {
          _autoSwitchToLogin();
        }
        if (error is PreviousAccountDataException) {
          final notifier = ref.read(registrationProvider.notifier);
          _askAboutPreviousAccountData(error,
              onDiscard: notifier.discardPreviousAccountDataAndContinue,
              onKeep: notifier.cancelPendingSignIn);
        }
      }
    });

    // Navigate on successful login (guard: skip if already in register mode).
    ref.listen(loginProvider, (_, next) {
      if (_mode != AuthMode.login) return;
      final error = next.error;
      if (next.hasError && error is PreviousAccountDataException) {
        final notifier = ref.read(loginProvider.notifier);
        _askAboutPreviousAccountData(error,
            onDiscard: notifier.discardPreviousAccountDataAndContinue,
            onKeep: notifier.cancelPendingSignIn);
        return;
      }
      next.whenData((result) {
        if (result == null) return;
        switch (result) {
          case AuthenticatedResult():
            // The splash decides where a signed-in user goes (unfinished setup
            // wizard, forced password change, role) — one rule for every path.
            context.go('/splash');
          case NeedsTenantSelectionResult(:final loginToken, :final memberships):
            context.go('/tenant-picker', extra: <String, dynamic>{
              'loginToken': loginToken,
              'memberships': memberships,
            });
        }
      });
    });

    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
        child: Column(
          children: [
            // ── Header : retour + changement de langue ──────────────────
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  if (_hasBack)
                    IconButton(
                      icon: Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: cs.surfaceContainerHighest,
                        ),
                        child: Icon(
                          Icons.arrow_back,
                          size: 20,
                          color: cs.onSurface,
                        ),
                      ),
                      // Déterministe (pas de canPop()/pop(), qui dépendait
                      // du chemin d'arrivée sur la page) : voir [_onBack].
                      onPressed: _onBack,
                    )
                  else
                    // Garde le sélecteur de langue à droite.
                    const SizedBox(width: 48, height: 48),
                  LangToggle(
                    isFrench: _isFrench,
                    onChanged: (fr) => setState(() => _isFrench = fr),
                  ),
                ],
              ),
            ),

            // ── Corps : contenu poussé vers le bas, scrollable si besoin ──
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  return SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(minHeight: constraints.maxHeight),
                      child: IntrinsicHeight(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.end,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _mode == AuthMode.register
                            ? (_isFrench ? 'Inscription' : 'Sign up')
                            : (_isFrench ? 'Connexion' : 'Log in'),
                        style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                              fontWeight: FontWeight.w700,
                              color: cs.onSurface,
                              letterSpacing: -0.3,
                            ),
                      ),
                      const SizedBox(height: 28),

                      // ── Champ numéro WhatsApp ───────────────────────
                      Text(
                        _isFrench ? 'Numéro WhatsApp' : 'WhatsApp number',
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                              fontWeight: FontWeight.w600,
                              color: cs.onSurface,
                            ),
                      ),
                      const SizedBox(height: 6),
                      IntlPhoneField(
                        key: const Key('phoneField'),
                        decoration: InputDecoration(
                          errorMaxLines: 3,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.outlineVariant),
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.outlineVariant),
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.primary, width: 1.5),
                          ),
                        ),
                        initialCountryCode: kDefaultPhoneCountryCode,
                        languageCode: 'fr',
                        keyboardType: TextInputType.phone,
                        // Shown by the field's own length check, which runs
                        // before [validator].
                        invalidNumberMessage: _isFrench
                            ? 'Numéro invalide pour ce pays'
                            : 'Invalid number for this country',
                        onChanged: (PhoneNumber phone) {
                          _completePhone = phone.completeNumber;
                          _phoneValid = isValidPhoneNumber(phone);
                        },
                        onCountryChanged: (_) {
                          _phoneValid = false;
                          _completePhone = null;
                        },
                        // Called before onChanged: judge the number received.
                        validator: (phone) {
                          if (!isValidPhoneNumber(phone)) {
                            return _isFrench
                                ? 'Numéro invalide pour ce pays'
                                : 'Invalid number for this country';
                          }
                          return null;
                        },
                      ),

                      const SizedBox(height: 16),

                      // ── Champ mot de passe ──────────────────────────
                      Text(
                        _isFrench ? 'Mot de passe' : 'Password',
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                              fontWeight: FontWeight.w600,
                              color: cs.onSurface,
                            ),
                      ),
                      const SizedBox(height: 6),
                      TextFormField(
                        key: const Key('passwordField'),
                        controller: _passwordController,
                        obscureText: _obscurePassword,
                        decoration: InputDecoration(
                          hintText: _isFrench
                              ? 'Entrez votre mot de passe'
                              : 'Enter your password',
                          // The register-mode length error is longer than a
                          // 720 px wide screen: let it wrap.
                          errorMaxLines: 3,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.outlineVariant),
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.outlineVariant),
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(color: cs.primary, width: 1.5),
                          ),
                          suffixIcon: IconButton(
                            icon: Icon(
                              _obscurePassword
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                              color: cs.onSurfaceVariant,
                            ),
                            onPressed: () => setState(
                              () => _obscurePassword = !_obscurePassword,
                            ),
                          ),
                        ),
                        validator: (value) {
                          if (value == null || value.trim().isEmpty) {
                            return _isFrench
                                ? 'Le mot de passe est requis'
                                : 'Password is required';
                          }
                          if (_mode == AuthMode.register &&
                              value.trim().length < 8) {
                            return _isFrench
                                ? 'Le mot de passe doit contenir au moins 8 caractères'
                                : 'Password must be at least 8 characters';
                          }
                          return null;
                        },
                        textInputAction: TextInputAction.done,
                      ),

                      const SizedBox(height: 20),

                      // ── Note CGU (register mode) ────────────────────
                      // Purement informative : l'acceptation effective des
                      // CGU a déjà été bloquée en amont sur TermsPage
                      // (scroll-to-unlock + double checkbox RGPD). Pas de
                      // case à cocher ici pour éviter une double demande
                      // de consentement redondante.
                      if (_mode == AuthMode.register) ...[
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Icon(Icons.check_circle_rounded, size: 18, color: cs.primary),
                            const SizedBox(width: 8),
                            Expanded(
                              child: RichText(
                                text: TextSpan(
                                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                        color: cs.onSurfaceVariant,
                                      ),
                                  children: _isFrench
                                      ? [
                                          const TextSpan(text: 'Vous avez accepté nos '),
                                          TextSpan(
                                            text: "Conditions d'utilisation",
                                            recognizer: _termsTap,
                                            style: TextStyle(
                                              color: cs.primary,
                                              fontWeight: FontWeight.w600,
                                            ),
                                          ),
                                          const TextSpan(text: ' et notre '),
                                          TextSpan(
                                            text: 'Politique de confidentialité',
                                            recognizer: _privacyTap,
                                            style: TextStyle(
                                              color: cs.primary,
                                              fontWeight: FontWeight.w600,
                                            ),
                                          ),
                                        ]
                                      : [
                                          const TextSpan(text: 'You have accepted our '),
                                          TextSpan(
                                            text: 'Terms of Use',
                                            recognizer: _termsTap,
                                            style: TextStyle(
                                              color: cs.primary,
                                              fontWeight: FontWeight.w600,
                                            ),
                                          ),
                                          const TextSpan(text: ' and our '),
                                          TextSpan(
                                            text: 'Privacy Policy',
                                            recognizer: _privacyTap,
                                            style: TextStyle(
                                              color: cs.primary,
                                              fontWeight: FontWeight.w600,
                                            ),
                                          ),
                                        ],
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 20),
                      ],

                      if (errorMessage != null) ...[
                        _ErrorBanner(message: errorMessage),
                        const SizedBox(height: 16),
                      ],

                      // ── Bouton Continuer ────────────────────────────
                      SizedBox(
                        width: double.infinity,
                        height: 56,
                        child: FilledButton(
                          key: const Key('submitButton'),
                          onPressed: isLoading ? null : _submit,
                          style: FilledButton.styleFrom(
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(28),
                            ),
                          ),
                          child: isLoading
                              ? SizedBox(
                                  height: 20,
                                  width: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                                  ),
                                )
                              : Text(
                                  _mode == AuthMode.register
                                      ? (_isFrench ? 'Créer mon compte' : 'Create my account')
                                      : (_isFrench ? 'Se connecter' : 'Log in'),
                                  style: const TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                        ),
                      ),

                      const SizedBox(height: 16),

                      // ── Mot de passe oublié (Story 14.12, login mode only) ──
                      if (_mode == AuthMode.login)
                        Center(
                          child: TextButton(
                            onPressed: () => context.push('/auth/forgot-password'),
                            child: Text(
                              _isFrench ? 'Mot de passe oublié ?' : 'Forgot password?',
                            ),
                          ),
                        ),

                      Center(
                        child: GestureDetector(
                          onTap: _toggleMode,
                          child: RichText(
                            text: TextSpan(
                              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: cs.onSurfaceVariant,
                                  ),
                              children: [
                                TextSpan(
                                  text: _mode == AuthMode.register
                                      ? (_isFrench ? 'Déjà un compte ? ' : 'Already have an account? ')
                                      : (_isFrench ? 'Pas encore de compte ? ' : "Don't have an account? "),
                                ),
                                TextSpan(
                                  text: _mode == AuthMode.register
                                      ? (_isFrench ? 'Se connecter' : 'Log in')
                                      : (_isFrench ? "S'inscrire" : 'Sign up'),
                                  style: TextStyle(
                                    color: cs.onSurface,
                                    fontWeight: FontWeight.w700,
                                    decoration: TextDecoration.underline,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
        ),
      ),
    );
  }
}

// ── Widgets privés ────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const Key('errorBanner'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(
            Icons.error_outline,
            color: Theme.of(context).colorScheme.onErrorContainer,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onErrorContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
