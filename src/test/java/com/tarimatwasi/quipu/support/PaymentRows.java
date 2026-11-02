package com.tarimatwasi.quipu.support;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A payment written by hand with its one voucher (TAR-239: the file lives in payment_proofs), for
 * the tests that need a payment in a given state without going through the use cases.
 */
public final class PaymentRows {

  private PaymentRows() {}

  /**
   * Inserts the payment and its voucher; a resolved one is reviewed by {@code reviewerId} now, a
   * rejected one with the reason «No coincide», an approved one verified at its amount.
   *
   * @param period the first day of the month, or {@code null} for a stay
   * @param createdDate the creation and submission date; {@code null} for now
   */
  public static void insert(
      JdbcTemplate jdbc,
      Long id,
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable String period,
      String amount,
      String status,
      @Nullable Long reviewerId,
      String recordedBy,
      @Nullable String createdDate,
      String fileKey,
      String contentType) {
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, stay_id, period, amount, verified_amount,"
            + " status, rejection_reason, reviewed_at, reviewed_by_id, recorded_by, created_date,"
            + " submitted_at) VALUES (?, ?, ?, ?, ?::date, ?::numeric,"
            + " CASE WHEN ? = 'APPROVED' THEN ?::numeric END, ?,"
            + " CASE WHEN ? IN ('REJECTED', 'SUPERSEDED') THEN 'No coincide' END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE ?::bigint END, ?,"
            + " COALESCE(?::timestamptz, now()), COALESCE(?::timestamptz, now()))",
        id,
        guestId,
        contractId,
        stayId,
        period,
        amount,
        status,
        amount,
        status,
        status,
        status,
        status,
        reviewerId,
        recordedBy,
        createdDate,
        createdDate);
    jdbc.update(
        "INSERT INTO payment_proofs (id, payment_id, kind, declared_amount, verified_amount,"
            + " file_key, original_name, content_type, size_bytes, uploaded_by)"
            + " VALUES (?, ?, 'VOUCHER', ?::numeric, CASE WHEN ? = 'APPROVED' THEN ?::numeric END,"
            + " ?, 'v.pdf', ?, 10, ?)",
        TestIds.next(),
        id,
        amount,
        status,
        amount,
        fileKey,
        contentType,
        recordedBy);
  }
}
