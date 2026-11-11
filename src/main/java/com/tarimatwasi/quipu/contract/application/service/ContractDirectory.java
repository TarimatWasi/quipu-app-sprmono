package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import com.tarimatwasi.quipu.shared.occupancy.StayOccupancyQuery;
import org.springframework.stereotype.Component;

/** The external capabilities queried when checking a contract's eligibility (BE-SPR-DI-05). */
@Component
record ContractDirectory(
    ContractEnvironmentPort environments,
    ContractGuestPort guests,
    GuestDebtQuery debts,
    StayOccupancyQuery stays) {}
