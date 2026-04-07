import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/storage/app_constants.dart';

/// Page de validation des Conditions Générales d'Utilisation.
///
/// Pattern utilisé : page dédiée avec scroll + checkbox obligatoire.
/// L'utilisateur doit :
///   1. Faire défiler le texte des CGU (active automatiquement la checkbox)
///   2. Cocher la case d'acceptation
///   3. Appuyer sur "J'accepte et continuer"
///
/// L'acceptation est persistée en SharedPreferences — la page n'est plus
/// affichée lors des ouvertures suivantes.
class TermsPage extends StatefulWidget {
  const TermsPage({super.key});

  @override
  State<TermsPage> createState() => _TermsPageState();
}

class _TermsPageState extends State<TermsPage> {
  final ScrollController _scrollCtrl = ScrollController();

  bool _hasScrolledToBottom = false;
  bool _termsAccepted = false;
  bool _privacyAccepted = false;

  bool get _canProceed => _termsAccepted && _privacyAccepted;

  @override
  void initState() {
    super.initState();
    _scrollCtrl.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollCtrl.removeListener(_onScroll);
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_hasScrolledToBottom) {
      final max = _scrollCtrl.position.maxScrollExtent;
      final current = _scrollCtrl.offset;
      // Considéré "lu" à 80 % du scroll
      if (current >= max * 0.80) {
        setState(() => _hasScrolledToBottom = true);
      }
    }
  }

  Future<void> _accept() async {
    if (!_canProceed) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kTermsAcceptedKey, true);
    if (mounted) context.go('/auth/register');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Header ──────────────────────────────────────────────
            _Header(onBack: () {
              if (context.canPop()) {
                context.pop();
              } else {
                context.go('/onboarding');
              }
            }),

            // ── Contenu scrollable ───────────────────────────────────
            Expanded(
              child: SingleChildScrollView(
                controller: _scrollCtrl,
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SizedBox(height: 8),
                    Text(
                      'Conditions\nd\'utilisation',
                      style: Theme.of(context)
                          .textTheme
                          .headlineMedium
                          ?.copyWith(fontWeight: FontWeight.bold, height: 1.2),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Dernière mise à jour : 4 mars 2026',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: cs.onSurfaceVariant,
                          ),
                    ),
                    const SizedBox(height: 24),

                    // Indicateur de progression de lecture
                    if (!_hasScrolledToBottom)
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          color: cs.primaryContainer,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              Icons.info_outline_rounded,
                              size: 16,
                              color: cs.onPrimaryContainer,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Veuillez lire jusqu\'en bas pour activer les cases',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: cs.onPrimaryContainer,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),

                    const SizedBox(height: 20),

                    _TermsSection(
                      title: '1. Acceptation des conditions',
                      content:
                          'En utilisant Keevo, vous acceptez d\'être lié par les présentes conditions. '
                          'Si vous n\'acceptez pas ces conditions, vous ne pouvez pas utiliser l\'application.',
                    ),
                    _TermsSection(
                      title: '2. Description du service',
                      content:
                          'Keevo est une application de gestion de stock et de ventes destinée aux commerçants au Cameroun. '
                          'Le service permet la gestion d\'inventaire, les transactions de vente (POS), '
                          'les rapports, et la synchronisation multi-boutiques.',
                    ),
                    _TermsSection(
                      title: '3. Compte utilisateur',
                      content:
                          'Vous êtes responsable de la confidentialité de vos identifiants. '
                          'Vous devez nous notifier immédiatement de toute utilisation non autorisée de votre compte. '
                          'Un seul compte par numéro WhatsApp est autorisé.',
                    ),
                    _TermsSection(
                      title: '4. Données et confidentialité',
                      content:
                          'Nous collectons les données nécessaires au fonctionnement du service : '
                          'numéro de téléphone, données de stock et de ventes. '
                          'Ces données sont chiffrées et ne sont jamais revendues à des tiers. '
                          'Vous conservez l\'intégralité de la propriété de vos données commerciales.',
                    ),
                    _TermsSection(
                      title: '5. Mode hors-ligne',
                      content:
                          'Keevo fonctionne en mode hors-ligne. Les données sont synchronisées '
                          'dès qu\'une connexion internet est disponible. '
                          'En cas de conflit de données, la dernière modification enregistrée prévaut.',
                    ),
                    _TermsSection(
                      title: '6. Limitation de responsabilité',
                      content:
                          'Keevo ne peut être tenu responsable des pertes de données liées à '
                          'une panne d\'appareil ou à une désinstallation sans sauvegarde préalable. '
                          'Nous recommandons d\'activer la synchronisation cloud régulièrement.',
                    ),
                    _TermsSection(
                      title: '7. Modifications des conditions',
                      content:
                          'Nous pouvons modifier ces conditions à tout moment. '
                          'Vous serez notifié via l\'application. L\'utilisation continue du service '
                          'après modification vaut acceptation des nouvelles conditions.',
                    ),
                    _TermsSection(
                      title: '8. Droit applicable',
                      content:
                          'Ces conditions sont régies par le droit camerounais. '
                          'Tout litige sera soumis à la juridiction compétente de Yaoundé, Cameroun.',
                    ),

                    const SizedBox(height: 8),
                    const Divider(),
                    const SizedBox(height: 8),

                    // Politique de confidentialité (résumé)
                    Text(
                      'Politique de confidentialité',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 12),
                    _TermsSection(
                      title: 'Données collectées',
                      content:
                          'Numéro de téléphone WhatsApp, données de stock (produits, prix, quantités), '
                          'historique des transactions, informations sur les boutiques.',
                    ),
                    _TermsSection(
                      title: 'Utilisation des données',
                      content:
                          'Vos données servent exclusivement à fournir et améliorer le service Keevo. '
                          'Nous n\'utilisons pas vos données à des fins publicitaires.',
                    ),
                    _TermsSection(
                      title: 'Sécurité',
                      content:
                          'Toutes les données sont chiffrées en transit (TLS 1.3) et au repos (AES-256). '
                          'Les mots de passe sont hachés avec bcrypt et ne sont jamais stockés en clair.',
                    ),
                    _TermsSection(
                      title: 'Vos droits',
                      content:
                          'Vous avez le droit d\'accéder, corriger ou supprimer vos données à tout moment '
                          'depuis les paramètres de l\'application ou en contactant notre support.',
                    ),

                    const SizedBox(height: 32),
                  ],
                ),
              ),
            ),

            // ── Zone d'acceptation ───────────────────────────────────
            Container(
              decoration: BoxDecoration(
                color: Theme.of(context).scaffoldBackgroundColor,
                border: Border(
                  top: BorderSide(
                    color: cs.outlineVariant.withAlpha(80),
                  ),
                ),
                boxShadow: [
                  BoxShadow(
                    color: cs.shadow.withAlpha(10),
                    blurRadius: 8,
                    offset: const Offset(0, -2),
                  ),
                ],
              ),
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Case 1 : CGU
                  _AcceptCheckbox(
                    value: _termsAccepted,
                    enabled: _hasScrolledToBottom,
                    label: 'J\'accepte les Conditions d\'utilisation de Keevo',
                    onChanged: (v) => setState(() => _termsAccepted = v ?? false),
                  ),
                  const SizedBox(height: 8),
                  // Case 2 : Politique de confidentialité
                  _AcceptCheckbox(
                    value: _privacyAccepted,
                    enabled: _hasScrolledToBottom,
                    label: 'J\'accepte la Politique de confidentialité',
                    onChanged:
                        (v) => setState(() => _privacyAccepted = v ?? false),
                  ),
                  const SizedBox(height: 16),
                  // Bouton
                  SizedBox(
                    height: 56,
                    child: FilledButton(
                      onPressed: _canProceed ? _accept : null,
                      style: FilledButton.styleFrom(
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(28),
                        ),
                        disabledBackgroundColor: cs.primary.withAlpha(50),
                      ),
                      child: Text(
                        _canProceed
                            ? 'J\'accepte et continuer'
                            : _hasScrolledToBottom
                                ? 'Cochez les cases pour continuer'
                                : 'Lisez les conditions pour continuer',
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
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
    );
  }
}

