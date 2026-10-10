#!/bin/bash
# Stage 3 live runs on Vertex from main: the validity plan of PR 270 (paired baselines, weak-model spread, canaries). Resumable by label; stops on the first dispatch failure.
R=Sriram-Codes-SW/doorprints
REF=main
D=/tmp/claude-0/-home-user-doorprints/7272c7a8-9f14-5e71-8cda-f1a81bd09f5b/scratchpad
LOG=$D/stage3.log
touch $LOG
g(){ for t in 1 2 3 4 5; do out=$(gh api "$@" 2>/dev/null) && { echo "$out"; return 0; }; sleep 10; done; return 1; }
latest(){ g "repos/$R/actions/workflows/ai-evals.yml/runs?per_page=1" --jq '.workflow_runs[0].id'; }
waitrun(){ until [ "$(g repos/$R/actions/runs/$1 --jq .status)" = completed ]; do sleep 30; done; }
one(){ label=$1; evalset=$2; model=$3; canary=$4; types=$5
  if grep -q "^$label .* done " $LOG; then return; fi
  id=$(grep "^$label " $LOG | head -1 | awk '{print $2}')
  if [ -z "$id" ]; then
    before=$(latest)
    for t in 1 2 3; do gh api -X POST repos/$R/actions/workflows/ai-evals.yml/dispatches -f ref=$REF \
      -f 'inputs[suites]=golden-set' -f 'inputs[provider]=vertex' -f 'inputs[thinking_level]=default' \
      -f "inputs[types]=$types" -f 'inputs[repeats]=1' -f 'inputs[delay_ms]=4000' -f 'inputs[address_set]=default' \
      -f "inputs[eval_set]=$evalset" -f "inputs[canary]=$canary" -f "inputs[chat_model]=$model" >/dev/null 2>&1 && break; sleep 10; done
    for t in $(seq 1 12); do sleep 15; id=$(latest); [ -n "$id" ] && [ "$id" != "$before" ] && break; id=""; done
    [ -z "$id" ] && { echo "$label dispatch-failed" >> $LOG; exit 1; }
    echo "$label $id started head=$(g repos/$R/actions/runs/$id --jq .head_sha | cut -c1-8) set=$evalset model=${model:-default} canary=$canary" >> $LOG
  fi
  waitrun $id
  echo "$label $id done $(g repos/$R/actions/runs/$id --jq .conclusion)" >> $LOG
}
one D1 golden-set "" none extract,ask,plan
one H1 hard-set "" none extract,ask,plan
one W1 hard-set gemini-3.5-flash-lite none extract,ask,plan
one C3 golden-set "" prompt-without-rules extract,ask
one C1 golden-set "" no-sanitizer extract
one C2 golden-set "" no-citation-filter ask
one W2 golden-set gemini-3.5-flash-lite none extract,ask,plan
one D2 golden-set "" none extract,ask,plan
echo STAGE3-DONE >> $LOG
