/// SaleStatus — lifecycle status of a POS sale.
///
/// Names the sale-status wire/DB values. Adopted at the POS checkout path
/// (cart, record-sale notifier/use case, sale model, success page, day
/// closure summary); other POS screens and the SQL datasources still compare
/// the raw strings — the values are identical. The `*Value` constants exist for
/// const contexts (default parameters of const constructors), where
/// `SaleStatus.x.value` is not a constant expression.
///
/// - [completed]: sale validated (stock decremented)
/// - [pendingValidation]: employee sale awaiting OWNER validation
/// - [cancelled]: sale cancelled
enum SaleStatus {
  completed(SaleStatus.completedValue),
  pendingValidation(SaleStatus.pendingValidationValue),
  cancelled(SaleStatus.cancelledValue);

  const SaleStatus(this.value);

  /// Backend / Drift string value.
  final String value;

  static const String completedValue = 'COMPLETED';
  static const String pendingValidationValue = 'PENDING_VALIDATION';
  static const String cancelledValue = 'CANCELLED';
}
