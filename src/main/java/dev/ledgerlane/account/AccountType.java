package dev.ledgerlane.account;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AccountType {
    ASSET("asset", true),
    EXPENSE("expense", true),
    LIABILITY("liability", false),
    REVENUE("revenue", false),
    EQUITY("equity", false);

    private final String value;
    private final boolean debitNormal;

    AccountType(String value, boolean debitNormal) {
        this.value = value;
        this.debitNormal = debitNormal;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean debitNormal() {
        return debitNormal;
    }

    public static AccountType fromValue(String value) {
        for (AccountType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown account type " + value);
    }
}
