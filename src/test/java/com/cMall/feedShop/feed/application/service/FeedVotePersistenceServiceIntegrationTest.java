package com.cMall.feedShop.feed.application.service;

import com.cMall.feedShop.event.domain.Event;
import com.cMall.feedShop.feed.domain.entity.Feed;
import com.cMall.feedShop.feed.domain.entity.FeedVote;
import com.cMall.feedShop.feed.domain.repository.FeedVoteRepository;
import com.cMall.feedShop.user.domain.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({FeedVotePersistenceService.class, FeedVotePersistenceServiceIntegrationTest.QueryDslTestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("FeedVote 저장 트랜잭션 통합 테스트")
class FeedVotePersistenceServiceIntegrationTest {

    @TestConfiguration
    static class QueryDslTestConfig {
        @Bean
        JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }
    }

    @Autowired
    private FeedVotePersistenceService persistenceService;

    @Autowired
    private FeedVoteRepository feedVoteRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void disableForeignKeys() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    @AfterEach
    void restoreForeignKeys() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @Test
    @DisplayName("유니크 제약 위반 트랜잭션이 롤백된 뒤 다음 투표는 정상 저장")
    void duplicateRollback_doesNotPolluteNextSaveTransaction() {
        persistenceService.saveVote(vote(1L, 10L, 100L));

        assertThatThrownBy(() -> persistenceService.saveVote(vote(1L, 11L, 100L)))
                .isInstanceOf(DataIntegrityViolationException.class);

        FeedVote nextVote = persistenceService.saveVote(vote(1L, 12L, 101L));

        assertThat(nextVote.getId()).isNotNull();
        assertThat(feedVoteRepository.countByEvent_Id(1L)).isEqualTo(2L);
    }

    private FeedVote vote(Long eventId, Long feedId, Long voterId) {
        FeedVote vote = FeedVote.builder()
                .event(reference(Event.class, eventId))
                .feed(reference(Feed.class, feedId))
                .voter(reference(User.class, voterId))
                .build();
        ReflectionTestUtils.setField(vote, "createdAt", LocalDateTime.now());
        return vote;
    }

    private <T> T reference(Class<T> type, Long id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.getReference(type, id);
        } finally {
            entityManager.close();
        }
    }
}
