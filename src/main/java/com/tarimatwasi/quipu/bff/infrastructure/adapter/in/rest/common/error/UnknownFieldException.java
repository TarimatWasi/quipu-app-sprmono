package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A body field the contract does not define, where the schema is closed ({@code
 * additionalProperties: false}). Jackson ignores unknown fields by default, so the request records
 * of those schemas collect them with {@code @JsonAnySetter} and the controller refuses them.
 */
public class UnknownFieldException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the unknown field. */
  public UnknownFieldException(String field) {
    super("Unknown field: " + field);
    this.field = field;
  }

  /** The name of the unknown field. */
  public String field() {
    return field;
  }

  /** Refuses a form that carried a field or a file part outside the closed list of the contract. */
  public static void rejectAnyOutside(Set<String> declared, Collection<String> received) {
    for (String name : received) {
      if (!declared.contains(name)) {
        throw new UnknownFieldException(name);
      }
    }
  }

  /** Refuses the body if it carried any field the record does not declare. */
  public static void rejectAny(@Nullable Map<String, Object> unknown) {
    if (unknown != null && !unknown.isEmpty()) {
      throw new UnknownFieldException(unknown.keySet().iterator().next());
    }
  }
}
