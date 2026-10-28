package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController.DirectPaymentForm;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.shared.file.FileInspectionBusyException;
import com.tarimatwasi.quipu.shared.file.PdfInspections;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;

/** TAR-39: a voucher whose inspection runs past its deadline never reaches the use case. */
class PaymentsBffControllerTest {

  @Test
  void aVoucherPastTheDeadlineOfItsInspectionNeverReachesTheUseCase() throws Exception {
    ManagePaymentsUseCase payments = mock(ManagePaymentsUseCase.class);
    var controller = new PaymentsBffController(payments, mock(IdMasker.class));
    var form =
        new DirectPaymentForm(
            new MockMultipartFile("file", "v.pdf", "application/pdf", TestPdfs.plain()),
            UUID.randomUUID(),
            "2026-10",
            null,
            new BigDecimal("800"),
            null);

    PdfInspections.whilePastTheDeadline(
        () ->
            assertThatThrownBy(
                    () ->
                        controller.create(
                            form,
                            new MockMultipartHttpServletRequest(),
                            new TestingAuthenticationToken("1", null)))
                .isInstanceOf(FileInspectionBusyException.class));
    // it would parse the file again under the lock of the guest (PaymentService.createDirect)
    verifyNoInteractions(payments);
  }
}
