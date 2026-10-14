import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../../domain/model/sale_status.dart';

/// SaleSuccessPage — full-screen confirmation after a sale is recorded.
class SaleSuccessPage extends StatefulWidget {
  final int totalAmount;
  final String saleStatus;

  const SaleSuccessPage({
    super.key,
    required this.totalAmount,
    this.saleStatus = SaleStatus.completedValue,
  });

  @override
  State<SaleSuccessPage> createState() => _SaleSuccessPageState();
}

class _SaleSuccessPageState extends State<SaleSuccessPage>
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

    Future.delayed(const Duration(milliseconds: 1800), () {
      if (mounted) context.go('/pos');
    });
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
              ],
            ),
          ),
        ),
      ),
    );
  }
}
