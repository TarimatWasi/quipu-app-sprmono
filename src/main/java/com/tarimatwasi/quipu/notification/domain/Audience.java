package com.tarimatwasi.quipu.notification.domain;

/**
 * Who reads a notification: every administrator (one shared inbox, each with its own read state) or
 * the one guest it is about.
 */
public enum Audience {
  ADMINISTRATORS,
  GUEST
}
