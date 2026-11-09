package org.example.shared;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.math.BigDecimal;

/**
 * Formats amounts in the default currency.
 */
@Named("money")
@ApplicationScoped
public class MoneyFormatter {

    /** ISO code of the currency used when none is given. */
    public String getDefaultCurrency() {
        return "EUR";
    }

    /** Formats an amount with the default currency. */
    public String format(BigDecimal amount) {
        return amount + " " + getDefaultCurrency();
    }
}
