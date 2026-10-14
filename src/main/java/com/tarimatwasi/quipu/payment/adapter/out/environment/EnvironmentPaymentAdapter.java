package com.tarimatwasi.quipu.payment.adapter.out.environment;

import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.payment.port.out.PaymentEnvironmentPort;
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
}
