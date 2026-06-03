// 이벤트 목록 조회 응답 DTO
package com.cMall.feedShop.event.application.dto.response;

import lombok.*;
import java.io.Serializable;
import java.util.List;

// [Phase 2-A] Redis 직렬화를 위해 Serializable 추가
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EventListResponseDto implements Serializable {
    private List<EventSummaryDto> content;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
} 