import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Keevo Material 3 — Indigo Sky palette (UX Design Spec)
///
/// colorPrimary:    #3B5BDB  (indigo royal)    — boutons, nav active
/// primaryGradient: #3B5BDB → #4DABF7          — AppBar, headers
/// colorSecondary:  #FF6B6B  (corail chaud)    — accent CTA, prix
/// colorSuccess:    #51CF66  (vert lime)        — confirmations, sync OK
/// colorWarning:    #FCC419  (ambre doré)       — stock bas, brouillons
/// colorError:      #FA5252  (rouge rubis)      — ruptures, erreurs
///                  #FF6B6B en sombre, avec onError #0D1B2A (v1s-16-17)
/// darkSurface:     #0D1B2A  (navy nuit)
///
/// Aplats de feedback (v1s-16-17) : tout aplat FIXE sous texte/icône blanc
/// ([onBrand]) utilise une teinte « strong » AA — [successStrong],
/// [errorStrong], [warningStrong] — jamais [success]/[errorColor] (2.0:1 /
/// 3.28:1 sous blanc). Les SnackBars ont un aplat fixe ([snackBarNeutral] /
/// [snackBarNeutralDark] par défaut) et un contenu [onBrand] via le thème.
class AppTheme {
  AppTheme._();

  // ── Core palette ──────────────────────────────────────────────────────
  static const Color primary = Color(0xFF3B5BDB);
  static const Color primaryGradientEnd = Color(0xFF4DABF7);
  static const Color secondary = Color(0xFFFF6B6B);
  static const Color success = Color(0xFF51CF66);
  /// Couleur de texte/icône accessible sur fond [success] (vert lime).
  static const Color onSuccess = Color(0xFF1A4731);
  static const Color warning = Color(0xFFFCC419);
  /// Couleur de texte/icône accessible sur fond [warning] (ambre).
  static const Color onWarning = Color(0xFF4A2C00);
  static const Color errorColor = Color(0xFFFA5252);
  /// Blanc sur [errorColor] : 3.28:1, icônes seulement (pas de texte). Les
  /// aplats rouges fixes sous texte blanc utilisent [errorStrong] (v1s-16-17).
  static const Color onError = Color(0xFFFFFFFF);
  static const Color darkSurface = Color(0xFF0D1B2A);

  /// Rouge soutenu (Open Color red-9) : aplats d'erreur / danger FIXES
  /// (SnackBars d'erreur, boutons destructifs, badges) sous [onBrand]
  /// (5.46:1). [errorColor] reste la couleur d'indicateur/texte.
  static const Color errorStrong = Color(0xFFC92A2A);

  /// `cs.error` en mode sombre (UX spec, colonne sombre).
  static const Color errorDark = Color(0xFFFF6B6B);

  /// `cs.onError` en mode sombre : texte foncé sur [errorDark] (6.27:1).
  static const Color onErrorDark = darkSurface;

  /// Aplat par défaut des SnackBars (clair) — texte [onBrand] ≥ 8.6:1.
  static const Color snackBarNeutral = Color(0xFF343A40);

  /// Aplat par défaut des SnackBars (sombre) — ardoise distincte de la
  /// surface navy, texte [onBrand] ≥ 8.6:1.
  static const Color snackBarNeutralDark = Color(0xFF3B4D63);

  /// Texte/icône sur un aplat de marque FIXE (ne change pas avec la
  /// luminosité) : [primary], [primaryGradient], [primaryGradientDark],
  /// [successGradient], aplats [successStrong]/[errorStrong]/
  /// [warningStrong]/[darkSurface]/[violetDeep], SnackBars, [avatarPalette].
  /// Sur `cs.primary`, utiliser `cs.onPrimary`.
  static const Color onBrand = Color(0xFFFFFFFF);

  /// Vert soutenu (texte/icône sur fond clair, aplats avec [onBrand]).
  /// Fusionne #2B8A3E / #37B24D / #40C057 / #22C55E ; assombri pour tenir
  /// AA (≥ 4.5:1) en texte sur blanc ET sous [onBrand].
  static const Color successStrong = Color(0xFF2B7A3B);

  /// Orange soutenu (texte/icône d'attention sur fond clair).
  /// Fusionne #E67700 / #E65100 / #F08C00 / #F59E0B / #F9A825 ; assombri
  /// pour tenir AA (≥ 4.5:1) en texte sur blanc.
  static const Color warningStrong = Color(0xFFA85500);

  /// Violet profond (accents secondaires : speed-dial, rapports).
  static const Color violet = Color(0xFF7048E8);
  static const Color violetDeep = Color(0xFF5F3DC4);

  // ── Classement (médailles) ───────────────────────────────────────────
  static const Color medalGold = Color(0xFFFFD700);
  static const Color medalSilver = Color(0xFFC0C0C0);
  static const Color medalBronze = Color(0xFFCD7F32);

