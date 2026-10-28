package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.environment;

import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The payment module asks the environment module, through its exposed port, for the codes. */
@Component
public class EnvironmentPaymentAdapter implements PaymentEnvironmentPort {

  private final ManageEnvironmentsUseCase environments;

  EnvironmentPaymentAdapter(ManageEnvironmentsUseCase environments) {
    this.environments = environments;
  }

  @Override
  public Map<Long, String> codesOf(Collection<Long> environmentIds) {
    return environments.codesOf(environmentIds);
  }

  @Override
  public void requireExists(Long environmentId) {
    environments.get(environmentId);
  }
}
