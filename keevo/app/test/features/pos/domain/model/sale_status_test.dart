import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/sale_status.dart';

void main() {
  test('wire values are unchanged', () {
    expect(SaleStatus.completed.value, 'COMPLETED');
    expect(SaleStatus.pendingValidation.value, 'PENDING_VALIDATION');
    expect(SaleStatus.cancelled.value, 'CANCELLED');
    expect(SaleStatus.completedValue, SaleStatus.completed.value);
    expect(SaleStatus.pendingValidationValue,
        SaleStatus.pendingValidation.value);
    expect(SaleStatus.cancelledValue, SaleStatus.cancelled.value);
  });

}
