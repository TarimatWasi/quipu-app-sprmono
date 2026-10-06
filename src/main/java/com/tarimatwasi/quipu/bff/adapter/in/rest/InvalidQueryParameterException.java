package com.tarimatwasi.quipu.bff.adapter.in.rest;

/** A query parameter whose raw text does not have the documented shape. */
public class InvalidQueryParameterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String parameter;

  /** Creates the exception for the named parameter. */
  public InvalidQueryParameterException(String parameter) {
    super("Invalid query parameter: " + parameter);
    this.parameter = parameter;
  }

  /** The name of the parameter. */
  public String parameter() {
    return parameter;
  }
}
