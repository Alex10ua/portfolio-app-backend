package com.dev.alex.Model.Enums;

/**
 * A user's verdict on one dividend payment. Only overrides are stored: a payment
 * nobody touched is upcoming, awaiting or received purely by its pay date.
 */
public enum DividendReceiptStatus {
    RECEIVED, MISSING
}
