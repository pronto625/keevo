import 'package:flutter/widgets.dart';

/// Retour Android sur les onglets de la barre de navigation.
///
/// Les onglets sont ouverts avec `context.go(...)` : la pile ne contient que
/// l'onglet courant, donc un retour système fermait l'application dès qu'on
/// était sur Caisse / Catalogue / Rapports / Plus. Désormais :
///   * sur un onglet autre que le premier → on revient au premier onglet
///     (Dashboard pour le gérant, Caisse pour l'employé) ;
///   * sur le premier onglet → comportement inchangé (le système gère).
///
/// Les pages poussées au-dessus du shell ne sont pas concernées : seul le
/// `PopScope` de la route au sommet de la pile est consulté.
class ShellTabBackScope extends StatelessWidget {
  /// Index de l'onglet affiché.
  final int tabIndex;

  /// Ramène sur le premier onglet.
  final VoidCallback onGoToFirstTab;

  final Widget child;

  const ShellTabBackScope({
    required this.tabIndex,
    required this.onGoToFirstTab,
    required this.child,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: tabIndex == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        onGoToFirstTab();
      },
      child: child,
    );
  }
}
