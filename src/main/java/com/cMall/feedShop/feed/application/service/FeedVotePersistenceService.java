package com.cMall.feedShop.feed.application.service;

import com.cMall.feedShop.feed.domain.entity.FeedVote;
import com.cMall.feedShop.feed.domain.repository.FeedVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Phase 2-B] FeedVote 저장 전용 서비스
 *
 * 호출자가 NOT_SUPPORTED인 상태에서 REQUIRED 트랜잭션으로 vote INSERT를 수행하고,
 * DB 유니크 제약 위반 시 DataIntegrityViolationException을 그대로 propagate.
 *
 * 호출자(FeedVoteService.voteFeed)는 트랜잭션 없이(NOT_SUPPORTED) 실행되므로
 * 예외를 catch해도 트랜잭션 오염이 발생하지 않음.
 *
 * 문제 배경:
 * - 기존 외부 트랜잭션 안에서 제약 위반을 catch하면 Hibernate Session은 계속 사용할 수 없음
 * - noRollbackFor: Spring 레벨 설정이지만 Hibernate Session 오염은 이미 발생
 * - 해결: voteFeed()를 NOT_SUPPORTED로 트랜잭션 없이 실행 → catch해도 오염 없음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedVotePersistenceService {

    private final FeedVoteRepository feedVoteRepository;

    /**
     * 투표 저장 (독립 트랜잭션)
     * - 성공: 저장된 FeedVote 반환
     * - 중복: DataIntegrityViolationException propagate → 호출자가 catch
     */
    // [Phase 2-B] REQUIRED: 독립 트랜잭션으로 INSERT + flush
    // REQUIRED가 새 트랜잭션을 만드는 이유: 호출자 voteFeed()가 NOT_SUPPORTED이기 때문
    // 제약 위반은 이 저장 트랜잭션에서 rollback되고 호출자는 트랜잭션 밖에서 예외를 처리함
    @Transactional
    public FeedVote saveVote(FeedVote vote) {
        FeedVote saved = feedVoteRepository.save(vote);
        feedVoteRepository.flush(); // DB 유니크 제약 즉시 검증
        return saved;
    }
}
