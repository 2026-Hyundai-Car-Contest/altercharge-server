· 서버: 주행 기록과 충전소 정보를 모아 앱에서 필요한 데이터를 제공합니다. 최근 56일의 배터리 잔량과 누적 주행거리 등 차량 기록을 보관하고 충전 이벤트와 최근 충전 이력을 관리합니다. 한국환경공단의 충전소 기본 정보를 정기적으로 수집하고 충전기 상태 변경분을 5분마다 받아 DB에 반영합니다. 사용자 위치를 기준으로 반경 3km 이내의 충전소를 검색해 거리·충전기 종류·출력·사용 가능한 충전기 수를 제공하고 조건에 맞는 충전소를 추천합니다. 길 안내 직전에는 저장된 충전기 상태와 마지막 확인 시각을 검사해 안내 가능 여부와 불가 사유를 알려 줍니다. Spring Boot와 JPA, PostgreSQL로 구현하고 Docker로 실행 환경을 구성해 AWS EC2에서 HTTPS로 운영합니다.

<!-- HUMANIZE-SUMMARY -->
<!--
{
  "genre": "report",
  "verdict": "accept",
  "original_chars": 396,
  "final_chars": 392,
  "char_count_scope": "본문 공백과 끝 개행 포함, 요약 블록 제외",
  "change_rate": 0.0102,
  "change_rate_percent": 1.02,
  "change_rate_source": "04_gates_before_finalize.json",
  "gate_verdict": "OK — 수렴",
  "grade": "B",
  "grade_reason": "S1 잔존 0건, S2 잔존 1건, 자체검증 6/6 통과. 변경률은 A 기준인 10~25%보다 낮다.",
  "self_check": {
    "passed": 6,
    "total": 6,
    "content_fidelity": "pass",
    "change_rate": "pass",
    "genre": "pass",
    "register": "pass",
    "no_remaining_s1": "pass",
    "no_injected_rhetoric": "pass"
  },
  "categories": [
    {
      "id": "C-11",
      "result": "연결어미 뒤 쉼표 3건 제거, 잔존 0건"
    },
    {
      "id": "C-12",
      "result": "C-11 손질에 따라 쉼표 포함 문장 감소"
    },
    {
      "id": "E-2",
      "result": "합쇼체 종결 반복은 장르에 맞춰 유지"
    }
  ],
  "highlights": [
    {
      "before": "보관하고, 충전 이벤트",
      "after": "보관하고 충전 이벤트"
    },
    {
      "before": "수집하고, 충전기 상태 변경분",
      "after": "수집하고 충전기 상태 변경분"
    },
    {
      "before": "검색하고, 거리·충전기 종류·출력·사용 가능한 충전기 수를 제공하며",
      "after": "검색해 거리·충전기 종류·출력·사용 가능한 충전기 수를 제공하고"
    }
  ],
  "residual_findings": [
    {
      "id": "E-2",
      "severity": "S2",
      "finding": "앞의 네 문장이 합니다로 끝나는 반복을 유지했다.",
      "disposition": "짧은 기술 보고서의 합쇼체가 자연스러워 억지로 종결어미를 바꾸지 않았다."
    }
  ],
  "fidelity_violations": [],
  "overediting_findings": [],
  "body_corrections_by_finalizer": 0
}
-->
