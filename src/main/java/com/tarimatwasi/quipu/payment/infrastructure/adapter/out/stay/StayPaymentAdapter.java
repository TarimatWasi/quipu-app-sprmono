package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.stay;

import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The payment module asks the guest module, through its exposed port, about a stay. */
@Component
public class StayPaymentAdapter implements PaymentStayPort {

  private final ManageStaysUseCase stays;
  private final OwnGuestUseCase ownGuests;

  StayPaymentAdapter(ManageStaysUseCase stays, OwnGuestUseCase ownGuests) {
    this.stays = stays;
    this.ownGuests = ownGuests;
  }

  @Override
  public List<StayTerms> staysOf(Long guestId) {
    return ownGuests.stays(guestId).stream().map(StayPaymentAdapter::terms).toList();
  }

  @Override
  public Optional<StayTerms> relevantStayOf(Long guestId, LocalDate today) {
    return ownGuests.relevantStay(guestId, today).map(StayPaymentAdapter::terms);
  }

  private static StayTerms terms(StayView stay) {
    return new StayTerms(stay.id(), stay.startDate(), stay.endDate(), stay.agreedAmount());
  }

  @Override
  public List<StayTerms> staysInForceOn(LocalDate day) {
    return stays.inForceOn(day).stream().map(StayPaymentAdapter::terms).toList();
  }

  @Override
  public List<Long> idsStartingIn(YearMonth month) {
    return stays.idsStartingIn(month);
  }

  @Override
  public StayInfo stayOf(Long guestId, Long stayId) {
    return new StayInfo(stays.get(guestId, stayId).id());
  }
}
