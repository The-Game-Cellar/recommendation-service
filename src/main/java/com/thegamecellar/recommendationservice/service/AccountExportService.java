package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedCandidate;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedHolding;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedProfile;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

// GDPR right of access for everything this service keeps per user, the same four tables the
// account purge empties. One read-only transaction, so the file describes a single moment.
@Service
@RequiredArgsConstructor
public class AccountExportService {

    private final UserProfileSnapshotRepository profileRepository;
    private final UserCandidatePoolRepository poolRepository;
    private final PoolHoldingRepository holdingRepository;
    private final ComputeQueueRepository queueRepository;

    @Transactional(readOnly = true)
    public AccountExportDTO exportUser(String userId) {
        ExportedProfile profile = profileRepository.findById(userId).map(ExportedProfile::of).orElse(null);
        List<ExportedCandidate> candidatePool = poolRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(UserCandidatePool::getIgdbId))
                .map(ExportedCandidate::of)
                .toList();
        List<ExportedHolding> heldBackGames = holdingRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(PoolHolding::getGenre).thenComparing(PoolHolding::getIgdbId))
                .map(ExportedHolding::of)
                .toList();
        ExportedQueueEntry pendingRecompute = queueRepository.findById(userId).map(ExportedQueueEntry::of).orElse(null);
        return new AccountExportDTO(userId, Instant.now().toString(), profile, candidatePool, heldBackGames, pendingRecompute);
    }
}
