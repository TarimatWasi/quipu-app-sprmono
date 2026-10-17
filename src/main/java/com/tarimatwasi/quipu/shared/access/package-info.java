/**
 * What the auth and guest modules share about the access of a guest (RF-11). It lives in {@code
 * shared} because the guest module already depends on the auth module (modules may not form a
 * cycle).
 */
@NullMarked
@NamedInterface("access")
package com.tarimatwasi.quipu.shared.access;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
