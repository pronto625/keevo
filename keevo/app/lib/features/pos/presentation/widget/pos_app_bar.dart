import 'package:flutter/material.dart';

/// AppBar neutre des écrans secondaires de la caisse : fond surface, titre
/// aligné à gauche en w700, flèche arrondie. Le bouton retour n'apparaît que
/// là où l'AppBar par défaut en afficherait un, et fait le même `maybePop`.
class PosAppBar extends StatelessWidget implements PreferredSizeWidget {
  final String title;
  final List<Widget>? actions;

  const PosAppBar({super.key, required this.title, this.actions});

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final canPop = ModalRoute.of(context)?.impliesAppBarDismissal ?? false;
    return AppBar(
      automaticallyImplyLeading: false,
      leading: canPop
          ? IconButton(
              icon: Icon(Icons.arrow_back_ios_new_rounded,
                  color: cs.onSurface, size: 20),
              tooltip: MaterialLocalizations.of(context).backButtonTooltip,
              onPressed: () => Navigator.maybePop(context),
            )
          : null,
      title: Text(
        title,
        style: theme.textTheme.titleLarge?.copyWith(
          fontSize: 18,
          color: cs.onSurface,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
        ),
      ),
      centerTitle: false,
      backgroundColor: cs.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      actions: actions,
    );
  }
}
