#!/bin/bash
# Stage 2 live runs: address variants and plan repeats on Vertex, dispatched from the #253 branch. Resumable by label. Stops on the first non-success.
R=Sriram-Codes-SW/doorprints
REF=main
D=/tmp/claude-0/-home-user-doorprints/7272c7a8-9f14-5e71-8cda-f1a81bd09f5b/scratchpad
LOG=$D/stage2.log
touch $LOG
g(){ for t in 1 2 3 4 5; do out=$(gh api "$@" 2>/dev/null) && { echo "$out"; return 0; }; sleep 10; done; return 1; }
latest(){ g "repos/$R/actions/workflows/ai-evals.yml/runs?per_page=1" --jq '.workflow_runs[0].id'; }
waitrun(){ until [ "$(g repos/$R/actions/runs/$1 --jq .status)" = completed ]; do sleep 30; done; }
one(){ label=$1; types=$2; reps=$3; set=$4
  if grep -q "^$label .* done " $LOG; then return; fi
  id=$(grep "^$label " $LOG | head -1 | awk '{print $2}')
  if [ -z "$id" ]; then
    before=$(latest)
    for t in 1 2 3; do gh api -X POST repos/$R/actions/workflows/ai-evals.yml/dispatches -f ref=$REF \
      -f 'inputs[suites]=golden-set' -f 'inputs[provider]=vertex' -f 'inputs[thinking_level]=default' \
      -f "inputs[types]=$types" -f "inputs[repeats]=$reps" -f "inputs[address_set]=$set" >/dev/null 2>&1 && break; sleep 10; done
    for t in $(seq 1 12); do sleep 15; id=$(latest); [ -n "$id" ] && [ "$id" != "$before" ] && break; id=""; done
    [ -z "$id" ] && { echo "$label dispatch-failed" >> $LOG; exit 1; }
    sha=$(g repos/$R/actions/runs/$id --jq .head_sha | cut -c1-8)
    echo "$label $id started head=$sha set=$set types=$types repeats=$reps" >> $LOG
  fi
  waitrun $id
  c=$(g repos/$R/actions/runs/$id --jq .conclusion)
  echo "$label $id done $c" >> $LOG
  [ "$c" = success ] || { echo "STOPPED-ON-$label" >> $LOG; exit 1; }
}
one S2-UNK-PLAN5 plan 5 unknown-invented
one S2-HOS-PLAN5 plan 5 hostile
one S2-UNK-EA extract,ask 1 unknown-invented
one S2-HOS-EA extract,ask 1 hostile
one S2-DEF-PLAN5 plan 5 default
one S2-DEF-EA extract,ask 1 default
echo STAGE2-DONE >> $LOG
