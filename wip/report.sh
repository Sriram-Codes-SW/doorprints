#!/bin/bash
# usage: report.sh <run id>  -> prints the server scorecard's verdict, metrics and failed cases
R=Sriram-Codes-SW/doorprints
aid=$(gh api repos/$R/actions/runs/$1/artifacts --jq '.artifacts[]|select(.name=="ai-eval-report")|.id')
cd /tmp && curl -sS -o rep-$1.zip -L https://api.github.com/repos/$R/actions/artifacts/$aid/zip && python3 -I - rep-$1.zip <<'PY'
import sys,zipfile,re
t=zipfile.ZipFile(sys.argv[1]).read('ai-eval-report.md').decode()
for sec in ('## Why FAIL','## Metrics\n'):
    i=t.find(sec)
    if i>=0:
        j=t.find('\n## ',i+5); print(t[i:j if j>0 else None][:2500])
print('Cases line:', re.search(r'\| Cases \| (.*?) \|',t).group(1))
i=t.find('## Failed'); print(t[i:i+3500] if i>=0 else 'no failed-case section')
PY
