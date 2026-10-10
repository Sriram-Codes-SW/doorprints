#!/bin/bash
# usage: wait-pr.sh <branch> : waits until no check on the branch head is queued/in progress, prints a summary and any failed names
cd /home/user/doorprints
B=$1
sleep 120
for i in $(seq 1 90); do
  git fetch -q origin "$B"; sha=$(git rev-parse origin/$B)
  open=$(gh api repos/Sriram-Codes-SW/doorprints/commits/$sha/check-runs --paginate --jq '[.check_runs[]|select(.status!="completed")]|length' | paste -sd+ | bc)
  [ "$open" = "0" ] && break
  sleep 30
done
echo "head ${sha:0:8} open=$open"
gh api repos/Sriram-Codes-SW/doorprints/commits/$sha/check-runs --paginate --jq '[.check_runs[]|.conclusion // "running"]|group_by(.)|map("\(.[0]):\(length)")|join(" ")'
gh api repos/Sriram-Codes-SW/doorprints/commits/$sha/check-runs --paginate --jq '.check_runs[]|select(.conclusion=="failure")|"FAILED: "+.name+" "+(.id|tostring)'
echo FINISHED