// ── Widgets privés ────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final VoidCallback onBack;
  const _Header({required this.onBack});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Row(
        children: [
          IconButton(
            icon: Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: cs.surfaceContainerHighest,
              ),
              child: Icon(Icons.arrow_back, size: 20, color: cs.onSurface),
            ),
            onPressed: onBack,
          ),
          const SizedBox(width: 8),
          Text(
            'Conditions légales',
            style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  color: cs.primary,
                ),
          ),
        ],
      ),
    );
  }
}

class _TermsSection extends StatelessWidget {
  final String title;
  final String content;

  const _TermsSection({required this.title, required this.content});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
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
          const SizedBox(height: 4),
          Text(
            content,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: cs.onSurfaceVariant,
                  height: 1.5,
                ),
          ),
        ],
      ),
    );
  }
}

class _AcceptCheckbox extends StatelessWidget {
  final bool value;
  final bool enabled;
  final String label;
  final ValueChanged<bool?> onChanged;

  const _AcceptCheckbox({
    required this.value,
    required this.enabled,
    required this.label,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: enabled ? () => onChanged(!value) : null,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Checkbox(
              value: value,
              onChanged: enabled ? onChanged : null,
              activeColor: cs.primary,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            Expanded(
              child: Text(
                label,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: enabled ? cs.onSurface : cs.onSurfaceVariant,
                      fontWeight:
                          enabled ? FontWeight.w500 : FontWeight.normal,
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
