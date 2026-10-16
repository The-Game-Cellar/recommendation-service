package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// GDPR right-to-deletion for everything this service keeps per user. All of it is derived from
// the library and rebuilt on the next request, which is why the gateway runs this before the
// library purge rather than needing a retry path of its own.
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountPurgeService {

    private final ComputeQueueRepository queueRepository;
    private final PoolHoldingRepository holdingRepository;
    private final UserCandidatePoolRepository poolRepository;
    private final UserProfileSnapshotRepository profileRepository;
    private final UserProfileCache profileCache;
    private final UserStateCache stateCache;
    private final TransactionTemplate transactionTemplate;

    public PurgeResult purgeUser(String userId) {
        PurgeResult result = transactionTemplate.execute(status -> {
            // Queue first: a compute in flight holds this row's lock, so the delete waits for it
            // to commit and the deletes below then catch the rows it wrote.
            long queued = queueRepository.deleteByUserId(userId);
            long held = holdingRepository.deleteByUserId(userId);
            long pool = poolRepository.deleteByUserId(userId);
            long profiles = profileRepository.deleteByUserId(userId);
            return new PurgeResult(queued, held, pool, profiles);
        });
        // After commit, so a read racing the purge cannot put the old profile back in the cache.
        profileCache.invalidate(userId);
        stateCache.invalidate(userId);
        log.info("Account purge complete for userId={}: removed {} queue + {} holding + {} pool + {} profile rows",
                userId, result.queueRemoved(), result.holdingRemoved(), result.poolRemoved(), result.profileRemoved());
        return result;
    }

    public record PurgeResult(long queueRemoved,
                              long holdingRemoved,
                              long poolRemoved,
                              long profileRemoved) {}
}
