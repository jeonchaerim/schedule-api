#!/usr/bin/env bash
# 멀티 인스턴스 부하 테스트 하네스
#   ./loadtest/run.sh -n 5 -c 50 -m same      # 인스턴스 5개, 동시 요청 50건, 같은 회원·같은 시간대(락 정합성)
#   ./loadtest/run.sh -n 10 -c 200 -m spread  # 인스턴스 10개, 동시 요청 200건, 회원마다 다른 시간대(처리량)
# 옵션: -n 인스턴스 수 / -c 동시 요청 수 / -m same|spread / -k 끝나고 컨테이너 유지 / -s 이미지 재빌드 생략
set -euo pipefail

N=5; C=50; MODE=same; KEEP=0; BUILD=1
while getopts "n:c:m:ks" opt; do
  case $opt in
    n) N=$OPTARG ;;
    c) C=$OPTARG ;;
    m) MODE=$OPTARG ;;
    k) KEEP=1 ;;
    s) BUILD=0 ;;
    *) exit 1 ;;
  esac
done
[ "$MODE" = same ] || [ "$MODE" = spread ] || { echo "-m 은 same 또는 spread"; exit 1; }
[ "$MODE" = spread ] && [ "$C" -gt 1000 ] && { echo "spread 모드는 시딩된 회원 수(1000) 이하만 가능"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESULT_DIR="$ROOT/loadtest/results"
CSV="$RESULT_DIR/results.csv"
mkdir -p "$RESULT_DIR"
TS=$(date +%Y%m%d-%H%M%S)
RAW="$RESULT_DIR/$TS-$MODE-n$N-c$C.log"
URL="http://localhost:8088"

dc() { docker compose -f "$ROOT/loadtest/docker-compose.yml" "$@"; }
now_ms() { perl -MTime::HiRes=time -e 'printf "%.0f\n", time()*1000'; }
healthy_count() {
  dc ps app --format json 2>/dev/null | jq -s 'flatten | map(select(.Health=="healthy")) | length'
}
wait_healthy() {   # $1 = 기다릴 healthy 인스턴스 수
  for _ in $(seq 1 120); do
    [ "$(healthy_count)" = "$1" ] && return 0
    sleep 2
  done
  echo "app $1개가 healthy가 되지 않음"; dc ps; exit 1
}
teardown() { dc down -v --remove-orphans >"$RESULT_DIR/.teardown.log" 2>&1 || echo "⚠ 컨테이너 정리 실패 — $RESULT_DIR/.teardown.log 확인"; }
cleanup() { [ "$KEEP" = 1 ] || teardown; }
trap cleanup EXIT

echo "[0/5] 이전 실행의 잔여 컨테이너 정리 (깨끗한 상태에서 시작)"
teardown

echo "[1/5] 인프라 + app 1개 기동 (DataInitializer 시딩은 1개만 돌려야 중복 시딩이 안 생김)"
if [ "$BUILD" = 1 ]; then dc build app; fi
dc up -d --scale app=1 postgres redis app
wait_healthy 1
# healthy는 "서버가 떴다"일 뿐, CommandLineRunner(DataInitializer)는 그 뒤에도 회원 1000명을 저장하는 중일 수 있음
echo "      시딩 완료 대기 (member 1000건)"
for _ in $(seq 1 90); do
  [ "$(dc exec -T postgres psql -U schedule -d schedule -tAc 'select count(*) from member' 2>/dev/null | tr -d ' \r')" = 1000 ] && break
  sleep 2
done

echo "[2/5] app ${N}개로 scale out (JVM 여러 개가 동시에 부팅하면 서로 느려져서 3개씩 나눠서 띄움)"
CUR=1
while [ "$CUR" -lt "$N" ]; do
  CUR=$(( CUR + 3 )); [ "$CUR" -gt "$N" ] && CUR=$N
  dc up -d --no-recreate --scale app="$CUR" app
  wait_healthy "$CUR"
  echo "      app ${CUR}개 healthy"
done

echo "[3/5] nginx 기동 (app이 전부 뜬 뒤에 시작해야 N개 IP를 다 resolve함)"
dc up -d --no-deps --force-recreate nginx
READY=0
for _ in $(seq 1 30); do
  curl -sf "$URL/actuator/health" >/dev/null && { READY=1; break; }
  sleep 1
done
[ "$READY" = 1 ] || { echo "nginx를 통한 /actuator/health 실패"; dc logs --tail 20 nginx; exit 1; }

# 실행마다 다른 시간대를 써서 이전 실행 결과와 겹치지 않게 함 (분 단위 run id → 2100년대 날짜)
RUN_ID=$(( $(date +%s) / 60 ))
DAY=$(( RUN_ID % 28 + 1 )); MONTH=$(( (RUN_ID / 28) % 12 + 1 )); YEAR=$(( 2100 + (RUN_ID / 336) % 800 ))
SLOT_DATE=$(printf "%04d-%02d-%02d" "$YEAR" "$MONTH" "$DAY")
export URL MODE SLOT_DATE
export MEMBER=$(( RUN_ID % 1000 + 1 ))

fire() {   # $1 = 요청 번호. same: 전원 같은 회원, spread: 번호 = 회원 ID. $2가 있으면 응답 본문도 출력(디버그)
  local mid=$MEMBER out=/dev/null
  [ "$MODE" = spread ] && mid=$1
  [ -n "${2:-}" ] && out=/dev/stdout
  curl -s --max-time 240 -o "$out" -w '%{http_code} %{time_total} %header{x-upstream}\n' \
    -X POST "$URL/schedules" -H 'Content-Type: application/json' \
    -d "{\"title\":\"lt-$1\",\"content\":\"${TAG}\",\"startAt\":\"${SLOT_DATE}T10:00:00\",\"endAt\":\"${SLOT_DATE}T11:00:00\",\"memberId\":$mid}"
}
export -f fire

echo "[4/5] 워밍업 (JVM/커넥션 풀 예열 — 측정에서 제외)"
WARM=$(( N * 3 )); [ "$WARM" -gt 900 ] && WARM=900
MODE=spread TAG=warmup SLOT_DATE="2099-01-01" bash -c "seq 1 $WARM | xargs -P $WARM -I{} bash -c 'fire {}'" > "$RESULT_DIR/.warmup.log"
awk '{t=$2*1000; if(NR==1||t<mn)mn=t; if(t>mx)mx=t} END{printf "      워밍업 %d건 — 응답시간 최소 %.0f ms / 최대 %.0f ms\n", NR, mn, mx}' "$RESULT_DIR/.warmup.log"
awk '{c[$1]++} END{for(k in c) printf "      워밍업 상태코드 %s: %d건\n", k, c[k]}' "$RESULT_DIR/.warmup.log"

echo "[5/5] 측정: 동시 요청 ${C}건 (mode=$MODE, 슬롯=$SLOT_DATE 10:00~11:00)"
export TAG=loadtest-$TS
START=$(now_ms)
seq 1 "$C" | xargs -P "$C" -I{} bash -c 'fire {}' > "$RAW"
END=$(now_ms)
WALL=$(( END - START ))

SUCCESS=$(awk '$1==200' "$RAW" | wc -l | tr -d ' ')
CONFLICT=$(awk '$1==409' "$RAW" | wc -l | tr -d ' ')
OTHER=$(( C - SUCCESS - CONFLICT ))
if [ "$OTHER" -gt 0 ]; then
  echo "기타 응답(200/409 아님) 샘플 — 원인 확인용:"
  fire 1 debug | head -c 600; echo
  echo "컨테이너 상태 / OOM(메모리 부족 강제종료) 여부:"
  for c in $(dc ps -aq app); do
    docker inspect -f '  {{.Name}} state={{.State.Status}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' "$c"
  done
  echo "nginx 에러 로그 (처음 5개 — 최초 실패 원인):"
  dc logs nginx 2>&1 | grep "\[error\]" | head -5 | cut -c1-300 || true
fi
INSTANCES=$(awk '{print $3}' "$RAW" | sort -u | grep -c . || true)
pct() {  # $1 = 백분위 (ms 단위로 출력)
  awk '{print $2*1000}' "$RAW" | sort -n | awk -v p="$1" '{a[NR]=$1} END{i=int(NR*p/100+0.999999); if(i<1)i=1; printf "%.0f", a[i]}'
}
P50=$(pct 50); P95=$(pct 95); MAX=$(pct 100)

SAVED=$(dc exec -T postgres psql -U schedule -d schedule -tAc \
  "select count(*) from schedule where content='$TAG'" | tr -d ' \r')
if [ "$MODE" = same ]; then EXPECTED=1; else EXPECTED=$C; fi
RESULT=FAIL; [ "$SAVED" = "$EXPECTED" ] && RESULT=PASS

[ -f "$CSV" ] || echo "timestamp,mode,instances,concurrency,success,conflict,other,saved_rows,expected_rows,result,wall_ms,p50_ms,p95_ms,max_ms,instances_hit" > "$CSV"
echo "$TS,$MODE,$N,$C,$SUCCESS,$CONFLICT,$OTHER,$SAVED,$EXPECTED,$RESULT,$WALL,$P50,$P95,$MAX,$INSTANCES" >> "$CSV"

echo
echo "================ 결과 ($RESULT) ================"
printf "mode=%s / 인스턴스 %s개(실제 요청 받은 인스턴스 %s개) / 동시 요청 %s건\n" "$MODE" "$N" "$INSTANCES" "$C"
printf "성공(200) %s / 충돌(409) %s / 기타 %s / DB 저장 %s건 (기대 %s건)\n" "$SUCCESS" "$CONFLICT" "$OTHER" "$SAVED" "$EXPECTED"
printf "전체 소요 %s ms / 응답시간 p50 %s ms, p95 %s ms, max %s ms\n" "$WALL" "$P50" "$P95" "$MAX"
echo "원본 로그: $RAW"
echo "누적 결과: $CSV"
