package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.adapter.in.rest.ContractsBffController.ContractResponse;
import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.OwnContractUseCase;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** CU-18, RN-26: a guest reads its own contract. Only GUEST reaches /bff/guest/**. */
@RestController
public class GuestContractBffController {

  private final OwnContractUseCase ownContract;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestContractBffController(
      OwnContractUseCase ownContract, GuestAccountUseCase accounts, IdMasker ids) {
    this.ownContract = ownContract;
    this.accounts = accounts;
    this.ids = ids;
  }

  /** The guest is the one of the session, never one named in the request (RN-09). */
  @GetMapping("/bff/guest/contract")
  public ResponseEntity<ContractResponse> get(Authentication authentication) {
    try {
      Long guestId =
          accounts
              .guestOf(Long.valueOf(authentication.getName()))
              .orElseThrow(ContractNotFoundException::new);
      // The body carries a freshly signed URL: no cache may keep it (contract v0.7.0).
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(ContractResponse.of(ownContract.ownContract(guestId), ids));
    } catch (ContractNotFoundException never) {
      // The contract answers this 404 without a body: "no contract was ever associated".
      return ResponseEntity.notFound().build();
    }
  }
}