  // ── Avatars produits (initiales) — aplats fixes, texte [onBrand] ─────
  static const List<Color> avatarPalette = [
    primary, // Indigo
    Color(0xFF1098AD), // Cyan
    Color(0xFF37B24D), // Green
    Color(0xFFE8590C), // Orange
    Color(0xFFF03E3E), // Red
    Color(0xFF7048E8), // Violet
    Color(0xFFAE3EC9), // Grape
    Color(0xFF1C7ED6), // Blue
  ];

  /// Initiales lisibles (AA) sur la pastille `accent` à 15 % : l'accent
  /// assombri en clair, éclairci en sombre.
  static Color avatarInitials(Color accent, Brightness brightness) =>
      Color.lerp(
        accent,
        brightness == Brightness.dark ? onBrand : const Color(0xFF000000),
        0.35,
      )!;

  // ── Dark scheme tokens (UX spec) ─────────────────────────────────────
  static const Color primaryDark = Color(0xFF4DABF7);
  static const Color secondaryDark = Color(0xFFFF8787);
  static const Color onPrimaryDark = Color(0xFF0D1B2A);
  static const Color primaryContainerDark = Color(0xFF1B3A5C);
  static const Color onSurfaceDark = Color(0xFFF0F4F8);
  static const Color onSurfaceVariantDark = Color(0xFFADB5BD);
  static const Color surfaceVariantDark = Color(0xFF1B2838);

  // ── Neutral greys (semantic) ──────────────────────────────────────────
  static const Color grey600 = Color(0xFF868E96);
  static const Color grey500 = Color(0xFFADB5BD);
  static const Color grey200 = Color(0xFFE9ECEF);
  static const Color grey100 = Color(0xFFF1F3F5);
  static const Color grey50 = Color(0xFFF8F9FA);

  // ── Scaffold backgrounds ──────────────────────────────────────────────
  static const Color scaffoldLight = Color(0xFFF8F9FA);
  // dark mode uses colorScheme.surface (darkSurface)

