package com.tarimatwasi.quipu.notification.domain;

/** The kind of resource a notification opens (contract: NotificationTarget.kind). */
public enum TargetKind {
  PAYMENT,
  GUEST,
  CONTRACT,
  FINE,
  REFUND,
  DOCUMENT
}
