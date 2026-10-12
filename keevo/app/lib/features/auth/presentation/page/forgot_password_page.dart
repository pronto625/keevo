import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../../../core/storage/app_constants.dart';
import '../../../../core/theme/app_theme.dart';
import '../provider/auth_provider.dart';
import '../../../../core/widget/phone_validation.dart';

/// ForgotPasswordPage — Story 14.12 — Forgot password OTP request screen.
///
/// User enters their WhatsApp number, taps "Envoyer le code".
/// Always shows success (anti-enumeration like the backend).
/// Navigates to /auth/reset-password with the phone number on success.
class ForgotPasswordPage extends ConsumerStatefulWidget {
  const ForgotPasswordPage({super.key});

  @override
  ConsumerState<ForgotPasswordPage> createState() => _ForgotPasswordPageState();
}

class _ForgotPasswordPageState extends ConsumerState<ForgotPasswordPage> {
  String? _completePhone;
  bool _phoneValid = false;
  bool _isCooldown = false;
  int _cooldownSeconds = 0;

  /// One countdown at a time: resetting it cancels the running one, otherwise
  /// a quick resubmit after an error would make the countdown run twice as fast.
  Timer? _cooldownTimer;

  @override
  void dispose() {
    _cooldownTimer?.cancel();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_phoneValid || _completePhone == null || _isCooldown) return;

    setState(() {
      _isCooldown = true;
      _cooldownSeconds = 60;
    });
    _startCooldown();

    await ref.read(forgotPasswordProvider.notifier).requestReset(
          phoneNumber: _completePhone!,
        );

    if (!mounted) return;

    // Check for error (network failure, 5xx, etc.) — D1 anti-enumeration applies to
    // business logic (unknown phone), but infrastructure errors deserve user feedback.
    // Without this check, user is navigated to reset-password to enter a code that
    // was never delivered.
    final forgotState = ref.read(forgotPasswordProvider);
    if (forgotState.hasError) {
      // No code was sent: the anti-spam delay only applies to real sends.
      _cooldownTimer?.cancel();
      setState(() {
        _isCooldown = false;
        _cooldownSeconds = 0;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            "Erreur de connexion. Vérifiez votre réseau et réessayez.",
          ),
        ),
      );
      return;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text(
          "Si ce numéro existe, un code WhatsApp vous a été envoyé.",
        ),
      ),
    );
    context
        .push('/auth/reset-password', extra: {'phoneNumber': _completePhone!});
  }

  void _startCooldown() {
    _cooldownTimer?.cancel();
    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        if (_cooldownSeconds > 1) {
          _cooldownSeconds--;
        } else {
          _cooldownSeconds = 0;
          _isCooldown = false;
          timer.cancel();
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final forgotState = ref.watch(forgotPasswordProvider);

    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(title: const Text('Mot de passe oublié')),
      extendBodyBehindAppBar: true,
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 16),
                Text(
                  'Réinitialiser votre mot de passe',
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: cs.onSurface,
                    letterSpacing: -0.3,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Entrez votre numéro WhatsApp pour recevoir un code de réinitialisation.',
                  style: theme.textTheme.bodyLarge?.copyWith(
                    color: cs.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 28),
                const _FieldLabel('Numéro WhatsApp'),
                const SizedBox(height: 6),
                IntlPhoneField(
                  key: const Key('forgotPhoneField'),
                  decoration: InputDecoration(
                    border: _outlinedBorder(cs.outlineVariant),
                    enabledBorder: _outlinedBorder(cs.outlineVariant),
                    focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                  ),
                  initialCountryCode: kDefaultPhoneCountryCode,
                  languageCode: 'fr',
                  keyboardType: TextInputType.phone,
                  invalidNumberMessage: 'Numéro invalide pour ce pays',
                  onChanged: (PhoneNumber phone) {
                    _completePhone = phone.completeNumber;
                    _phoneValid = isValidPhoneNumber(phone);
                  },
                  onCountryChanged: (_) {
                    _phoneValid = false;
                    _completePhone = null;
                  },
                ),
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: FilledButton(
                    key: const Key('sendOtpButton'),
                    onPressed:
                        (forgotState.isLoading || _isCooldown) ? null : _submit,
                    style: FilledButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(28),
                      ),
                    ),
                    child: forgotState.isLoading
                        ? SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                          )
                        : Text(
                            _isCooldown
                                ? 'Réessayer dans ${_cooldownSeconds}s'
                                : 'Envoyer le code',
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
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

// Même bordure fine que les champs d'AuthPage (label au-dessus du champ).
OutlineInputBorder _outlinedBorder(Color color, {double width = 1}) =>
    OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: color, width: width),
    );

class _FieldLabel extends StatelessWidget {
  final String text;
  const _FieldLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: Theme.of(context).textTheme.labelLarge?.copyWith(
            fontWeight: FontWeight.w600,
            color: Theme.of(context).colorScheme.onSurface,
          ),
    );
  }
}
