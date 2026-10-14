import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/utils/percent_format.dart';

void main() {
  test('uses a decimal comma and a space before the sign', () {
    expect(formatPercentFr(25), '25,0 %');
    expect(formatPercentFr(12.345), '12,3 %');
    expect(formatPercentFr(-4.25, decimals: 2), '-4,25 %');
    expect(formatPercentFr(80, decimals: 0), '80 %');
  });
}
