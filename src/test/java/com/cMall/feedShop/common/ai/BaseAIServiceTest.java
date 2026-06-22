package com.cMall.feedShop.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BaseAIService 테스트")
class BaseAIServiceTest {

    private BaseAIService baseAIService;

    @BeforeEach
    void setUp() {
        baseAIService = new BaseAIService(new ObjectMapper());
    }

    @Test
    @DisplayName("AI 비활성화 상태에서는 항상 폴백 텍스트를 반환한다")
    void generateText_ReturnsFallback() {
        String result = baseAIService.generateText("테스트 프롬프트");

        assertThat(result).isEqualTo("{\"message\": \"AI 서비스가 현재 사용 불가능합니다.\"}");
    }

    @Test
    @DisplayName("응답 문자열과 관계없이 기본 응답 객체를 반환한다")
    void parseAIResponse_ReturnsEmptyResponse() {
        TestAIResponse result = baseAIService.parseAIResponse(
                "{\"status\":\"OK\",\"message\":\"성공\"}",
                TestAIResponse.class
        );

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("null 응답도 기본 응답 객체로 변환한다")
    void parseAIResponse_NullResponse_ReturnsEmptyResponse() {
        TestAIResponse result = baseAIService.parseAIResponse(null, TestAIResponse.class);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("기본 생성자가 없는 응답 클래스는 명시적인 예외를 반환한다")
    void parseAIResponse_ConstructorFailure_ThrowsException() {
        assertThatThrownBy(() -> baseAIService.parseAIResponse("{}", InvalidResponse.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("기본 응답 객체 생성 실패");
    }

    static class TestAIResponse extends BaseAIResponse<String> {
        public TestAIResponse() {
            super();
        }
    }

    static class InvalidResponse extends BaseAIResponse<String> {
        private InvalidResponse(String ignored) {
        }
    }
}
