package com.daigan.record.data.model

/**
 * How the worker signs in to the customer's game account.
 *
 * Verification codes are never persisted; only the chosen method is stored. QR code
 * login records the method only — the app performs no scanning or authentication.
 */
enum class LoginMethod {
    PASSWORD,
    VERIFICATION_CODE,
    QR_CODE,
}
