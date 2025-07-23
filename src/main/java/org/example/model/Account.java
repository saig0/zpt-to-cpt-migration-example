package org.example.model;

public record Account(String id, String userName, String email, boolean subscribeToNewsletter, String activationCode) {}
