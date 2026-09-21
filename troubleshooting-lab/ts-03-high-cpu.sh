#!/usr/bin/env bash
# TS-03 High Java CPU: an expensive computation appears on the request path (lab fault CPU_BURN 15 ms/request).
source "$(dirname "$0")/lib.sh"; evidence ts-03
note "INCIDENT: +15 ms CPU per request, 40 rps"
lab_fault CPU_BURN '{"latencyMs":15,"errorRate":0}'
load 40 60s & L=$!
sleep 20
prom 'max(process_cpu_usage{application="decision-service"})' > "$EV/process-cpu.txt"
docker exec $DS jcmd 1 JFR.start name=ts03 duration=25s settings=profile filename=/dumps/ts03.jfr > /dev/null
sleep 28
docker exec $DS jfr view --width 160 hot-methods /dumps/ts03.jfr > "$EV/jfr-hot-methods.txt" 2>&1
docker exec $DS jfr view --width 160 thread-cpu-load /dumps/ts03.jfr > "$EV/jfr-thread-cpu.txt" 2>&1
wait $L
lab_clear
note "FIX: remove the hot code path (fault cleared)"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
echo "process cpu: $(cat "$EV/process-cpu.txt")" | tee -a "$EV/timeline.txt"
grep -v JAVA_TOOL "$EV/jfr-hot-methods.txt" | sed -n '4,9p' | tee -a "$EV/timeline.txt"
