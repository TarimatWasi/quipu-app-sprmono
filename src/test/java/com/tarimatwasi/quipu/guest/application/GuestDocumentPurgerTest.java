package com.tarimatwasi.quipu.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** TAR-187 (RN-37): an object without a row stays pending until the store removes it. */
@ExtendWith(MockitoExtension.class)
class GuestDocumentPurgerTest {

  private static final String KEY = "guest-documents/a";
  private static final String OTHER = "guest-documents/b";

  @Mock GuestDocumentActionRepositoryPort records;
  @Mock GuestDocumentFilePort files;

  private GuestDocumentPurger purger() {
    return new GuestDocumentPurger(records, files);
  }

  @Test
  void aScheduledObjectIsRecorded() {
    purger().schedule(KEY);

    verify(records).addPurge(KEY);
  }

  @Test
  void theRemovedObjectsAreForgottenAndTheOthersStayPending() {
    when(records.pendingPurges(100)).thenReturn(List.of(KEY, OTHER));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(KEY);

    assertThat(purger().purgePending()).isOne();

    verify(files).delete(OTHER);
    verify(records).removePurge(OTHER);
    verify(records, never()).removePurge(KEY);
  }

  @Test
  void withNothingPendingNothingIsRemoved() {
    when(records.pendingPurges(100)).thenReturn(List.of());

    assertThat(purger().purgePending()).isZero();
  }
}
