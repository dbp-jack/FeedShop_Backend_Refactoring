package com.cMall.feedShop.feed.application.service;

import com.cMall.feedShop.common.exception.BusinessException;
import com.cMall.feedShop.common.exception.ErrorCode;
import com.cMall.feedShop.feed.application.dto.response.FeedVoteResponseDto;
import com.cMall.feedShop.feed.application.exception.FeedNotFoundException;
import com.cMall.feedShop.feed.domain.entity.Feed;
import com.cMall.feedShop.feed.domain.entity.FeedVote;
import com.cMall.feedShop.feed.domain.repository.FeedRepository;
import com.cMall.feedShop.feed.domain.repository.FeedVoteRepository;
import com.cMall.feedShop.user.domain.model.User;
import com.cMall.feedShop.user.domain.repository.UserRepository;
import com.cMall.feedShop.user.application.service.UserLevelService;
import com.cMall.feedShop.user.domain.model.ActivityType;
import com.cMall.feedShop.user.application.service.PointService;
import com.cMall.feedShop.event.domain.Event;
import com.cMall.feedShop.event.domain.enums.EventStatus;
import com.cMall.feedShop.event.application.service.EventStatusService;
import com.cMall.feedShop.common.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedVoteService {

    private final FeedVoteRepository feedVoteRepository;
    private final FeedRepository feedRepository;
    private final UserRepository userRepository;
    private final UserLevelService userLevelService;
    private final PointService pointService;
    private final EventStatusService eventStatusService;
    // DB 제약 위반이 발생한 저장 트랜잭션을 후속 리워드 처리와 격리
    private final FeedVotePersistenceService feedVotePersistenceService;
    // Redis INCR — DB 카운터 락 경합 없이 파생 투표 수 갱신
    private final StringRedisTemplate redisTemplate;

    private static final String VOTE_COUNT_KEY = "vote:count:";
    private static final String DUPLICATE_VOTE_CONSTRAINT = "uk_feed_votes_event_voter";

    /**
     * 피드 투표
     * - 이벤트 참여 피드에만 투표 가능
     * - 투표 시 자동으로 리워드 지급 (포인트 100점 + 뱃지 점수 2점)
     */
    // [Phase 2-B] NOT_SUPPORTED: 트랜잭션 없이 실행
    // [BEFORE 1] @Transactional(noRollbackFor=...) → Hibernate Session 오염으로 무효
    // [BEFORE 2] 외부 트랜잭션을 유지한 저장 분리 → rollback 뒤 후속 흐름의 안전성 보장 실패
    // [AFTER] NOT_SUPPORTED: 트랜잭션 없음 → saveVote 예외 catch해도 오염 없음
    //         각 하위 작업(saveVote, earnPoints, recordActivity)이 독립 트랜잭션 사용
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FeedVoteResponseDto voteFeed(Long feedId, Long userId) {
        log.info("피드 투표 요청 - feedId: {}, userId: {}", feedId, userId);

        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 2. 피드 조회
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new FeedNotFoundException(feedId));

        if (feed.isDeleted()) {
            throw new FeedNotFoundException(feedId);
        }

        // 3. 이벤트 참여 피드인지 확인
        if (!feed.isEventFeed()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이벤트 참여 피드에만 투표할 수 있습니다.");
        }

        // 4. 이벤트가 진행중인지 확인
        Event event = feed.getEvent();
        EventStatus eventStatus = eventStatusService.calculateEventStatus(event, TimeUtil.nowDate());
        if (eventStatus != EventStatus.ONGOING) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, 
                String.format("이벤트가 종료되어 투표할 수 없습니다. 현재 상태: %s", eventStatus));
        }

        // 4. 같은 이벤트에서 이미 다른 피드에 투표했는지 확인 (앱 레벨 1차 체크)
        if (feedVoteRepository.existsByEventIdAndUserId(feed.getEvent().getId(), userId)) {
            log.info("이미 해당 이벤트에 투표함 - 이벤트ID: {}, 사용자ID: {}", feed.getEvent().getId(), userId);
            return FeedVoteResponseDto.success(false, safeVoteCount(feedId));
        }

        // 5. 투표 생성
        // [Phase 2-B] DB 유니크 제약 (event_id, voter_id)으로 동시 요청 시 중복 방지
        // FeedVotePersistenceService의 REQUIRED 트랜잭션에서 flush() 처리
        //   → 유니크 제약 위반은 저장 트랜잭션만 rollback
        //   → 이 오케스트레이션은 NOT_SUPPORTED이므로 오염된 영속성 컨텍스트를 이어 쓰지 않음
        FeedVote vote = FeedVote.builder()
                .feed(feed)
                .voter(user)
                .event(feed.getEvent())
                .build();

        // [Phase 2-B] 독립 트랜잭션(saveVote)으로 INSERT + flush
        // NOT_SUPPORTED 환경에서 DataIntegrityViolationException catch → 트랜잭션 오염 없음
        FeedVote savedVote;
        try {
            savedVote = feedVotePersistenceService.saveVote(vote);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateVoteViolation(e)) {
                throw e;
            }
            log.info("[Phase 2-B] DB 유니크 제약으로 동시 투표 중복 차단 - 이벤트ID: {}, 사용자ID: {}",
                    feed.getEvent().getId(), userId);
            return FeedVoteResponseDto.success(false, safeVoteCount(feedId));
        }

        // 6. 피드 투표 수 증가
        // [BEFORE 1] ORM 레벨 증가 → 동시 요청 시 충돌로 롤백 발생
        // feed.incrementVoteCount();
        // [BEFORE 2] 원자적 SQL UPDATE → feed_votes FK S-lock + feeds UPDATE X-lock 데드락 발생
        // feedRepository.incrementVoteCountAtomic(feedId);

        // [Phase 2-B] Redis INCR 원자적 연산 — lock 없이 투표 수 갱신
        // Redis 실패 시 DB 커밋은 이미 완료된 상태. 정기 보정 스케줄러가 불일치를 복구
        updateRedisVoteCount(feedId);

        log.info("피드 투표 완료 - feedId: {}, userId: {}, voteId: {}", feedId, userId, savedVote.getId());

        // 7. 투표 리워드 지급 (포인트 100점 + 뱃지 점수 2점)
        try {
            // 포인트 100점 지급
            pointService.earnPoints(user, 100, "피드 투표 리워드", feedId);
            
            // 뱃지 점수 2점 추가 (VOTE_PARTICIPATION 활동 기록)
            userLevelService.recordActivity(userId, ActivityType.VOTE_PARTICIPATION, 
                "피드 투표 참여", feedId, "FEED");
            
            log.info("피드 투표 리워드 지급 완료 - userId: {}, feedId: {}", userId, feedId);
        } catch (Exception e) {
            log.error("피드 투표 리워드 지급 실패 - userId: {}, feedId: {}", userId, feedId, e);
            // 리워드 지급 실패가 투표에 영향을 주지 않도록 예외를 던지지 않음
        }

        // Redis에서 최신 투표 수 반환
        return FeedVoteResponseDto.success(true, safeVoteCount(feedId));
    }


    /**
     * 사용자가 특정 피드에 투표했는지 확인
     */
    public boolean hasVoted(Long feedId, Long userId) {
        if (userId == null) {
            return false;
        }
        return feedVoteRepository.existsByFeed_IdAndVoter_Id(feedId, userId);
    }

    /**
     * 특정 피드의 투표 개수 조회
     * [Phase 2-B] Redis 우선 조회 → Redis 없으면 DB 집계 후 setIfAbsent로 재설정
     * setIfAbsent: 동시 요청 시 첫 번째 스레드만 SET 성공 → 경쟁 조건 방지
     */
    public long getVoteCount(Long feedId) {
        String redisKey = VOTE_COUNT_KEY + feedId;
        try {
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                return Long.parseLong(cached);
            }
            long dbCount = feedVoteRepository.countByFeed_Id(feedId);
            redisTemplate.opsForValue().setIfAbsent(redisKey, String.valueOf(dbCount));
            return dbCount;
        } catch (Exception e) {
            log.warn("Redis vote count read failed; falling back to DB. feedId={}", feedId, e);
            return feedVoteRepository.countByFeed_Id(feedId);
        }
    }

    private void updateRedisVoteCount(Long feedId) {
        String redisKey = VOTE_COUNT_KEY + feedId;
        try {
            Long incremented = redisTemplate.opsForValue().increment(redisKey);
            if (incremented != null && incremented == 1L) {
                long dbCount = feedVoteRepository.countByFeed_Id(feedId);
                if (dbCount > incremented) {
                    redisTemplate.opsForValue().set(redisKey, String.valueOf(dbCount));
                }
            }
        } catch (Exception e) {
            log.warn("Redis vote count update failed; DB remains the source of truth. feedId={}", feedId, e);
        }
    }

    private int safeVoteCount(Long feedId) {
        return Math.toIntExact(getVoteCount(feedId));
    }

    private boolean isDuplicateVoteViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(DUPLICATE_VOTE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 특정 이벤트의 투표 개수 조회
     */
    public long getEventVoteCount(Long eventId) {
        return feedVoteRepository.countByEvent_Id(eventId);
    }

    /**
     * 투표 수 동기화 — feed_votes 실제 레코드 수 기준으로 feeds.participantVoteCount와 Redis 모두 보정
     * feed_votes: 투표 원본 데이터 / feeds.participantVoteCount·Redis: 파생 값
     * 파생 값끼리 보정하면 기존 오류가 복제되므로 항상 원본(feed_votes COUNT)을 기준으로 삼음
     */
    @Transactional
    public void syncVoteCount(Long feedId) {
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new FeedNotFoundException(feedId));

        // feed_votes 실제 레코드 수 — 보정 기준
        long actualVoteCount = feedVoteRepository.countByFeed_Id(feedId);
        long currentCount = feed.getParticipantVoteCount();

        // feeds.participantVoteCount 보정
        if (actualVoteCount != currentCount) {
            log.info("투표 수 동기화 - feedId: {}, feeds.count: {}, feed_votes.count: {}",
                    feedId, currentCount, actualVoteCount);
            long difference = actualVoteCount - currentCount;
            if (difference > 0) {
                for (int i = 0; i < difference; i++) feed.incrementVoteCount();
            } else {
                for (int i = 0; i < Math.abs(difference); i++) feed.decrementVoteCount();
            }
        }

        // Redis 카운터 보정 — feed_votes 집계값으로 덮어씀
        String redisKey = VOTE_COUNT_KEY + feedId;
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        long redisCount = redisValue != null ? Long.parseLong(redisValue) : -1;
        if (redisCount != actualVoteCount) {
            log.info("Redis 투표 수 보정 - feedId: {}, Redis: {}, feed_votes.count: {}",
                    feedId, redisCount, actualVoteCount);
            redisTemplate.opsForValue().set(redisKey, String.valueOf(actualVoteCount));
        }
    }

    /**
     * 모든 피드의 투표 수 동기화 — 전체 페이지 순회
     * 페이지 크기보다 피드 수가 많을 경우 일부 피드가 누락되는 문제 방지
     */
    @Transactional
    public void syncAllVoteCounts() {
        int pageSize = 1000;
        int pageNumber = 0;
        int syncedCount = 0;

        while (true) {
            Page<Feed> feedPage = feedRepository.findAllActive(PageRequest.of(pageNumber, pageSize));
            List<Feed> feeds = feedPage.getContent();

            if (feeds.isEmpty()) break;

            for (Feed feed : feeds) {
                try {
                    syncVoteCount(feed.getId());
                    syncedCount++;
                } catch (Exception e) {
                    log.error("피드 투표 수 동기화 실패 - feedId: {}", feed.getId(), e);
                }
            }

            if (feedPage.isLast()) break;
            pageNumber++;
        }

        log.info("전체 피드 투표 수 동기화 완료 - {}개 피드 처리됨", syncedCount);
    }

    /**
     * 여러 피드에 대한 사용자의 투표 상태 일괄 조회
     * 성능 개선을 위한 일괄 조회 메서드
     * 
     * @param feedIds 피드 ID 목록
     * @param userId 사용자 ID
     * @return 투표한 피드 ID 집합
     */
    public Set<Long> getVotedFeedIdsByFeedIdsAndUserId(List<Long> feedIds, Long userId) {
        if (userId == null || feedIds == null || feedIds.isEmpty()) {
            return new HashSet<>();
        }
        
        try {
            List<Long> votedFeedIds = feedVoteRepository.findVotedFeedIdsByFeedIdsAndUserId(feedIds, userId);
            return new HashSet<>(votedFeedIds);
        } catch (Exception e) {
            log.error("일괄 투표 상태 조회 중 오류 발생 - userId: {}, feedIds: {}", userId, feedIds, e);
            // 오류 발생 시 빈 집합 반환 (성능 개선 실패 시 기존 방식으로 fallback)
            return new HashSet<>();
        }
    }
}
