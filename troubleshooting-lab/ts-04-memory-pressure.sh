#!/usr/bin/env bash
# TS-04 Java memory pressure: an unbounded in-memory "cache" retains 650 MB of a ~1 GB heap.
source "$(dirname "$0")/lib.sh"; evidence ts-04
docker exec $DS jcmd 1 GC.heap_info > "$EV/heap-before.txt"
load 40 90s & L=$!
sleep 15
note "INCIDENT: leak 650 MB in 50 MB steps under 40 rps"
for i in $(seq 1 13); do curl -s -X POST localhost:8080/lab/leak/50 -H "$ADMIN" > /dev/null; sleep 2; done
sleep 15
docker exec $DS jcmd 1 GC.heap_info > "$EV/heap-during.txt"
docker exec $DS jcmd 1 GC.class_histogram > "$EV/class-histogram-during.txt" 2>/dev/null
prom 'sum(rate(jvm_gc_pause_seconds_sum{application="decision-service"}[1m]))' > "$EV/gc-pause-rate.txt"
prom 'sum(rate(jvm_gc_pause_seconds_count{application="decision-service"}[1m]))' > "$EV/gc-count-rate.txt"
prom 'sum(jvm_memory_used_bytes{application="decision-service",area="heap"}) / sum(jvm_memory_max_bytes{application="decision-service",area="heap",id=~"G1 Old Gen|G1 Eden Space|G1 Survivor Space"})' > "$EV/heap-ratio.txt"
wait $L
note "FIX: release the leak (lab endpoint clears the list)"
lab_clear
sleep 5
docker exec $DS jcmd 1 GC.run > /dev/null
docker exec $DS jcmd 1 GC.heap_info > "$EV/heap-after.txt"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
for f in gc-pause-rate gc-count-rate heap-ratio; do echo "$f: $(cat "$EV/$f.txt")" | tee -a "$EV/timeline.txt"; done
grep -h "garbage-first heap" "$EV"/heap-*.txt | tee -a "$EV/timeline.txt"
sed -n '1,8p' "$EV/class-histogram-during.txt" | grep -v JAVA_TOOL | tee -a "$EV/timeline.txt"
