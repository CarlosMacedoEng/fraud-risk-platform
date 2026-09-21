#!/usr/bin/env bash
# TS-05 Thread contention: a global synchronized block with a blocking call on the request path.
# On Java 21 a virtual thread that blocks inside synchronized PINS its carrier thread.
source "$(dirname "$0")/lib.sh"; evidence ts-05
note "INCIDENT: global lock held 10 ms per request (serialises the service: max ~100 rps), 60 rps"
lab_fault LOCK_CONTENTION '{"latencyMs":10,"errorRate":0}'
load 60 45s & L=$!
sleep 15
docker exec $DS jcmd 1 JFR.start name=ts05 duration=20s settings=profile filename=/dumps/ts05.jfr > /dev/null
docker exec $DS jcmd 1 Thread.print > "$EV/thread-dump.txt"
sleep 23
docker exec $DS sh -c 'jfr summary /dumps/ts05.jfr | grep -E "VirtualThreadPinned|JavaMonitorEnter"' > "$EV/jfr-contention-events.txt" 2>&1
docker exec $DS sh -c 'jfr print --events jdk.JavaMonitorEnter /dumps/ts05.jfr | grep -E "monitorClass|at com.fraudplatform" | sort | uniq -c | sort -rn | head -5' > "$EV/jfr-monitor-top.txt" 2>&1
prom 'sum(increase(risk_admission_rejected_total[1m]))' > "$EV/admission-rejected.txt"
wait $L
lab_clear
note "FIX: remove the global lock (fault cleared)"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
echo "carrier threads (ForkJoinPool) BLOCKED in thread dump: $(grep -A2 'ForkJoinPool-1-worker' "$EV/thread-dump.txt" | grep -c BLOCKED)" | tee -a "$EV/timeline.txt"
cat "$EV/jfr-contention-events.txt" "$EV/jfr-monitor-top.txt" | grep -v JAVA_TOOL | tee -a "$EV/timeline.txt"
echo "admission rejected (1m): $(cat "$EV/admission-rejected.txt")" | tee -a "$EV/timeline.txt"
