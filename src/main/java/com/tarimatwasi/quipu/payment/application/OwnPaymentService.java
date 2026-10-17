package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.PeriodStatus;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import com.tarimatwasi.quipu.payment.domain.StayPayments.StayStatus;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.payment.domain.VoucherFile;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.in.NoActiveContractException;
import com.tarimatwasi.quipu.payment.port.in.NoActiveStayException;
import com.tarimatwasi.quipu.payment.port.in.NoStayYetException;
import com.tarimatwasi.quipu.payment.port.in.OnboardingRequiredException;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort.OwnGuest;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort.Standing;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.VoucherDraft;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.shared.file.FileNames;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OwnPaymentService implements OwnPaymentsUseCase {

  private final PaymentRepositoryPort payments;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final Clock clock;
  private final VoucherRecorder recorder;

  OwnPaymentService(
      PaymentRepositoryPort payments,
      PaymentFilePort files,
      PaymentGuestPort guests,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      Clock clock) {
    this.payments = payments;
    this.guests = guests;
    this.contracts = contracts;
    this.stays = stays;
    this.clock = clock;
    this.recorder = new VoucherRecorder(files);
  }

  // --- the state of the payments ---

  @Override
  @Transactional(readOnly = true)
  public List<PeriodView> accountStatus(Long guestId) {
    admitted(guestId, true);
    LocalDate today = BusinessDay.today(clock);
    List<PeriodView> periods = new ArrayList<>();
    for (ContractInfo contract : contracts.contractsOf(guestId)) {
      for (PeriodStatus period :
          contract.terms().statuses(payments.findAllByContract(contract.id()), today)) {
        periods.add(
            new PeriodView(
                period.period(),
                state(period.state()),
                period.dueDate(),
                contract.monthlyAmount(),
                contract.id(),
                period.paymentId(),
                period.rejectionReason()));
      }
    }
    return periods;
  }

  @Override
  @Transactional(readOnly = true)
  public StayStatusView stayStatus(Long guestId) {
    admitted(guestId, false);
    LocalDate today = BusinessDay.today(clock);
    StayTerms stay = stays.relevantStayOf(guestId, today).orElseThrow(NoStayYetException::new);
    StayStatus status =
        StayPayments.status(stay.endDate(), payments.findAllByStay(stay.id()), today);
    return new StayStatusView(
        stay.id(),
        stay.startDate(),
        stay.endDate(),
        state(status.state()),
        stay.agreedAmount(),
        status.paymentId(),
        status.rejectionReason());
  }

  private static ChargeState state(PeriodState state) {
    return ChargeState.valueOf(state.name());
  }

  // --- the vouchers ---

  @Override
  @Transactional
  public PaymentView uploadForPeriod(PeriodVoucher voucher) {
    Long guestId = voucher.guestId();
    OwnGuest guest = admitted(guestId, true);
    VoucherRecorder.checkAmountAndMethod(voucher.amount(), voucher.paymentMethod());
    requireActive(guest);
    LocalDate today = BusinessDay.today(clock);
    // RN-27: only a contract in force takes a voucher of the guest; an older one that ended is
    // settled by the administrator (direct payment). Of those in force, the one that charges the
    // period: a renewal loaded ahead of time is in force too but charges nothing yet.
    List<ContractInfo> inForce =
        contracts.contractsOf(guestId).stream().filter(c -> c.terms().activeOn(today)).toList();
    if (inForce.isEmpty()) {
      throw new NoActiveContractException();
    }
    YearMonth period = voucher.period();
    ContractInfo contract =
        inForce.stream()
            .filter(c -> c.terms().periods(today).contains(period))
            .max(Comparator.comparing(c -> c.terms().startDate()))
            .orElseThrow(() -> new InvalidPaymentException("period"));
    VoucherType type = typeOf(voucher.voucher());
    VoucherRecorder.rejectLive(payments.findLiveByPeriod(contract.id(), period));
    return store(
        voucher.voucher(),
        type,
        guestId,
        contract.id(),
        null,
        period,
        new Declared(voucher.amount(), voucher.paymentMethod()));
  }

  @Override
  @Transactional
  public PaymentView uploadForStay(StayVoucher voucher) {
    Long guestId = voucher.guestId();
    OwnGuest guest = admitted(guestId, false);
    VoucherRecorder.checkAmountAndMethod(voucher.amount(), voucher.paymentMethod());
    requireActive(guest);
    LocalDate today = BusinessDay.today(clock);
    StayTerms stay =
        StayPayments.voucherStay(
                stays.staysOf(guestId), id -> payments.findLiveByStay(id).isPresent(), today)
            .orElseThrow(NoActiveStayException::new);
    VoucherType type = typeOf(voucher.voucher());
    VoucherRecorder.rejectLive(payments.findLiveByStay(stay.id()));
    return store(
        voucher.voucher(),
        type,
        guestId,
        null,
        stay.id(),
        null,
        new Declared(voucher.amount(), voucher.paymentMethod()));
  }

  /** The amount and the method the guest declared. */
  private record Declared(BigDecimal amount, @Nullable String paymentMethod) {}

  private PaymentView store(
      VoucherUpload upload,
      VoucherType type,
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      Declared declared) {
    byte[] content = upload.content();
    return VoucherRecorder.view(
        recorder.store(
            content,
            type,
            key ->
                payments.insertPending(
                    new VoucherDraft(
                        guestId,
                        contractId,
                        stayId,
                        period,
                        declared.amount().setScale(2),
                        VoucherRecorder.blankToNull(declared.paymentMethod()),
                        key,
                        FileNames.sanitized(upload.originalName(), VoucherRecorder.DEFAULT_NAME),
                        type,
                        content.length))));
  }

  // --- who may ---

  /**
   * RN-09, RN-33: the route belongs to one type of guest, and none is used before the onboarding
   * ends. Both are checked before anything else, so that a guest of the other type learns nothing.
   */
  private OwnGuest admitted(Long guestId, boolean contractType) {
    OwnGuest guest = guests.ownGuest(guestId);
    if (guest.contractType() != contractType) {
      throw new OtherGuestTypeException();
    }
    if (guest.standing() == Standing.ONBOARDING) {
      throw new OnboardingRequiredException();
    }
    return guest;
  }

  /** RN-35: an inactive guest takes no new payment. */
  private static void requireActive(OwnGuest guest) {
    if (guest.standing() != Standing.ACTIVE) {
      throw new GuestNotActiveException(guest.id());
    }
  }

  private static VoucherType typeOf(VoucherUpload upload) {
    return VoucherFile.typeOf(upload.content()).orElseThrow(InvalidVoucherFileException::new);
  }
}
