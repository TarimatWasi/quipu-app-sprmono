package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

/** One email as it leaves: the subject and the same message in HTML and in plain text. */
record MailContent(String subject, String html, String text) {}
