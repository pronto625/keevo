import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../../../../core/extension/extension_actions.dart';
import '../../../../core/extension/extension_slot.dart';
import '../../domain/model/sale_status.dart';

/// SaleSuccessPage — full-screen confirmation after a sale is recorded.
///
/// Story 15.3: a completed sale with its id shows the post-sale actions of
/// the active modules ([kPosCheckoutPostSaleActions], e.g. « Facturer »).
/// Without any, the page returns to the POS by itself after
/// [autoReturnDelay]; with some, it stays and offers « Nouvelle vente ».
class SaleSuccessPage extends ConsumerStatefulWidget {
  final int totalAmount;
  final String saleStatus;

  /// P1 (v1s-12-10): the sale holds draft products left for the owner to
  /// validate (EMPLOYEE seller). The sale itself is recorded normally.
  final bool hasDraftsToValidate;

  /// Id of the recorded sale (the server keeps the device's id).
  final String? saleId;

  /// Time before the automatic return to the POS.
  static const autoReturnDelay = Duration(milliseconds: 1800);

  /// Longest extra wait for the post-sale actions still loading at
  /// [autoReturnDelay] before returning anyway.
  static const slotWait = Duration(seconds: 3);

  const SaleSuccessPage({
    super.key,
    required this.totalAmount,
    this.saleStatus = SaleStatus.completedValue,
    this.hasDraftsToValidate = false,
    this.saleId,
  });

  @override
  ConsumerState<SaleSuccessPage> createState() => _SaleSuccessPageState();
}

class _SaleSuccessPageState extends ConsumerState<SaleSuccessPage>
    with TickerProviderStateMixin {
  late final AnimationController _fadeCtrl;
  late final AnimationController _scaleCtrl;
  late final Animation<double> _fadeIn;
  late final Animation<double> _scale;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'XAF', decimalDigits: 0);

  @override
  void initState() {
    super.initState();
    HapticFeedback.mediumImpact();

    _fadeCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
    );
    _scaleCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _fadeIn = CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut);
    _scale = CurvedAnimation(parent: _scaleCtrl, curve: Curves.elasticOut);

    _fadeCtrl.forward();
    _scaleCtrl.forward();

    _autoReturn();
  }

  /// The post-sale slot is shown for a completed sale whose id is known.
  bool get _showsSlot =>
      widget.saleId != null &&
      widget.saleStatus == SaleStatus.completedValue;

  Future<void> _autoReturn() async {
    await Future<void>.delayed(SaleSuccessPage.autoReturnDelay);
    if (!mounted) return;
    if (_showsSlot && await _hasPostSaleActions()) {
      // Stays on screen: « Nouvelle vente » is kept from now on, even if the
      // actions disappear (module change), so the user is never trapped.
      if (mounted) setState(() => _returnCancelled = true);
      return;
    }
    if (mounted) context.go('/pos');
  }

  /// The automatic return was cancelled (post-sale actions shown).
  bool _returnCancelled = false;

  /// Whether the slot shows at least one action (waits up to
  /// [SaleSuccessPage.slotWait] for an answer still loading). A failure is
  /// « none »: the page returns to the POS as before.
  Future<bool> _hasPostSaleActions() async {
    try {
      final contributions = await ref
          .read(extensionContributionsProvider(kPosCheckoutPostSaleActions)
              .future)
          .timeout(SaleSuccessPage.slotWait);
      if (!mounted) return false;
      return renderableContributions(
              contributions, ref.read(extensionActionsProvider))
          .isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    _scaleCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isPending = widget.saleStatus == SaleStatus.pendingValidation.value;
    final hasActions = _returnCancelled ||
        _showsSlot &&
            visibleExtensionContributions(ref, kPosCheckoutPostSaleActions)
                .isNotEmpty;
    // Aplat sémantique plein (plus de dégradé) ; le vert est la teinte la
    // plus foncée de l'ancien dégradé pour garder le contraste du blanc. Sur
    // l'ambre, le blanc est illisible : texte et icône passent en onWarning.
    final fg = isPending ? AppTheme.onWarning : AppTheme.onBrand;
    return Scaffold(
      body: Container(
        color: isPending ? AppTheme.warning : AppTheme.successStrong,
        child: FadeTransition(
          opacity: _fadeIn,
          child: Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ScaleTransition(
                  scale: _scale,
                  child: Container(
                    width: 96,
                    height: 96,
                    decoration: BoxDecoration(
                      color: fg.withValues(alpha: 0.2),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.check_rounded,
                      size: 56,
                      color: fg,
                    ),
                  ),
                ),
                const SizedBox(height: 28),
                Text(
                  isPending ? 'Vente en attente' : 'Vente enregistrée !',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        color: fg,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.5,
                      ),
                ),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                  decoration: BoxDecoration(
                    color: fg.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(24),
                  ),
                  child: Text(
                    _currencyFormat.format(widget.totalAmount),
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                          color: fg,
                          fontWeight: FontWeight.w700,
                        ),
                  ),
                ),
                if (widget.hasDraftsToValidate && !isPending) ...[
                  const SizedBox(height: 16),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32),
                    child: Text(
                      'Produit(s) brouillon à valider par le propriétaire',
                      textAlign: TextAlign.center,
                      style: Theme.of(context)
                          .textTheme
                          .bodyMedium
                          ?.copyWith(color: fg, fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
                if (_showsSlot) ...[
                  const SizedBox(height: 32),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: ExtensionSlot(
                      pointId: kPosCheckoutPostSaleActions,
                      args: {'saleId': widget.saleId},
                      style: OutlinedButton.styleFrom(
                        foregroundColor: fg,
                        side: BorderSide(color: fg),
                        minimumSize: const Size(160, 48),
                      ),
                    ),
                  ),
                ],
                if (hasActions) ...[
                  const SizedBox(height: 16),
                  FilledButton(
                    key: const Key('new-sale'),
                    onPressed: () => context.go('/pos'),
                    style: FilledButton.styleFrom(
                      backgroundColor: fg,
                      foregroundColor: AppTheme.successStrong,
                      minimumSize: const Size(160, 48),
                    ),
                    child: const Text('Nouvelle vente'),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
