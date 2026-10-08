package com.spire.backend.service;

import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * A staff member's title as printed on agreements (the console user's
 * {@code title}), kept in {@code web_agreement_staff_titles} so nothing is
 * added to {@code users}. Set when the System Admin adds the account or
 * saves its Details card; read to prefill the countersign.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementStaffTitleService {

    /** The console's limit on a title (AgreementAdminController:271-272). */
    static final int MAX_TITLE = 255;

    private final WebAgreementStaffTitleRepository titleRepository;

    /** The user's title; empty when none was ever set (or it is blank). */
    @Transactional(readOnly = true)
    public Optional<String> titleOf(Long userId) {
        if (userId == null) return Optional.empty();
        return titleRepository.findByUserId(userId)
                .map(WebAgreementStaffTitle::getTitle)
                .filter(t -> !t.isBlank());
    }

    /**
     * Sets the user's title (trimmed; blank stores null), updating their one
     * row or adding it. {@code by} is whoever set it.
     */
    @Transactional
    public void setTitle(Long userId, String title, Long by) {
        String t = title == null || title.isBlank() ? null : title.trim();
        WebAgreementStaffTitle row = titleRepository.findByUserId(userId)
                .orElseGet(() -> WebAgreementStaffTitle.builder().userId(userId).build());
        row.setTitle(t);
        row.setUpdatedBy(by);
        titleRepository.save(row);
    }

    /**
     * A typed title, trimmed: 400 "Title is required." when blank and
     * {@code required}, 400 "Title is too long (max 255 characters)." over
     * the limit (console messages, AgreementAdminController:160-162, 265-272).
     * "" when blank and optional.
     */
    static String clean(String raw, boolean required) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty() && required) throw new IllegalArgumentException("Title is required.");
        if (t.length() > MAX_TITLE) throw new IllegalArgumentException("Title is too long (max 255 characters).");
        return t;
    }
}
