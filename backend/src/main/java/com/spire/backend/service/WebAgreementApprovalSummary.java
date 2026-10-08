package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The approval summary on list rows (the console's
 * {@code populateApprovalSummary}): fills the transient
 * {@code managerStatus}, {@code accountsStatus} and
 * {@code sentForApprovalAt} of a page of agreements with two queries (gate
 * rows + SENT_FOR_APPROVAL events) rather than per-row lookups. Shared by
 * the ERM list, the approver's "All agreements" list and the System Admin
 * tabs, so none of those services depends on another.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementApprovalSummary {

    private final WebAgreementApprovalRepository approvalRepository;
    private final WebAgreementEventRepository eventRepository;

    /**
     * managerStatus / accountsStatus = the LATEST gate per role (highest
     * round wins); null when no gate exists for that role (Phase 1 never has
     * an Accounts gate, so the UI shows "N/A"). sentForApprovalAt = the
     * EARLIEST SENT_FOR_APPROVAL event (when it first went to approvers),
     * ISO-formatted; null if never sent.
     */
    public void populate(List<WebAgreement> agreements) {
        if (agreements == null || agreements.isEmpty()) return;
        List<Long> ids = agreements.stream()
                .map(WebAgreement::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;

        // Latest gate row per (agreement, role): highest round wins.
        Map<Long, Map<String, WebAgreementApproval>> byAgreement = new HashMap<>();
        for (WebAgreementApproval g : approvalRepository.findByAgreementIdIn(ids)) {
            byAgreement.computeIfAbsent(g.getAgreementId(), k -> new HashMap<>())
                    .merge(g.getRole(), g, (cur, cand) -> {
                        int curRound = cur.getRound() == null ? -1 : cur.getRound();
                        int candRound = cand.getRound() == null ? -1 : cand.getRound();
                        return candRound >= curRound ? cand : cur;
                    });
        }

        // Earliest SENT_FOR_APPROVAL event per agreement.
        Map<Long, LocalDateTime> sentAt = new HashMap<>();
        for (WebAgreementEvent e : eventRepository.findByAgreementIdInAndEventType(ids,
                WebAgreementEvent.EventType.SENT_FOR_APPROVAL.name())) {
            if (e.getCreatedAt() == null) continue;
            sentAt.merge(e.getAgreementId(), e.getCreatedAt(),
                    (cur, cand) -> cand.isBefore(cur) ? cand : cur);
        }

        for (WebAgreement a : agreements) {
            Map<String, WebAgreementApproval> roleMap = byAgreement.get(a.getId());
            if (roleMap != null) {
                WebAgreementApproval mgr = roleMap.get(WebAgreementApproval.ApproverRole.MANAGER.name());
                if (mgr != null && mgr.getStatus() != null) {
                    a.setManagerStatus(mgr.getStatus());
                }
                WebAgreementApproval acc = roleMap.get(WebAgreementApproval.ApproverRole.ACCOUNTS.name());
                if (acc != null && acc.getStatus() != null) {
                    a.setAccountsStatus(acc.getStatus());
                }
            }
            LocalDateTime sent = sentAt.get(a.getId());
            if (sent != null) {
                a.setSentForApprovalAt(sent.toString());
            }
        }
    }

    /**
     * Gate rows from one {@code findByAgreementIdIn} grouped per agreement,
     * each oldest first (ties by id): what
     * {@code findByAgreementIdOrderByCreatedAtAsc} gives one agreement, for a
     * whole list in one query.
     */
    public static Map<Long, List<WebAgreementApproval>> oldestFirstByAgreement(List<WebAgreementApproval> rows) {
        Map<Long, List<WebAgreementApproval>> out = new HashMap<>();
        for (WebAgreementApproval g : rows) {
            out.computeIfAbsent(g.getAgreementId(), k -> new ArrayList<>()).add(g);
        }
        Comparator<WebAgreementApproval> oldestFirst = Comparator
                .comparing(WebAgreementApproval::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(WebAgreementApproval::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        out.values().forEach(list -> list.sort(oldestFirst));
        return out;
    }
}
