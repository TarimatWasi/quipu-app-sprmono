package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentReviewerPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import org.springframework.stereotype.Component;

/**
 * The other modules the administrator's payment services ask who and where: the guests, the
 * environments, the stays and the administrators who review (BE-SPR-COD-09: one parameter instead
 * of four).
 */
@Component
record PaymentDirectory(
    PaymentGuestPort guests,
    PaymentEnvironmentPort environments,
    PaymentStayPort stays,
    PaymentReviewerPort reviewers) {}
