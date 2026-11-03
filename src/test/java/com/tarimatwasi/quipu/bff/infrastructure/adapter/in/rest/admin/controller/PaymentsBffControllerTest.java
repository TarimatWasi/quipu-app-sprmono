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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/** TAR-39: a voucher whose inspection runs past its deadline never reaches the use case. */
class PaymentsBffControllerTest {

  @Test
  void aVoucherPastTheDeadlineOfItsInspectionNeverReachesTheUseCase() throws Exception {
    ManagePaymentsUseCase payments = mock(ManagePaymentsUseCase.class);
    var controller =
        new PaymentsBffController(payments, mock(IdMasker.class), JsonMapper.builder().build());
    var form = new DirectPaymentForm(UUID.randomUUID(), "2026-10", null, null);
    var request = new MockMultipartHttpServletRequest();
    request.addParameter("guestId", form.guestId().toString());
    request.addParameter("period", "2026-10");
    request.addParameter("proofs", "[{\"kind\":\"VOUCHER\",\"amount\":800,\"fileIndex\":0}]");
    request.addFile(new MockMultipartFile("files", "v.pdf", "application/pdf", TestPdfs.plain()));

    PdfInspections.whilePastTheDeadline(
        () ->
            assertThatThrownBy(
                    () ->
                        controller.create(form, request, new TestingAuthenticationToken("1", null)))
                .isInstanceOf(FileInspectionBusyException.class));
    // it would parse the file again under the lock of the guest (PaymentService.registerDirect)
    verifyNoInteractions(payments);
  }
}