  // ── Gradient builders ─────────────────────────────────────────────────
  static LinearGradient get primaryGradient => const LinearGradient(
        colors: [primary, primaryGradientEnd],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  static LinearGradient get primaryGradientDark => const LinearGradient(
        colors: [Color(0xFF1A365D), Color(0xFF0D1B2A)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  static LinearGradient get successGradient => const LinearGradient(
        colors: [success, Color(0xFF37B24D)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  // ── Settings icon color pairs (light mode) ────────────────────────────
  // Grouped by hue family so the settings page looks intentional.
  static const Color iconBlueBg = Color(0xFFD0EBFF);
  static const Color iconBlue = Color(0xFF339AF0);
  static const Color iconTealBg = Color(0xFFD3F9D8);
  static const Color iconTeal = Color(0xFF0CA678);
  static const Color iconAmberBg = Color(0xFFFFF9DB);
  static const Color iconAmber = Color(0xFFF59F00);
  static const Color iconPurpleBg = Color(0xFFF3E5F5);
  static const Color iconPurple = Color(0xFF9B59B6);
  static const Color iconIndigoBg = Color(0xFFF0E6FF);
  static const Color iconIndigo = Color(0xFF6F42C1);
  static const Color iconOrangeBg = Color(0xFFFFF7ED);
  static const Color iconOrange = Color(0xFFEA580C);
  static const Color iconGreyBg = Color(0xFFF1F3F5);
  static const Color iconGrey = Color(0xFF868E96);
  static const Color iconCyanBg = Color(0xFFE0F2FE);
  static const Color iconCyan = Color(0xFF0EA5E9);
  static const Color iconRedBg = Color(0xFFFFE3E3);

  // ── Shared radii ──────────────────────────────────────────────────────
  static const double radiusS = 8;
  static const double radiusM = 12;
  static const double radiusL = 16;
  static const double radiusXL = 24;
  static const double radiusXXL = 32;

  // ── Soft/Modern tokens ───────────────────────────────────────────────
  // Extension of "Indigo Sky" for the soft/modern/épuré rebrand
  // (Onboarding, 2026-09-12 — see _bmad-output/planning-artifacts/
  // ux-design-specification.md §Onboarding Rebrand). Same brand hue,
  // lower-saturation surfaces, more negative space. Rolled out
  // screen-by-screen; not yet wired into the global ThemeData.
  static const Color primarySoftTint = Color(0xFFEEF1FE);
  static const Color primarySoftTintDark = Color(0xFF16223A);

  /// Soft "mesh" wash — visible at the top, resolved to solid white by
  /// ~55% so list/form content underneath reads on a clean surface.
  /// Revised 2026-09-15: v1 was nearly imperceptible (`#F7F8FF`→white);
  /// this version keeps the same brand hues but with enough presence to
  /// read as an intentional gradient, matching the reference direction.
  static LinearGradient get softBackgroundGradient => const LinearGradient(
        colors: [
          Color(0xFFC9D6FA), // indigo tint
          Color(0xFFDCEAFB), // sky tint
          Colors.white,
        ],
        stops: [0.0, 0.32, 0.62],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  static LinearGradient get softBackgroundGradientDark => const LinearGradient(
        colors: [Color(0xFF16223A), Color(0xFF0F1D30), Color(0xFF0D1B2A)],
        stops: [0.0, 0.32, 0.62],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );

  // ── Helpers ───────────────────────────────────────────────────────────

  /// Semantic status color based on label.
  static Color statusColor(String status) => switch (status) {
        'SUCCESS' || 'OK' => success,
        'FAILED' => errorColor,
        'WARN' => warning,
        _ => grey600,
      };

  /// Pale brand tint for soft pastilles/cards. Widgets must resolve it
  /// through here rather than using [primarySoftTint] directly: the light
  /// value is opaque, so it stays pale in dark mode and makes the dark
  /// theme's light text unreadable on top of it.
  static Color softTint(Brightness brightness) =>
      brightness == Brightness.dark ? primarySoftTintDark : primarySoftTint;

  /// Single diffuse shadow shared by every soft card, so elevation reads
  /// the same across screens.
  static List<BoxShadow> softShadow(ColorScheme cs) => [
        BoxShadow(
          color: cs.shadow.withValues(alpha: 0.06),
          blurRadius: 16,
          offset: const Offset(0, 4),
        ),
      ];

  /// Soft card surface: plain surface color, large radius, diffuse shadow.
  static BoxDecoration softCardDecoration(
    ColorScheme cs, {
    double radius = radiusL,
  }) =>
      BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(radius),
        boxShadow: softShadow(cs),
      );

  static TextTheme _interTextTheme(TextTheme base) =>
      GoogleFonts.interTextTheme(base);

  // ── Component themes (shared) ─────────────────────────────────────────

  static InputDecorationTheme _inputDecoration(ColorScheme cs) =>
      InputDecorationTheme(
        // Long French validation messages wrap instead of being cut with an
        // ellipsis on narrow phones.
        errorMaxLines: 3,
        filled: true,
        fillColor: cs.surfaceContainerHighest.withAlpha(80),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.outlineVariant.withAlpha(100)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusM),
          borderSide: BorderSide(color: cs.error, width: 1.5),
        ),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        hintStyle: TextStyle(color: cs.onSurface.withAlpha(120)),
      );

  static CardThemeData _cardTheme(ColorScheme cs) => CardThemeData(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusL),
        ),
        color: cs.surface,
        surfaceTintColor: Colors.transparent,
      );

  static AppBarTheme _appBarTheme(ColorScheme cs) => AppBarTheme(
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        backgroundColor: Colors.transparent,
        foregroundColor: cs.onSurface,
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w600,
          color: cs.onSurface,
        ),
      );

