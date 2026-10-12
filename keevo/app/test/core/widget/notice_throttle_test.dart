import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/widget/notice_throttle.dart';

void main() {
  test('N concurrent reports within the window let one notice through', () {
    var now = DateTime(2026, 10, 12, 10);
    final throttle = NoticeThrottle(now: () => now);

    final shown = List.generate(5, (_) => throttle.tryAcquire());
    expect(shown.where((s) => s), hasLength(1));
    expect(shown.first, isTrue);

    now = now.add(const Duration(seconds: 9));
    expect(throttle.tryAcquire(), isFalse);
  });

  test('a new notice is allowed once the window has passed', () {
    var now = DateTime(2026, 10, 12, 10);
    final throttle = NoticeThrottle(now: () => now);
    expect(throttle.tryAcquire(), isTrue);

    now = now.add(const Duration(seconds: 10));
    expect(throttle.tryAcquire(), isTrue);
  });

  test('a clock moved backwards does not suppress the notice', () {
    var now = DateTime(2026, 10, 12, 10);
    final throttle = NoticeThrottle(now: () => now);
    expect(throttle.tryAcquire(), isTrue);

    now = now.subtract(const Duration(hours: 1));
    expect(throttle.tryAcquire(), isTrue);
  });
}
