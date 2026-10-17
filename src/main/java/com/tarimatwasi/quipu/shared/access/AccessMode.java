package com.tarimatwasi.quipu.shared.access;

/** How the administrator hands the access to the guest (RN-32). */
public enum AccessMode {
  /** A single-use activation link. */
  LINK,
  /** A temporary password, replaced by the guest's own at the first login (RF-12). */
  TEMPORARY_PASSWORD
}
