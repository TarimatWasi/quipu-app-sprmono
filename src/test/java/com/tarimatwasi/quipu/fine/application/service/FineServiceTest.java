package com.tarimatwasi.quipu.fine.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.fine.application.port.in.FineGuestNotFoundException;
import com.tarimatwasi.quipu.fine.application.port.in.FineNotFoundException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineDescriptionException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineMonthException;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineCommand;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineView;
import com.tarimatwasi.quipu.fine.application.port.out.FineGuestMissingException;
import com.tarimatwasi.quipu.fine.application.port.out.FineGuestPort;
import com.tarimatwasi.quipu.fine.application.port.out.FineRepositoryPort;
import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FineServiceTest {

  private static final Long ID = TestIds.next();
  private static final Long GUEST = TestIds.next();
  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
  private static final BigDecimal AMOUNT = new BigDecimal("15.50");
  private static final Fine NOISE = new Fine(ID, GUEST, AMOUNT, SEPTEMBER, "Ruido");
  private static final FineView NOISE_VIEW = new FineView(ID, GUEST, AMOUNT, SEPTEMBER, "Ruido");

  @Mock FineRepositoryPort repository;
  @Mock FineGuestPort guests;

  private FineService service() {
    return new FineService(repository, guests);
  }

  private static FineCommand command(BigDecimal amount, @Nullable String description) {
    return new FineCommand(GUEST, amount, SEPTEMBER, description);
  }

  @Test
  void createsAFineForAnExistingGuestWithTheDescriptionStripped() {
    when(guests.exists(GUEST)).thenReturn(true);
    when(repository.insert(GUEST, AMOUNT, SEPTEMBER, "Ruido")).thenReturn(NOISE);

    assertThat(service().create(command(AMOUNT, "  Ruido "))).isEqualTo(NOISE_VIEW);
  }

  @Test
  void aBlankDescriptionMeansNone() {
    when(guests.exists(GUEST)).thenReturn(true);
    when(repository.insert(GUEST, AMOUNT, SEPTEMBER, null))
        .thenReturn(new Fine(ID, GUEST, AMOUNT, SEPTEMBER, null));

    assertThat(service().create(command(AMOUNT, "   ")).description()).isNull();
  }

  @Test
  void anInvalidAmountIsRejectedBeforeAnythingIsReadOrWritten() {
    for (var amount : new String[] {"0", "-1", "10.001", "100000000.00"}) {
      assertThatThrownBy(() -> service().create(command(new BigDecimal(amount), null)))
          .as(amount)
          .isInstanceOf(InvalidFineException.class);
    }
    verify(guests, never()).exists(any());
    verify(repository, never()).insert(any(), any(), any(), any());
  }

  @Test
  void aMonthOfYearZeroIsRejectedOnCreateAndList() {
    assertThatThrownBy(
            () -> service().create(new FineCommand(GUEST, AMOUNT, YearMonth.of(0, 1), null)))
        .isInstanceOf(InvalidFineMonthException.class);
    assertThatThrownBy(() -> service().list(YearMonth.of(0, 1)))
        .isInstanceOf(InvalidFineMonthException.class);
    verify(guests, never()).exists(any());
    verify(repository, never()).insert(any(), any(), any(), any());
    verify(repository, never()).findByMonth(any());
  }

  @Test
  void aDescriptionOverFiveHundredCharactersIsRejected() {
    assertThatThrownBy(() -> service().create(command(AMOUNT, "x".repeat(501))))
        .isInstanceOf(InvalidFineDescriptionException.class);
    assertThatThrownBy(() -> service().create(command(AMOUNT, "😀".repeat(501))))
        .isInstanceOf(InvalidFineDescriptionException.class);
    verify(repository, never()).insert(any(), any(), any(), any());
  }

  @Test
  void fiveHundredCharactersCountedAsCodePointsAreAccepted() {
    var emojis = "😀".repeat(500);
    when(guests.exists(GUEST)).thenReturn(true);
    when(repository.insert(GUEST, AMOUNT, SEPTEMBER, emojis))
        .thenReturn(new Fine(ID, GUEST, AMOUNT, SEPTEMBER, emojis));

    assertThat(service().create(command(AMOUNT, emojis)).description()).isEqualTo(emojis);
  }

  @Test
  void aGuestThatDoesNotExistIsNotFoundAndNothingIsWritten() {
    when(guests.exists(GUEST)).thenReturn(false);

    assertThatThrownBy(() -> service().create(command(AMOUNT, null)))
        .isInstanceOf(FineGuestNotFoundException.class);
    verify(repository, never()).insert(any(), any(), any(), any());
  }

  /** A guest id that was never issued comes as none: a 404, but only once the data is valid. */
  @Test
  void anUnknownGuestIsNotFoundAfterTheDataIsChecked() {
    assertThatThrownBy(() -> service().create(new FineCommand(null, AMOUNT, SEPTEMBER, null)))
        .isInstanceOf(FineGuestNotFoundException.class);
    assertThatThrownBy(
            () -> service().create(new FineCommand(null, BigDecimal.ZERO, SEPTEMBER, null)))
        .isInstanceOf(InvalidFineException.class);
    verify(guests, never()).exists(any());
    verify(repository, never()).insert(any(), any(), any(), any());
  }

  /** The guest is gone between the check and the insert: the foreign key answers the same 404. */
  @Test
  void aGuestRefusedByTheForeignKeyIsNotFoundToo() {
    when(guests.exists(GUEST)).thenReturn(true);
    when(repository.insert(GUEST, AMOUNT, SEPTEMBER, null))
        .thenThrow(new FineGuestMissingException(new IllegalStateException("fk")));

    assertThatThrownBy(() -> service().create(command(AMOUNT, null)))
        .isInstanceOf(FineGuestNotFoundException.class);
  }

  @Test
  void deletesAFine() {
    when(repository.delete(ID)).thenReturn(true);

    service().delete(ID);

    verify(repository).delete(ID);
  }

  @Test
  void deletingAMissingFineIsNotFound() {
    when(repository.delete(ID)).thenReturn(false);

    assertThatThrownBy(() -> service().delete(ID)).isInstanceOf(FineNotFoundException.class);
  }

  @Test
  void listsTheFinesOfTheMonth() {
    var other = new Fine(TestIds.next(), GUEST, BigDecimal.TEN, SEPTEMBER, null);
    when(repository.findByMonth(SEPTEMBER)).thenReturn(List.of(NOISE, other));

    assertThat(service().list(SEPTEMBER))
        .containsExactly(
            NOISE_VIEW, new FineView(other.id(), GUEST, BigDecimal.TEN, SEPTEMBER, null));
  }

  /** RF-18: the fines of the month add up in the base, and a month of year 0000 is refused. */
  @Test
  void theTotalOfTheMonthIsTheSumTheBaseAnswers() {
    when(repository.sumByMonth(SEPTEMBER)).thenReturn(new BigDecimal("25.30"));

    assertThat(service().totalOf(SEPTEMBER)).isEqualByComparingTo("25.30");
    assertThatThrownBy(() -> service().totalOf(YearMonth.of(0, 1)))
        .isInstanceOf(InvalidFineMonthException.class);
  }
}
