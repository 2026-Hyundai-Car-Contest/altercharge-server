package com.server.altercharger_server.common.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI alterChargeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("AlterCharge 백엔드 API").version("0.2.0")
                        .description("주차 화면 MVP API 01~08. 환경공단 JSON을 서버에서 수집해 PostgreSQL에 저장하고 "
                                + "앱에도 JSON으로 제공합니다. 가용 충전기는 stat=2만 집계합니다. "
                                + "시각은 ISO 8601 오프셋 문자열이며 관측 시각과 상태 변경 시각을 구분합니다. "
                                + "mock-user-001과 mock-vehicle-001은 인증되지 않은 시연용 식별자입니다. "
                                + "외부 충전 이벤트는 MOCK만 허용하고 실제 방문 집계에서 제외합니다. "
                                + "REQ_ST_005: 유효 기록 14일 미만이면 프론트에서 SOC<=30%로 권장 여부를 정하고, "
                                + "14일 이상이면 기존 프론트 판정을 전달합니다. 서버는 충전 권장 여부·다음 충전일을 계산하지 않습니다. "
                                + "요금·회원가입·인증·어드민·주행 중 안내는 업무 API 범위에서 제외합니다. "
                                + "예시 데이터는 모두 가상값입니다. 운영 주소는 https://15-164-244-37.sslip.io이며 "
                                + "기본 서버 /는 현재 접속한 호스트를 사용하므로 로컬 Try it out도 로컬에서 실행됩니다."))
                .servers(List.of(new Server().url("/")
                                .description("현재 접속한 호스트·포트: 로컬 전용 테스트 시 이 서버를 사용"),
                        new Server().url("https://15-164-244-37.sslip.io")
                                .description("운영 HTTPS 서버")));
    }
}
