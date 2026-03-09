import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/storage/app_constants.dart';

/// Onboarding welcome screen — affiché uniquement au premier lancement.
///
/// Design inspiré de l'image fournie :
///  - Sélecteur de langue (FR / EN) en haut
///  - Bloc central avec logo Keevo
///  - Zone illustrative au centre
///  - Bouton "Commencer" en bas
class OnboardingPage extends StatefulWidget {
  const OnboardingPage({super.key});

  @override
  State<OnboardingPage> createState() => _OnboardingPageState();
}

class _OnboardingPageState extends State<OnboardingPage>
    with SingleTickerProviderStateMixin {
  bool _isFrench = true;
  late final AnimationController _fadeCtrl;
  late final Animation<double> _fadeAnim;

  @override
  void initState() {
    super.initState();
    _fadeCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    )..forward();
    _fadeAnim = CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeIn);
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    super.dispose();
  }

  Future<void> _onStart() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kOnboardingSeenKey, true);
    if (mounted) context.go('/terms');
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: FadeTransition(
          opacity: _fadeAnim,
          child: Column(
            children: [
              // ── Sélecteur de langue ───────────────────────────────────
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 16,
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    _LangButton(
                      label: 'Français',
                      selected: _isFrench,
                      onTap: () => setState(() => _isFrench = true),
                    ),
                    const SizedBox(width: 8),
                    _LangButton(
                      label: 'English',
                      selected: !_isFrench,
                      onTap: () => setState(() => _isFrench = false),
                    ),
                  ],
                ),
              ),

              // ── Card centrale avec logo ───────────────────────────────
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(
                    vertical: 32,
                    horizontal: 24,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: cs.primary.withOpacity(0.08),
                        blurRadius: 24,
                        offset: const Offset(0, 8),
                      ),
                    ],
                    border: Border.all(
                      color: cs.outlineVariant.withOpacity(0.4),
                    ),
                  ),
                  child: Column(
                    children: [
                      Text(
                        _isFrench ? 'Bienvenue sur' : 'Welcome to',
                        style:
                            Theme.of(context).textTheme.headlineMedium?.copyWith(
                                  fontWeight: FontWeight.w300,
                                  color: cs.onSurface,
                                ),
                      ),
                      const SizedBox(height: 16),
                      // Logo Keevo
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Container(
                            width: 52,
                            height: 52,
                            decoration: BoxDecoration(
                              color: cs.primary,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Center(
                              child: Text(
                                'K',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 28,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Keevo',
                                style: Theme.of(context)
                                    .textTheme
                                    .headlineSmall
                                    ?.copyWith(
                                      fontWeight: FontWeight.bold,
                                      color: cs.primary,
                                      letterSpacing: 0.5,
                                    ),
                              ),
                              Text(
                                _isFrench
                                    ? 'GESTION DE STOCK'
                                    : 'INVENTORY MANAGEMENT',
                                style: Theme.of(context)
                                    .textTheme
                                    .labelSmall
                                    ?.copyWith(
                                      color: cs.onSurfaceVariant,
                                      letterSpacing: 1.5,
                                    ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),

              SizedBox(height: size.height * 0.04),

              // ── Illustration centrale ─────────────────────────────────
              Expanded(
                child: Padding(
                  padding: EdgeInsets.symmetric(
                    horizontal: size.width * 0.12,
                  ),
                  child: _OnboardingIllustration(
                    size: size,
                    primaryColor: cs.primary,
                    isFrench: _isFrench,
                  ),
                ),
              ),

              SizedBox(height: size.height * 0.03),

              // ── Bouton bas ────────────────────────────────────────────
              Padding(
                padding: EdgeInsets.fromLTRB(
                  size.width * 0.06, 
                  0, 
                  size.width * 0.06, 
                  size.height * 0.04
                ),
                child: _StartButton(
                  label: _isFrench
                      ? 'Glisser pour commencer'
                      : 'Swipe to get started',
                  onTap: _onStart,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Widget bouton de langue ───────────────────────────────────────────────────

class _LangButton extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _LangButton({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? cs.primary : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: selected ? cs.primary : cs.outline,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : cs.onSurfaceVariant,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            fontSize: 13,
          ),
        ),
      ),
    );
  }
}

// ── Illustration ──────────────────────────────────────────────────────────────

class _OnboardingIllustration extends StatelessWidget {
  final Size size;
  final Color primaryColor;
  final bool isFrench;

  const _OnboardingIllustration({
    required this.size,
    required this.primaryColor,
    required this.isFrench,
  });

  @override
  Widget build(BuildContext context) {
    final screenSize = MediaQuery.of(context).size;
    final isCompact = screenSize.height < 700;
    
    // Dimensions adaptatives basées sur la taille d'écran
    final outerCircleSize = isCompact ? screenSize.width * 0.25 : screenSize.width * 0.3;
    final innerCircleSize = outerCircleSize * 0.67;
    final iconSize = outerCircleSize * 0.37;
    final verticalSpacing = isCompact ? screenSize.height * 0.025 : screenSize.height * 0.035;
    
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      mainAxisSize: MainAxisSize.min,
      children: [
        // Icône centrale stylisée
        Container(
          width: outerCircleSize,
          height: outerCircleSize,
          decoration: BoxDecoration(
            color: primaryColor.withOpacity(0.06),
            shape: BoxShape.circle,
          ),
          child: Center(
            child: Container(
              width: innerCircleSize,
              height: innerCircleSize,
              decoration: BoxDecoration(
                color: primaryColor.withOpacity(0.12),
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.store_rounded,
                size: iconSize,
                color: primaryColor,
              ),
            ),
          ),
        ),
        SizedBox(height: verticalSpacing),
        // Feature pills
        Wrap(
          spacing: screenSize.width * 0.025,
          runSpacing: screenSize.width * 0.025,
          alignment: WrapAlignment.center,
          children: [
            _FeaturePill(
              icon: Icons.inventory_2_outlined,
              label: isFrench ? 'Stock en temps réel' : 'Real-time stock',
              color: primaryColor,
            ),
            _FeaturePill(
              icon: Icons.point_of_sale_outlined,
              label: isFrench ? 'Caisse (POS)' : 'Point of Sale',
              color: primaryColor,
            ),
            _FeaturePill(
              icon: Icons.wifi_off_rounded,
              label: isFrench ? 'Mode hors-ligne' : 'Offline mode',
              color: primaryColor,
            ),
            _FeaturePill(
              icon: Icons.chat_bubble_outline_rounded,
              label: isFrench ? 'Rapports WhatsApp' : 'WhatsApp reports',
              color: primaryColor,
            ),
          ],
        ),
      ],
    );
  }
}

class _FeaturePill extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;

  const _FeaturePill({
    required this.icon,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final screenSize = MediaQuery.of(context).size;
    final isCompact = screenSize.height < 700 || screenSize.width < 350;
    
    // Dimensions adaptatives
    final horizontalPadding = screenSize.width * 0.035;
    final verticalPadding = isCompact ? 6.0 : 8.0;
    final iconSize = isCompact ? 14.0 : 16.0;
    final fontSize = isCompact ? 11.0 : 12.0;
    final borderRadius = isCompact ? 16.0 : 20.0;
    
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: horizontalPadding, 
        vertical: verticalPadding
      ),
      decoration: BoxDecoration(
        color: color.withOpacity(0.08),
        borderRadius: BorderRadius.circular(borderRadius),
        border: Border.all(color: color.withOpacity(0.2)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: iconSize, color: color),
          SizedBox(width: screenSize.width * 0.015),
          Text(
            label,
            style: TextStyle(
              fontSize: fontSize,
              color: color,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Bouton "Commencer" style swipe ────────────────────────────────────────────

class _StartButton extends StatelessWidget {
  final String label;
  final VoidCallback onTap;

  const _StartButton({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 60,
        decoration: BoxDecoration(
          color: cs.primary.withOpacity(0.08),
          borderRadius: BorderRadius.circular(30),
          border: Border.all(color: cs.primary.withOpacity(0.2)),
        ),
        child: Row(
          children: [
            const SizedBox(width: 8),
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: cs.primary,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.store_rounded,
                color: Colors.white,
                size: 22,
              ),
            ),
            Expanded(
              child: Center(
                child: Text(
                  label,
                  style: TextStyle(
                    color: cs.onSurfaceVariant,
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ),
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: cs.primary,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.arrow_forward_rounded,
                color: Colors.white,
                size: 20,
              ),
            ),
            const SizedBox(width: 8),
          ],
        ),
      ),
    );
  }
}
