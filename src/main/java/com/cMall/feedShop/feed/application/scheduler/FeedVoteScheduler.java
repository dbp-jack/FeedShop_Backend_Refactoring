package com.cMall.feedShop.feed.application.scheduler;

import com.cMall.feedShop.feed.application.service.FeedVoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedVoteScheduler {

    private final FeedVoteService feedVoteService;

    // 매일 새벽 3시 DB 기준으로 Redis 투표 수 보정
    // Redis 장애·서버 크래시 등으로 INCR/DECR 누락 시 불일치를 DB 원본으로 복구
    @Scheduled(cron = "0 0 3 * * *")
    public void syncVoteCounts() {
        log.info("투표 수 보정 스케줄러 시작");
        feedVoteService.syncAllVoteCounts();
        log.info("투표 수 보정 스케줄러 완료");
    }
}
