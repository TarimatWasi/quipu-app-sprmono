package com.tarimatwasi.quipu.shared.web;

import java.util.Set;

/**
 * The only routes of the BFF that take a file as {@code multipart/form-data} (RF-03, RF-05, RF-15).
 * Every other state-changing route takes JSON only (ADR-006). The routes are exact paths: neither a
 * trailing slash nor a sub-path is an upload.
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

  /** The routes that take a voucher. */
  public static final Set<String> VOUCHERS =
      Set.of(ADMIN_PAYMENTS, GUEST_PAYMENTS, TEMPORARY_GUEST_PAYMENTS);

  /** Every route that takes a file. */
  public static final Set<String> ALL =
      Set.of(CONTRACTS, ADMIN_PAYMENTS, GUEST_PAYMENTS, TEMPORARY_GUEST_PAYMENTS);

  private UploadRoutes() {}
}