  // Soft/modern rebrand (2026-09-16) — elevation moved from the bar itself
  // to a soft shadow drawn by MainShell's wrapping Container (flatter,
  // less harsh than Material's default drop shadow); indicator uses the
  // pale brand tint instead of the more saturated seed-generated
  // `primaryContainer`, matching the rest of the rebranded flow.
  static NavigationBarThemeData _navBarTheme(ColorScheme cs) =>
      NavigationBarThemeData(
        elevation: 0,
        indicatorColor: softTint(cs.brightness),
        backgroundColor: cs.surface,
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            color: selected ? cs.primary : cs.onSurfaceVariant,
          );
        }),
      );

  static DialogThemeData _dialogTheme(ColorScheme cs) => DialogThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusXL),
        ),
        backgroundColor: cs.surface,
      );

  static BottomSheetThemeData _bottomSheetTheme(ColorScheme cs) =>
      BottomSheetThemeData(
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        backgroundColor: cs.surface,
        showDragHandle: true,
      );

  /// v1s-16-17 — every SnackBar sits on a FIXED fill (neutral by default,
  /// AA tone fills otherwise) with [onBrand] content, so text, icon, action
  /// and close icon agree on every fill in both modes. [textTheme] must be
  /// the Inter text theme (a bare TextStyle would drop font and size).
  static SnackBarThemeData _snackBarTheme(
    ColorScheme cs,
    TextTheme textTheme,
  ) =>
      SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusM),
        ),
        backgroundColor: cs.brightness == Brightness.dark
            ? snackBarNeutralDark
            : snackBarNeutral,
        contentTextStyle: textTheme.bodyMedium?.copyWith(color: onBrand),
        actionTextColor: onBrand,
        closeIconColor: onBrand,
      );

  // ── Light theme ───────────────────────────────────────────────────────

  static ThemeData light() {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.light,
    ).copyWith(
      primary: primary,
      onPrimary: onBrand,
      secondary: secondary,
      onSecondary: Colors.white,
      error: errorColor,
      onError: onError,
    );
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: scaffoldLight,
    );
    final textTheme = _interTextTheme(base.textTheme);
    return base.copyWith(
      textTheme: textTheme,
      primaryTextTheme: _interTextTheme(base.primaryTextTheme),
      inputDecorationTheme: _inputDecoration(colorScheme),
      cardTheme: _cardTheme(colorScheme),
      appBarTheme: _appBarTheme(colorScheme),
      navigationBarTheme: _navBarTheme(colorScheme),
      dialogTheme: _dialogTheme(colorScheme),
      bottomSheetTheme: _bottomSheetTheme(colorScheme),
      snackBarTheme: _snackBarTheme(colorScheme, textTheme),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant.withAlpha(80),
        thickness: 0.5,
        space: 1,
      ),
    );
  }

  // ── Dark theme ────────────────────────────────────────────────────────

  static ThemeData dark() {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.dark,
    ).copyWith(
      primary: primaryDark, // bleu ciel en dark mode (UX spec)
      onPrimary: onPrimaryDark,
      primaryContainer: primaryContainerDark,
      secondary: secondaryDark, // corail clair dark mode (UX spec)
      onSecondary: Colors.black,
      // v1s-16-17 — UX-spec dark error; fixed red fills use [errorStrong].
      error: errorDark,
      onError: onErrorDark,
      surface: darkSurface,
      onSurface: onSurfaceDark,
      onSurfaceVariant: onSurfaceVariantDark,
      // M3 successor of `surfaceVariant` (UX spec "surfaceVariant").
      surfaceContainerHighest: surfaceVariantDark,
    );
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
    );
    final textTheme = _interTextTheme(base.textTheme);
    return base.copyWith(
      textTheme: textTheme,
      primaryTextTheme: _interTextTheme(base.primaryTextTheme),
      inputDecorationTheme: _inputDecoration(colorScheme),
      cardTheme: _cardTheme(colorScheme),
      appBarTheme: _appBarTheme(colorScheme),
      navigationBarTheme: _navBarTheme(colorScheme),
      dialogTheme: _dialogTheme(colorScheme),
      bottomSheetTheme: _bottomSheetTheme(colorScheme),
      snackBarTheme: _snackBarTheme(colorScheme, textTheme),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant.withAlpha(80),
        thickness: 0.5,
        space: 1,
      ),
    );
  }
}

/// Extension to access AppTheme semantic colors relative to the current
/// brightness without importing AppTheme constants everywhere.
extension KeevoColors on ColorScheme {
  bool get _isDark => brightness == Brightness.dark;

  Color get success => _isDark ? const Color(0xFF69DB7C) : AppTheme.success;
  Color get warning => _isDark ? const Color(0xFFFFD43B) : AppTheme.warning;
  Color get successContainer =>
      _isDark ? const Color(0xFF1B4332) : const Color(0xFFD3F9D8);
  Color get warningContainer =>
      _isDark ? const Color(0xFF462F00) : const Color(0xFFFFF9DB);
  Color get errorContainer_ =>
      _isDark ? const Color(0xFF5C1A1A) : const Color(0xFFFFE3E3);

  /// Texte/icône lisible (AA) sur [warningContainer].
  Color get onWarningContainer =>
      _isDark ? const Color(0xFFFFE8A3) : AppTheme.onWarning;

  /// Texte/icône lisible (AA) sur [errorContainer_].
  Color get onErrorContainer_ =>
      _isDark ? const Color(0xFFFFC9C9) : const Color(0xFF8C1D1D);

  /// Vert de statut lisible : [AppTheme.successStrong] en clair.
  /// Teinte de texte/icône, jamais un aplat sous onBrand (utiliser [AppTheme.successStrong]).
  Color get successStrong => _isDark ? success : AppTheme.successStrong;

  /// Orange de statut lisible : [AppTheme.warningStrong] en clair.
  /// Teinte de texte/icône, jamais un aplat sous onBrand (utiliser [AppTheme.warningStrong]).
  Color get warningStrong => _isDark ? warning : AppTheme.warningStrong;

  /// Light scaffold‐grey that adapts for dark mode.
  Color get scaffoldBg => _isDark ? surface : AppTheme.scaffoldLight;

  /// Card/container background that adapts cleanly.
  Color get cardBg =>
      _isDark ? surfaceContainerHigh : Colors.white;

  /// Muted foreground (secondary text, icons).
  Color get muted => onSurface.withAlpha(150);

  /// Very muted foreground (tertiary labels, hints).
  Color get dimmed => onSurface.withAlpha(100);
}
