package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofEdit;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The proofs of a multipart payment form (RN-22, TAR-239): a JSON part {@code proofs} (ProofInput
 * or ProofEdit of the contract) and the files in {@code files}, each proof pointing to its file by
 * {@code fileIndex}. Every error names the field as the contract does ({@code proofs[1].amount},
 * {@code proofs[0].file}).
 */
public final class ProofParts {

  /** The JSON part of the proofs. */
  public static final String PROOFS = "proofs";

  /** The files of the vouchers. */
  public static final String FILES = "files";

  /** One proof as the client sends it; any other field is refused. */
  public record ProofPart(
      @Nullable UUID id,
      @Nullable ProofType kind,
      @Nullable BigDecimal amount,
      @Nullable String method,
      @Nullable Integer fileIndex,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  private ProofParts() {}

  /**
   * Spring binds only what a form declares and silently drops the rest, but a payment form is
   * closed: a misspelled part must not register a payment with other terms.
   */
  public static void checkParts(Set<String> fields, MultipartHttpServletRequest request) {
    List<String> sent =
        Stream.concat(
                request.getParameterMap().keySet().stream(), request.getFileMap().keySet().stream())
            .toList();
    for (String part : sent) {
      if (!fields.contains(part)) {
        throw new InvalidPaymentException(part);
      }
    }
  }

  /** The proofs of a new payment: an id is not one of their fields. */
  public static List<ProofUpload> uploads(MultipartHttpServletRequest request, JsonMapper json) {
    List<ProofPart> parts = read(request, json);
    List<@Nullable VoucherUpload> files = filesFor(parts, request);
    List<ProofUpload> proofs = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      ProofPart part = parts.get(i);
      if (part.id() != null) {
        throw new InvalidPaymentException(field(i, "id"));
      }
      proofs.add(new ProofUpload(kindOf(part, i), amountOf(part, i), part.method(), files.get(i)));
    }
    return proofs;
  }

  /** The proofs of a correction; an id that was never issued is not a proof of the payment. */
  public static List<ProofEdit> edits(
      MultipartHttpServletRequest request, JsonMapper json, IdMasker ids) {
    List<ProofPart> parts = read(request, json);
    List<@Nullable VoucherUpload> files = filesFor(parts, request);
    List<ProofEdit> proofs = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      ProofPart part = parts.get(i);
      UUID id = part.id();
      Long proofId;
      try {
        proofId = id == null ? null : ids.unmask(IdKind.PAYMENT_PROOF, id);
      } catch (UnknownMaskedIdException unknown) {
        throw new InvalidPaymentException(field(i, "id"));
      }
      proofs.add(
          new ProofEdit(proofId, kindOf(part, i), amountOf(part, i), part.method(), files.get(i)));
    }
    return proofs;
  }

  private static List<ProofPart> read(MultipartHttpServletRequest request, JsonMapper json) {
    String text = textOf(request);
    if (text == null) {
      throw new InvalidPaymentException(PROOFS);
    }
    List<ProofPart> parts;
    try {
      parts = List.of(json.readValue(text, ProofPart[].class));
    } catch (JacksonException | NullPointerException unreadable) {
      throw new InvalidPaymentException(PROOFS);
    }
    if (parts.isEmpty() || parts.size() > ManagePaymentsUseCase.MAX_PROOFS) {
      throw new InvalidPaymentException(PROOFS);
    }
    for (int i = 0; i < parts.size(); i++) {
      Map<String, Object> unknown = parts.get(i).unknown();
      if (unknown != null && !unknown.isEmpty()) {
        throw new InvalidPaymentException(field(i, unknown.keySet().iterator().next()));
      }
    }
    return parts;
  }

  /** The JSON part, sent as a part with its content type or as a plain field. */
  private static @Nullable String textOf(MultipartHttpServletRequest request) {
    MultipartFile file = request.getFile(PROOFS);
    if (file != null) {
      try {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
        throw new InvalidPaymentException(PROOFS);
      }
    }
    return request.getParameter(PROOFS);
  }

  /** The file of each proof by its {@code fileIndex}; each file is used once and none is left. */
  private static List<@Nullable VoucherUpload> filesFor(
      List<ProofPart> parts, MultipartHttpServletRequest request) {
    List<MultipartFile> sent = request.getFiles(FILES);
    if (sent.size() > ManagePaymentsUseCase.MAX_PROOFS) {
      throw new InvalidPaymentException(FILES);
    }
    Set<Integer> used = new HashSet<>();
    List<@Nullable VoucherUpload> files = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      Integer index = parts.get(i).fileIndex();
      if (index == null) {
        files.add(null);
        continue;
      }
      if (index < 0 || index >= sent.size() || !used.add(index)) {
        throw new InvalidPaymentException(field(i, "fileIndex"));
      }
      MultipartFile file = sent.get(index);
      String name = file.getOriginalFilename();
      files.add(new VoucherUpload(name == null ? "" : name, content(file, field(i, "file"))));
    }
    if (used.size() != sent.size()) {
      throw new InvalidPaymentException(FILES);
    }
    return files;
  }

  private static ProofType kindOf(ProofPart part, int index) {
    ProofType kind = part.kind();
    if (kind == null) {
      throw new InvalidPaymentException(field(index, "kind"));
    }
    return kind;
  }

  private static BigDecimal amountOf(ProofPart part, int index) {
    BigDecimal amount = part.amount();
    if (amount == null || amount.toString().toLowerCase(Locale.ROOT).indexOf('e') >= 0) {
      throw new InvalidPaymentException(field(index, "amount"));
    }
    return amount;
  }

  private static String field(int index, String name) {
    return PROOFS + "[" + index + "]." + name;
  }

  /**
   * RF-15: the declared size is checked before the bytes are read and inspected; TAR-39: the PDF is
   * inspected here, before the use case opens its transaction and locks rows.
   */
  public static byte[] content(MultipartFile file, String field) {
    if (file.getSize() > ManagePaymentsUseCase.MAX_VOUCHER_BYTES) {
      throw new InvalidVoucherFileException(field);
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException unreadable) {
      throw new InvalidVoucherFileException(field);
    }
    FileKind.inspectAhead(content);
    return content;
  }
}
