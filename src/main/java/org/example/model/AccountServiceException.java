package org.example.model;

public class AccountServiceException extends RuntimeException {
  public AccountServiceException(final String message) {
    super(message);
  }
}
