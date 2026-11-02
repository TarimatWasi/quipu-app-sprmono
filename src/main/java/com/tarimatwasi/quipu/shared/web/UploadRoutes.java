package com.tarimatwasi.quipu.shared.web;

import java.util.Set;

/**
 * The only routes of the BFF that take a file as {@code multipart/form-data} (RF-03, RF-05, RF-15,
 * RN-37). Every other state-changing route takes JSON only (ADR-006). The routes of {@link #ALL}
 * are exact paths taken by POST: neither a trailing slash nor a sub-path is an upload. One more,
 * {@link #GUEST_DOCUMENT_REPLACEMENT}, is taken by PUT and carries two ids, so it is a pattern of
 * exactly those segments.
 */
public final class UploadRoutes {

  /** The contract of a guest with a contract (PDF, up to 10 MB). */
  public static final String CONTRACTS = "/bff/admin/contracts";

  /** A voucher registered by the administrator (JPG, PNG or PDF, up to 5 MB). */
  public static final String ADMIN_PAYMENTS = "/bff/admin/payments";

  /** A voucher uploaded by a guest with a contract. */
  public static final String GUEST_PAYMENTS = "/bff/guest/payments";

  /** The voucher uploaded by a temporary guest. */
  public static final String TEMPORARY_GUEST_PAYMENTS = "/bff/temporary-guest/payments";

  /** A document of the onboarding of a guest of either type (JPG, PNG or PDF, up to 5 MB). */
  public static final String GUEST_ONBOARDING_DOCUMENTS = "/bff/guest/onboarding/documents";

  /** One more document of a guest with a contract, from «Mis datos» (RN-37, TAR-242). */
  public static final String GUEST_DOCUMENTS = "/bff/guest/documents";

  /** One more document of a temporary guest, from «Mis datos» (RN-37, TAR-242). */
  public static final String TEMPORARY_GUEST_DOCUMENTS = "/bff/temporary-guest/documents";

  /** The routes that take a document of a guest by POST, during the onboarding or later. */
  public static final Set<String> GUEST_DOCUMENT_UPLOADS =
      Set.of(GUEST_ONBOARDING_DOCUMENTS, GUEST_DOCUMENTS, TEMPORARY_GUEST_DOCUMENTS);

  /**
   * An administrator replaces a document of a guest (RN-37), by PUT; a pattern whose two variables
   * are one segment each.
   */
  public static final String GUEST_DOCUMENT_REPLACEMENT =
      "/bff/admin/guests/{id}/documents/{documentId}";

  /**
   * An administrator deletes a document of a guest (RN-37), by POST. It takes JSON, not a file, but
   * it deletes personal data irreversibly, so it is under the same check of where the request comes
   * from as the uploads (ADR-006).
   */
  public static final String GUEST_DOCUMENT_DELETION = GUEST_DOCUMENT_REPLACEMENT + "/deletion";

  /** The routes that take a voucher. */
  public static final Set<String> VOUCHERS =
      Set.of(ADMIN_PAYMENTS, GUEST_PAYMENTS, TEMPORARY_GUEST_PAYMENTS);

  /** The administrator adds or corrects the proofs of a payment (TAR-239), by PUT. */
  public static final String ADMIN_PAYMENT_PROOFS = "/bff/admin/payments/{id}/proofs";

  /** A guest with a contract corrects its rejected payment and sends it again, by PUT. */
  public static final String GUEST_PAYMENT = "/bff/guest/payments/{id}";

  /** A temporary guest corrects the rejected payment of its stay and sends it again, by PUT. */
  public static final String TEMPORARY_GUEST_PAYMENT = "/bff/temporary-guest/payments/{id}";

  /**
   * The corrections of a payment (TAR-239): multipart by PUT, under the same checks as the uploads
   * (ADR-006); patterns whose variable is one segment.
   */
  public static final Set<String> PAYMENT_CORRECTIONS =
      Set.of(ADMIN_PAYMENT_PROOFS, GUEST_PAYMENT, TEMPORARY_GUEST_PAYMENT);

  /** Every exact route that takes a file by POST. */
  public static final Set<String> ALL =
      Set.of(
          CONTRACTS,
          ADMIN_PAYMENTS,
          GUEST_PAYMENTS,
          TEMPORARY_GUEST_PAYMENTS,
          GUEST_ONBOARDING_DOCUMENTS,
          GUEST_DOCUMENTS,
          TEMPORARY_GUEST_DOCUMENTS);

  private UploadRoutes() {}
}
