/// PaymentModeEnum — payment methods supported in POS.
enum PaymentModeEnum {
  cash('CASH'),
  mobileMoney('MOBILE_MONEY');

  final String value;
  const PaymentModeEnum(this.value);
}
