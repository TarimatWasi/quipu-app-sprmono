package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.adapter.in.rest.GuestOnboardingBffController.DocumentForm;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.multipart.MultipartFile;

/** TAR-137: a document over 5 MB is refused by its declared size, before its bytes are read. */
class GuestOnboardingBffControllerTest {

  @Test
  void aDocumentOverTheLimitIsRefusedWithoutReadingIt() throws Exception {
    OnboardingUseCase onboarding = mock(OnboardingUseCase.class);
    GuestAccountUseCase accounts = mock(GuestAccountUseCase.class);
    var controller = new GuestOnboardingBffController(onboarding, accounts, mock(IdMasker.class));
    MultipartFile file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(5L * 1024 * 1024 + 1);
    MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();

    assertThatThrownBy(
            () ->
                controller.upload(
                    new DocumentForm(file), request, new TestingAuthenticationToken("1", null)))
        .isInstanceOf(InvalidOnboardingInputException.class);
    verify(file, never()).getBytes();
    verifyNoInteractions(onboarding, accounts);
  }
}
