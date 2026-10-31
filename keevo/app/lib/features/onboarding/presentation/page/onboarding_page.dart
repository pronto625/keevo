import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/lang_toggle.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Onboarding welcome screen — affiché uniquement au premier lancement.
///
/// Soft/modern rebrand, révision 2026-09-15 : structure calquée sur la
/// référence visuelle fournie (dégradé doux en haut, titre gras aligné à
/// gauche, liste de cartes icône + titre/sous-titre, CTA plein en bas).
/// Pas d'indicateur de progression (jugé superflu — retiré sur cet écran).
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
    final cs = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: FadeTransition(
            opacity: _fadeAnim,
            child: Column(
              children: [
                // ── Sélecteur de langue ─────────────────────────────────
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      LangToggle(
                        isFrench: _isFrench,
                        onChanged: (fr) => setState(() => _isFrench = fr),
                      ),
                    ],
                  ),
                ),

                // ── Espace de respiration : pousse le titre vers le bas,
                // grande zone de dégradé visible au-dessus (cf. référence).
                Expanded(
                  flex: 3,
                  child: Align(
                    alignment: Alignment.bottomLeft,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(24, 0, 24, 4),
                      child: Text(
                        _isFrench
                            ? 'Votre commerce,\nmieux géré avec Keevo'
                            : 'Run your shop\nbetter, with Keevo',
                        textAlign: TextAlign.left,
                        style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                              fontWeight: FontWeight.w700,
                              color: cs.onSurface,
                              height: 1.15,
                              letterSpacing: -0.3,
                            ),
                      ),
                    ),
                  ),
                ),

                // ── Liste de cartes (icône + titre/sous-titre) ──────────
                Expanded(
                  flex: 5,
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
                    child: Column(
                      children: [
                        _FeatureCard(
                          icon: Icons.inventory_2_outlined,
                          title: _isFrench ? 'Stock en temps réel' : 'Real-time stock',
                          subtitle: _isFrench
                              ? 'Vos quantités toujours à jour'
                              : 'Always know what\'s in stock',
                        ),
                        const SizedBox(height: 12),
                        _FeatureCard(
                          icon: Icons.point_of_sale_outlined,
                          title: _isFrench ? 'Caisse (POS)' : 'Point of Sale',
                          subtitle: _isFrench
                              ? 'Encaissez en quelques secondes'
                              : 'Check out in seconds',
                        ),
                        const SizedBox(height: 12),
                        _FeatureCard(
                          icon: Icons.wifi_off_rounded,
                          title: _isFrench ? 'Mode hors-ligne' : 'Offline mode',
                          subtitle: _isFrench
                              ? 'Fonctionne même sans connexion'
                              : 'Works even without connection',
                        ),
                        const SizedBox(height: 12),
                        _FeatureCard(
                          icon: Icons.chat_bubble_outline_rounded,
                          title: _isFrench ? 'Rapports WhatsApp' : 'WhatsApp reports',
                          subtitle: _isFrench
                              ? 'Votre bilan chaque jour'
                              : 'Your daily summary',
                        ),
                      ],
                    ),
                  ),
                ),

                // ── CTA ──────────────────────────────────────────────────
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 8, 24, 20),
                  child: SizedBox(
                    width: double.infinity,
                    height: 56,
                    child: FilledButton(
                      onPressed: _onStart,
                      style: FilledButton.styleFrom(
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(28),
                        ),
                      ),
                      child: Text(
                        _isFrench ? 'Commencer' : 'Get started',
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
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

// ── Carte de fonctionnalité (icône + titre/sous-titre) ──────────────────────
//
// Structure calquée sur la référence : avatar rond à gauche, titre +
// sous-titre empilés au centre, badge "inclus" à droite. Purement
// informatif (pas de sélection possible sur cet écran) — le badge est donc
// un check déjà "actif", pas une case à cocher vide.

class _FeatureCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;

  const _FeatureCard({
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        border: Border.all(color: cs.outlineVariant.withAlpha(90)),
      ),
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: AppTheme.softTint(Theme.of(context).brightness),
              shape: BoxShape.circle,
            ),
            alignment: Alignment.center,
            child: Icon(icon, size: 20, color: cs.primary),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: cs.onSurface,
                      ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: cs.primary,
              borderRadius: BorderRadius.circular(7),
            ),
            child: Icon(Icons.check_rounded, size: 16, color: cs.onPrimary),
          ),
        ],
      ),
    );
  }
}
