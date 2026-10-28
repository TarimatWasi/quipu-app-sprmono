package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/** The BFF error body of the contract; {@code field} is left out when it is not a field error. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BffErrorResponse(String code, String message, @Nullable String field) {
  public BffErrorResponse(String code, String message) {
    this(code, message, null);
  }
}
