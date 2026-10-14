package com.tarimatwasi.quipu.payment.domain;

/** The kinds of file a voucher can be (RF-15, RN-22), each with the type that is served. */
public enum VoucherType {
  JPEG("image/jpeg", "jpg"),
  PNG("image/png", "png"),
  PDF("application/pdf", "pdf");

  private final String contentType;
  private final String extension;

  VoucherType(String contentType, String extension) {
    this.contentType = contentType;
    this.extension = extension;
  }

  /** The type that is stored and served, derived from the content and never from the client. */
  public String contentType() {
    return contentType;
  }

  /** The extension of the fixed name a voucher is downloaded with. */
  public String extension() {
    return extension;
  }
}
