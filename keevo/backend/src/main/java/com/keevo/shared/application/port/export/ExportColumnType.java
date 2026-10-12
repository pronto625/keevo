package com.keevo.shared.application.port.export;

/**
 * Logical type of an export column (Story v1s-18-1). Renderers format cells from it.
 *
 * <ul>
 *   <li>{@link #TEXT} — {@code String}</li>
 *   <li>{@link #INTEGER} — {@code Long} / {@code Integer} (quantities, counts)</li>
 *   <li>{@link #AMOUNT_XAF} — integer amount in XAF ({@code Long} / {@code Integer} / {@code BigDecimal})</li>
 *   <li>{@link #DECIMAL} — {@code BigDecimal} (rates, percentages)</li>
 *   <li>{@link #BOOLEAN} — {@code Boolean}</li>
 *   <li>{@link #DATE} — {@code LocalDate} (or an {@code Instant}, converted to its WAT date)</li>
 *   <li>{@link #DATETIME} — {@code Instant}, rendered in WAT ({@code Africa/Douala})</li>
 * </ul>
 */
public enum ExportColumnType {
    TEXT,
    INTEGER,
    AMOUNT_XAF,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME
}
