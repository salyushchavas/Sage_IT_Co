package com.spire.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Checklist 5.3: amounts are US dollars everywhere ("$1,234.50"), and
 * money is checked in whole cents.
 */
public final class Money {

    private Money() {}

    /** "$1,234.50"; "—" when there's no amount. */
    public static String usd(BigDecimal amount) {
        if (amount == null) return "—";
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        return f.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** The amount in whole cents; refuses fractions of a cent. */
    public static long cents(BigDecimal amount) {
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amounts can have at most two decimal places (cents).");
        }
    }

    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
