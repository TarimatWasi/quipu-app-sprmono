package com.tarimatwasi.quipu.payment.application.service;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.ProofKind;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the tests of {@link PaymentService} share: the ports as mocks, the service with the day
 * 2026-10-14 in Lima, and the payments, guests and contracts they read.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
abstract class PaymentServiceFixture {

  static final Long ADMIN = TestIds.next();
  static final Long GUEST = TestIds.next();
  static final Long CONTRACT = TestIds.next();
  static final Long ENVIRONMENT = TestIds.next();
  static final Long STAY = TestIds.next();
  static final Long PAYMENT = TestIds.next();
  static final Long PROOF = TestIds.next();

  /** The voucher of every payment of these tests, verified at its whole amount (RN-16). */
  static final Map<Long, BigDecimal> VERIFIED = Map.of(PROOF, new BigDecimal("800.00"));

  static final byte[] PDF = TestPdfs.plain();
  static final String KEY = "vouchers/abc";
  static final String URL = "https://r2.test/vouchers/abc?X-Amz-Signature=s";
  static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  static final Instant CREATED = Instant.parse("2026-10-10T15:00:00Z");

  // 2026-10-14 09:00 in Lima
  final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentFilePort files;
  @Mock PaymentGuestPort guests;
  @Mock PaymentContractPort contracts;
  @Mock PaymentEnvironmentPort environments;
  @Mock PaymentStayPort stays;
  @Mock RefundRepositoryPort refunds;
  @Mock com.tarimatwasi.quipu.payment.application.port.out.PaymentReviewerPort reviewers;

  PaymentService service;

  @BeforeEach
  void setUp() {
    service =
        new PaymentService(
            payments,
            files,
            contracts,
            new PaymentDirectory(guests, environments, stays, reviewers),
            refunds,
            clock,
            org.mockito.Mockito.mock(com.tarimatwasi.quipu.shared.changes.ChangeSignals.class));
  }

  static Payment payment(PaymentStatus status, @Nullable YearMonth period, @Nullable Long stay) {
    return new Payment(
        PAYMENT,
        GUEST,
        stay == null ? CONTRACT : null,
        stay,
        period,
        new BigDecimal("800.00"),
        status == PaymentStatus.APPROVED ? new BigDecimal("800.00") : null,
        status,
        status == PaymentStatus.REJECTED ? "No coincide" : null,
        status == PaymentStatus.PENDING ? null : Instant.parse("2026-10-11T15:00:00Z"),
        status == PaymentStatus.PENDING ? null : ADMIN,
        CREATED);
  }

  /** The payment with its one voucher, of id {@link #PROOF}. */
  static StoredPayment stored(Payment payment) {
    return new StoredPayment(
        payment,
        List.of(
            new PaymentProof(
                PROOF,
                ProofKind.VOUCHER,
                new BigDecimal("800.00"),
                payment.verifiedAmount(),
                "Yape",
                new ProofFile(KEY, "v.pdf", VoucherType.PDF, 10),
                ProofAuthor.GUEST,
                null,
                CREATED)),
        CREATED);
  }

  static GuestProfile guest(boolean contractType, boolean active) {
    return new GuestProfile(GUEST, "Ana Quispe", "12345678", contractType, active, false, "DNI");
  }

  static ContractInfo contract(String start, @Nullable String end, @Nullable Instant terminatedAt) {
    return new ContractInfo(
        CONTRACT,
        ENVIRONMENT,
        ContractTerms.of(
            LocalDate.parse(start),
            end == null ? null : LocalDate.parse(end),
            terminatedAt,
            ZoneId.of("America/Lima"),
            5,
            3),
        new BigDecimal("800.00"));
  }

  static DirectPayment forPeriod(String period, String amount, byte[] content) {
    return new DirectPayment(
        ADMIN,
        GUEST,
        YearMonth.parse(period),
        null,
        null,
        java.util.List.of(
            new com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload(
                com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType
                    .VOUCHER,
                new BigDecimal(amount),
                "Efectivo",
                new VoucherUpload("../../vaucher.pdf", content))));
  }

  static DirectPayment forStay(byte[] content) {
    return new DirectPayment(
        ADMIN,
        GUEST,
        null,
        STAY,
        null,
        java.util.List.of(
            new com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload(
                com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType
                    .VOUCHER,
                new BigDecimal("150.50"),
                null,
                new VoucherUpload("v.pdf", content))));
  }

  void contractGuestWithOpenContract() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.lockedContractsOf(GUEST))
        .thenReturn(List.of(contract("2026-01-01", null, null)));
    lenient().when(payments.findAllByContract(CONTRACT)).thenReturn(List.of());
  }
}
