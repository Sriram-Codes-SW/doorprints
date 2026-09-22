# Doorprints — Vertex AI setup guide (owner, step by step)

| Version | Date       | Author                    | Change |
|---------|------------|---------------------------|--------|
| v0.1    | 2026-09-22 | Claude (Cowork) – AI team | First version: Google Cloud project on the trial billing account, Vertex AI API, least-privilege service account, Workload Identity Federation for GitHub Actions (repo `Sriram-Codes-SW/doorprints` only), GitHub secrets/variables, budget alerts, model/location check, first small run and credit check, local and Cloud Run use, switching back to AI Studio, troubleshooting. |
| v0.2    | 2026-09-22 | Claude (Cowork) – AI team | The **AI evals** workflow now defaults to `provider` = `aistudio`; step 10 says to pick `vertex` explicitly and, after a good credit check, to ask for the default to be switched. Step 11: Vertex mode is not yet available through `docker compose` (requested from the compose owner, ai-design.md section 2). Troubleshooting: chat errors now carry a `setupHint` naming `GCP_LOCATION`. |
| v0.3    | 2026-09-22 | Claude (Cowork) – Docs team | **Owner's setup outcome recorded** (2026-09-22): new section "Status of this project's setup"; project id `doorprints-ai`; step 8 results as a worked example (chat `gemini-3.5-flash` answers in `asia-south1`; `gemini-embedding-2` answers 404 there and works on `global`), so the repository variables are `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global` (step 6); data-residency note (chat processed in India, embedding text on the `global` endpoint); trial credit ends **22 Dec 2026**, new step 14 "Before the trial ends" (export by about 15 Dec, then switch back to AI Studio or upgrade billing; checklist in [runbook 10.4](../08-operations-runbook.md)). Steps 11 and 12 add `AI_VERTEX_EMBEDDING_LOCATION=global`. Step 4.5: the stricter attribute condition (repository and `refs/heads/main`) asked for by the DevSecOps review of `ai-evals.yml` is now the recommended one. |
| v0.4    | 2026-09-22 | Claude (Cowork) – Docs team | Status: step 10 is **started**. The first `provider=vertex` eval run 35753477789 (`gemini-3.5-flash` in `asia-south1` + `gemini-embedding-2` on `global`, commit `8f583af`) failed only on citationPrecision 0.78 (7/9); commit `feb0294` answers it (golden set v0.5, inline-marker citation rule) without lowering thresholds. The re-run and the credit check are still open, so the workflow default stays `aistudio`. Step 9 stays open. |

This guide switches the Doorprints AI features from the Gemini API in Google AI Studio to **Google Cloud Vertex AI**
(Google renamed it "Gemini Enterprise Agent Platform" in 2026; the console may show either name, and the API is still
called **Vertex AI API**, `aiplatform.googleapis.com`). Why and what changes: [ai-design.md](ai-design.md) sections
2.1 and 3.3. Nothing in this guide creates a key or password: GitHub Actions gets short-lived tokens through Workload
Identity Federation, and the app uses Application Default Credentials.

Time: about 45 minutes. You need: owner access to the GitHub repository `Sriram-Codes-SW/doorprints`, a Google account,
and the Google Cloud Free Trial ($300 credit, 90 days) already activated on that account.

> **Money warning.** Vertex AI has no free tier: every call is billed and, we believe, paid from the trial credit.
> That is **not verified** yet (Google's trial page only says the credit cannot pay for "Gemini API in AI Studio"
> costs). Do steps 7 (budget alerts) and 10 (credit check) before running anything big. Budget alerts only send
> e-mails; they do not stop spending.

## Status of this project's setup (2026-09-22)

The owner reported the Google Cloud setup done on 2026-09-22 and the GitHub secrets and variables set. What is
recorded here comes from the owner's report; nothing in it could be checked from the build environment.

| Item | Result |
|---|---|
| Project | id **`doorprints-ai`** on the trial billing account |
| Steps 1 to 6 | Done (APIs, service account with Vertex AI User only, Workload Identity Federation, GitHub secrets `GCP_WIF_PROVIDER`, `GCP_SA_EMAIL` and the variables below) |
| Step 7 budget alerts | Reported as part of the setup; confirm in Billing > Budgets & alerts that the three thresholds exist and that credits are unticked |
| Step 8 models | Chat `gemini-3.5-flash` **works in `asia-south1`**. Embeddings `gemini-embedding-2` are **not available in `asia-south1`** (404) and **work on `global`**. Worked example in step 8 |
| GitHub variables (step 6) | `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global` |
| Data residency | Chat prompts and answers are processed in Mumbai (`asia-south1`). **Embedding text** (the redacted house text at every save or re-index, and every Ask question, [ai-design.md](ai-design.md) 9.1) is processed on the **`global`** endpoint, which gives no India residency guarantee (Google picks the region). Recorded in [02](../02-threat-model.md) T-I20 and [ai-design.md](ai-design.md) 2.1 |
| Step 9 | Open: capture real responses for the contract tests |
| Step 10 | **Started.** First `provider=vertex` eval: **AI evals** run [35753477789](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/35753477789) (manual, commit `8f583af`, 2026-09-22 16:20 UTC; chat `gemini-3.5-flash` in `asia-south1`, embeddings `gemini-embedding-2` on `global`) **failed only on citationPrecision 0.78** (7/9 cited houses correct; the other metrics passed). Cause: ask-01 named the Blue gate house as best and also cited the two other houses with a water fact. Fix in commit `feb0294` (AI change set): golden set v0.5 allows those two as a grounded comparison, and the server now counts only houses marked inline as `[house:id]` ([ai-design.md](ai-design.md) 8.3, 8.5). **Thresholds were not lowered.** Still open: a re-run on `feb0294` or later, and the **credit check** (did the run's cost come off the $300 trial credit?). The `ai-evals.yml` default stays `aistudio` until both are done |
| Trial end | The $300 credit ends on **22 Dec 2026**. Step 14 and [runbook 10.4](../08-operations-runbook.md): export by about **15 Dec 2026**, then switch back to AI Studio or upgrade billing before the 22nd |

Values used below (replace with yours where noted):

| Placeholder | Example (this project's value where known) | Where you get it |
|---|---|---|
| `PROJECT_ID` | `doorprints-ai` (this project) | step 1 (unique across Google Cloud) |
| `PROJECT_NUMBER` | `123456789012` | step 1 (Dashboard > Project info) |
| `LOCATION` | `asia-south1` (Mumbai) for chat; `global` for embeddings (this project, step 8) | step 8 decides; `global` is the fallback |
| Service account | `doorprints-ai@PROJECT_ID.iam.gserviceaccount.com` | step 3 |
| Pool / provider | `github` / `github-actions` | step 4 |

Every step shows the console clicks first and the same thing as a `gcloud` command; use either. The commands run in
**Cloud Shell** (the `>_` icon at the top right of the console), where `gcloud` is already installed and logged in.

---

## Step 1. Create (or pick) the project on the trial billing account

1. Open <https://console.cloud.google.com/>.
2. Top bar: click the **project picker** (left of the search box) > **NEW PROJECT**.
3. **Project name**: `doorprints-ai`. Under the name, Google shows the **Project ID** (for example
   `doorprints-ai-472915`); click **EDIT** if you want to change it now (it can never be changed later). Write it down.
4. **Billing account**: choose the account that says **My Billing Account** / "Free Trial" (the one with the $300
   credit). **Location**: "No organization" is fine. Click **CREATE**.
5. When it is created, select it in the project picker. On the **Dashboard**, the **Project info** card shows the
   **Project number** (digits only). Write it down too.
6. Check billing: menu (three lines, top left) > **Billing** > it must show the trial credit and the project under
   **My projects**. If the project has "Billing disabled": Billing > **Account management** > **My projects** > the
   three dots next to the project > **Change billing** > pick the trial account.

```bash
gcloud projects create PROJECT_ID --name="doorprints-ai"
gcloud billing accounts list                       # note the ACCOUNT_ID of "My Billing Account"
gcloud billing projects link PROJECT_ID --billing-account=ACCOUNT_ID
gcloud config set project PROJECT_ID
gcloud projects describe PROJECT_ID --format="value(projectNumber)"   # PROJECT_NUMBER
```

## Step 2. Enable the APIs

1. Menu > **APIs & Services** > **Library**.
2. Search **Vertex AI API** > open it > **ENABLE** (takes up to a minute).
3. Also enable, the same way, **IAM Service Account Credentials API** and **Security Token Service API** (Workload
   Identity Federation needs both).

```bash
gcloud services enable aiplatform.googleapis.com iamcredentials.googleapis.com sts.googleapis.com
```

## Step 3. Create the service account (Vertex AI User only)

1. Menu > **IAM & Admin** > **Service Accounts** > **+ CREATE SERVICE ACCOUNT**.
2. **Service account name**: `doorprints-ai`; description: "Doorprints AI evals and backend (Vertex AI only)".
   **CREATE AND CONTINUE**.
3. **Grant this service account access to project** > **Select a role** > type `Vertex AI User` > pick
   **Vertex AI User** (`roles/aiplatform.user`). Add **no other role**. **CONTINUE** > **DONE**.
4. Do **not** open the **Keys** tab and do not create a key: no key is needed anywhere.

```bash
gcloud iam service-accounts create doorprints-ai \
  --display-name="Doorprints AI evals and backend (Vertex AI only)"
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:doorprints-ai@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/aiplatform.user"
```

## Step 4. Workload Identity Pool and GitHub provider (only this repository)

1. Menu > **IAM & Admin** > **Workload Identity Federation** > **GET STARTED** (or **+ CREATE POOL**).
2. Pool: **Name** `github`, **Pool ID** `github`, description "GitHub Actions". **CONTINUE**.
3. Provider: **Select a provider** = **OpenID Connect (OIDC)**; **Provider name** `github-actions`, **Provider ID**
   `github-actions`; **Issuer (URL)** `https://token.actions.githubusercontent.com`; **Audiences**: keep
   **Default audience**. **CONTINUE**.
4. **Configure provider attributes** (click **ADD MAPPING** for each extra row):

   | Google | OIDC |
   |---|---|
   | `google.subject` | `assertion.sub` |
   | `attribute.repository` | `assertion.repository` |
   | `attribute.repository_owner` | `assertion.repository_owner` |
   | `attribute.ref` | `assertion.ref` |

5. **Attribute conditions** > **ADD CONDITION** and paste exactly:

   ```
   assertion.repository == 'Sriram-Codes-SW/doorprints'
   ```

   This is the important security line: tokens from any other GitHub repository (including forks) are rejected.
   **Recommended (DevSecOps review of `ai-evals.yml`, 2026-09-22), now that the repository is public:**
   `assertion.repository == 'Sriram-Codes-SW/doorprints' && assertion.ref == 'refs/heads/main'`
   (then the eval can only be run from `main`). If you used the shorter condition, edit the provider and replace it;
   DevSecOps tracks this as open until the owner confirms it.
6. **SAVE**. Open the provider and copy its full name from the top of the page; it looks like
   `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/github-actions`. This is the value
   of the GitHub secret `GCP_WIF_PROVIDER` (step 6).

```bash
gcloud iam workload-identity-pools create github --location=global --display-name="GitHub Actions"
gcloud iam workload-identity-pools providers create-oidc github-actions \
  --location=global --workload-identity-pool=github --display-name="github-actions" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository == 'Sriram-Codes-SW/doorprints'"
gcloud iam workload-identity-pools providers describe github-actions \
  --location=global --workload-identity-pool=github --format="value(name)"   # GCP_WIF_PROVIDER
```

## Step 5. Let the repository use the service account (Workload Identity User)

1. Menu > **IAM & Admin** > **Service Accounts** > click `doorprints-ai@…` > tab **PRINCIPALS WITH ACCESS** (older
   consoles: **PERMISSIONS**) > **GRANT ACCESS**.
2. **New principals**: paste (one line, your project **number**):

   ```
   principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/Sriram-Codes-SW/doorprints
   ```

3. **Role**: **Workload Identity User** (`roles/iam.workloadIdentityUser`). **SAVE**.

   (Alternatively, on the pool page: **GRANT ACCESS** > "Grant access using service account impersonation" > the
   service account > attribute name `repository`, value `Sriram-Codes-SW/doorprints`.)

```bash
gcloud iam service-accounts add-iam-policy-binding \
  doorprints-ai@PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/Sriram-Codes-SW/doorprints"
```

## Step 6. Add the GitHub secrets and variables

GitHub > repository `Sriram-Codes-SW/doorprints` > **Settings** > **Secrets and variables** > **Actions**.

Tab **Secrets** > **New repository secret** (twice):

| Name | Value |
|---|---|
| `GCP_WIF_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/github-actions` |
| `GCP_SA_EMAIL` | `doorprints-ai@PROJECT_ID.iam.gserviceaccount.com` |

Tab **Variables** > **New repository variable** (twice):

| Name | Value |
|---|---|
| `GCP_PROJECT_ID` | `PROJECT_ID` (the id, not the number) |
| `GCP_LOCATION` | `asia-south1`, or what step 8 tells you |

Optional variable `AI_VERTEX_EMBEDDING_LOCATION` only if step 8 shows the embedding model is not available in
`GCP_LOCATION`.

**This project (set 2026-09-22):** `GCP_PROJECT_ID` = `doorprints-ai`, `GCP_LOCATION` = `asia-south1`,
`AI_VERTEX_EMBEDDING_LOCATION` = `global` (step 8: the embedding model is not offered in `asia-south1`).
`ai-evals.yml` reads all three (`vars.GCP_PROJECT_ID`, `vars.GCP_LOCATION`, `vars.AI_VERTEX_EMBEDDING_LOCATION`). (The workflow also accepts `GCP_PROJECT_ID` / `GCP_LOCATION` as secrets; variables are fine, they
are not secret.) Keep the existing `AI_API_KEY` secret: it is what `provider=aistudio` uses.

## Step 7. Budget alerts at $50, $150 and $250

1. Menu > **Billing** > **Budgets & alerts** > **+ CREATE BUDGET**.
2. **Name**: `doorprints-ai trial`. **Time range**: Monthly (or "Custom range" covering the 90 trial days).
   **Projects**: only `doorprints-ai`. **Services**: all.
3. **Credits**: **untick** "Discounts", "Promotions and others" (the trial credit is a promotion). Otherwise the
   credit cancels the cost, the budget sees $0 and no alert ever fires. **NEXT**.
4. **Amount**: Budget type **Specified amount**, **Target amount** `250`. **NEXT**.
5. **Thresholds** (percent of 250, trigger on **Actual**): `20` ($50), `60` ($150), `100` ($250). Delete other rows.
6. **Notifications**: tick **Email alerts to billing admins and users**. **FINISH**.

Remember: alerts are e-mails, not a spending cap. To stop spending, disable the Vertex AI API (step 2 page >
**DISABLE API**) or unlink billing from the project.

## Step 8. Check that the models exist in your location (2 minutes, costs a fraction of a cent)

Mumbai (`asia-south1`) keeps processing in India, but not every model is offered in every region, and this could not
be verified from the build environment. In Cloud Shell (it uses your own login, which is fine for this check):

```bash
PROJECT_ID=your-project-id
LOC=asia-south1
HOST=$LOC-aiplatform.googleapis.com         # for LOC=global use HOST=aiplatform.googleapis.com
TOKEN=$(gcloud auth print-access-token)

# Chat
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://$HOST/v1beta1/projects/$PROJECT_ID/locations/$LOC/publishers/google/models/gemini-3.5-flash:generateContent" \
  -d '{"contents":[{"role":"user","parts":[{"text":"Say OK"}]}],"generationConfig":{"maxOutputTokens":16}}'

# Embeddings (768 dimensions)
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://$HOST/v1beta1/projects/$PROJECT_ID/locations/$LOC/publishers/google/models/gemini-embedding-2:embedContent" \
  -d '{"content":{"parts":[{"text":"Blue gate, Indiranagar"}]},"embedContentConfig":{"outputDimensionality":768}}' | head -c 300
```

- Both return JSON with `candidates` / `embedding` → keep `GCP_LOCATION=asia-south1`.
- Chat works but embeddings say **404 NOT_FOUND** ("Publisher Model … was not found") → set the variable
  `AI_VERTEX_EMBEDDING_LOCATION=global` (repeat the embedding call with `LOC=global` first).
- Chat says 404 too → use `GCP_LOCATION=global` (widest availability; Google picks the region, so no India
  residency guarantee) or `us-central1`.
- Want Flash-Lite? Repeat the chat call with `gemini-3.5-flash-lite`.
- **403 SERVICE_DISABLED** → step 2 not done or still propagating (wait 2 minutes).

**Worked example: this project's results (owner, 2026-09-22).**

| Call | `LOC=asia-south1` | `LOC=global` |
|---|---|---|
| Chat `gemini-3.5-flash:generateContent` | Works (JSON with `candidates`) | not needed |
| Embeddings `gemini-embedding-2:embedContent` | **404 NOT_FOUND** ("Publisher Model … was not found") | Works (JSON with `embedding.values`) |

So the second bullet above applied: keep `GCP_LOCATION=asia-south1` and set `AI_VERTEX_EMBEDDING_LOCATION=global`.
What that means for data residency: chat prompts and answers (Ask, extraction, planner) stay in India; the text that is
embedded (the redacted house text at every save or re-index, and every Ask question) is processed wherever Google's
`global` endpoint routes it, with no India residency guarantee. The embedding text never contains the contact name or
phone ([ai-design.md](ai-design.md) 9.1). If India-only processing becomes a requirement, re-run the embedding check
from time to time (Google adds models to regions) and remove the variable when `asia-south1` answers, or try
`gemini-embedding-001` (`:predict`, 768 dimensions; needs a full re-index) in `asia-south1`.
`gemini-3.5-flash-lite` was not checked.

## Step 9. Save real responses for the contract tests (5 minutes, optional but asked for by review)

The contract tests (`VertexGenerateContentContractTest`, `VertexEmbeddingContractTest`) use response bodies built
from Google's SDK source and reference docs, not captured live. With the step 8 commands, save one real body of each
kind and give them to the AI team (or open a PR), shortening any long vector to its first 5 numbers:

```bash
curl … gemini-3.5-flash:generateContent … > generate.json
curl … gemini-embedding-2:embedContent … > embed.json
```

For a 429 and a 403 example: the eval's scorecard and logs never contain bodies, so copy them from the step 8 curl
output if you ever see one. Remove your project number from anything you share if you prefer.

## Step 10. First small run and credit check

1. GitHub > **Actions** > **AI evals** > **Run workflow**: change `provider` to **vertex** (the default is still
   `aistudio` until this guide is done), `types` = **extract** (4 cases, about 4 chat calls, no embeddings), leave
   the rest. **Run workflow**.
2. Open the run: the step **Authenticate to Google Cloud** must be green; the job summary shows the scorecard with
   "Provider: vertex …". If it failed, see Troubleshooting.
3. Wait: billing data appears after a few hours (up to 24 h).
4. Menu > **Billing** > **Reports**. Filters on the right: **Projects** = `doorprints-ai`; **Services** =
   **Vertex AI** (may be listed as "Gemini Enterprise Agent Platform"); **Group by** = **SKU**; time range = today.
   Tick **Show net cost** / look at the **Credits** column (older layout: **Promotions and others**).
   - **Credit applied (good):** the Vertex SKUs (e.g. "Gemini 3.5 Flash … input tokens") show a cost and an equal
     negative credit, net `0.00`, and Billing > **Overview** shows the credit balance went down a little.
   - **Not covered:** the net cost is positive and the credit balance is unchanged. Then stop, decide whether to pay,
     or switch back to AI Studio (step 13).
5. Only after a good result: run the full eval (`types` = `extract,ask,plan`).
6. Then ask for the workflow default to become `vertex` (a one-line change to the `provider` input in
   `.github/workflows/ai-evals.yml`, made by the AI team and reviewed by DevSecOps). Until then just pick `vertex`
   each time.

## Step 11. Local development with Vertex AI

```bash
gcloud auth application-default login                  # opens the browser once
gcloud auth application-default set-quota-project PROJECT_ID
export APP_AI_ENABLED=true AI_PROVIDER=vertex GCP_PROJECT_ID=PROJECT_ID GCP_LOCATION=asia-south1
export AI_VERTEX_EMBEDDING_LOCATION=global              # this project: the embedding model is not offered in asia-south1
cd backend && mvn spring-boot:run
```

Your own Google account needs the **Vertex AI User** role on the project (as project owner you already have more).
No `AI_API_KEY` is needed. `POST /api/ai/reindex` once after switching.

**Not yet with `docker compose`:** `docker-compose.yml` does not pass `AI_PROVIDER`, `GCP_PROJECT_ID`,
`GCP_LOCATION` or the credentials file to the container yet (requested from its owner, see ai-design.md section 2).
Until that lands, run the backend with `mvn spring-boot:run` as above (the database can still come from compose:
`docker compose up db`).

## Step 12. Cloud Run (when the backend is deployed there)

In the Cloud Run service > **EDIT & DEPLOY NEW REVISION** > **Security** tab > **Service account** =
`doorprints-ai@PROJECT_ID.iam.gserviceaccount.com`; **Variables & Secrets**: `APP_AI_ENABLED=true`,
`AI_PROVIDER=vertex`, `GCP_PROJECT_ID=PROJECT_ID`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global`
(this project, step 8). The app then gets tokens from the
metadata server; no key, no `GOOGLE_APPLICATION_CREDENTIALS`.

## Step 13. Switch back to AI Studio (any time, nothing to delete)

- App: set `AI_PROVIDER=aistudio` (or remove the variable; `aistudio` is the default) and make sure `AI_API_KEY` is
  set. Same models, so the index stays usable; run `POST /api/ai/reindex` once to be safe.
- Evals: **Run workflow** with `provider` = **aistudio** (uses the `AI_API_KEY` secret).
- To stop all Google Cloud cost: disable the Vertex AI API in the project, or delete the project
  (IAM & Admin > Settings > **SHUT DOWN**).

## Step 14. Before the trial ends (credit ends 22 Dec 2026)

The trial does not charge automatically: when the credit or the 90 days run out, billed services in the project stop
until the billing account is upgraded. Cloud AI with `AI_PROVIDER=vertex` then fails (setup hints or a generic `503`),
while everything else in Doorprints keeps working. Plan it instead of finding out:

1. **By about 15 Dec 2026**: export anything worth keeping from Google Cloud (staging data if any, eval reports you
   want beyond the artifact retention, billing reports as CSV), and note the credit left (Billing > Overview).
2. **Decide** (product owner): (a) **switch back to AI Studio** (step 13; `AI_PROVIDER=aistudio` for the app, the
   default `aistudio` for evals; real data then only with a paid Gemini API key, [01](../01-requirements.md) PRV-022),
   or (b) **upgrade the billing account** to paid and keep Vertex AI, with the spend cap budget and alerts of
   [runbook 10.2](../08-operations-runbook.md) in place **before** upgrading.
3. **Before 22 Dec 2026**: carry out the decision, then run one small eval (`types` = `extract`) on the chosen
   provider to prove it works.
4. If Vertex AI is not kept: disable the Vertex AI API or shut down the project, and delete the WIF secrets
   `GCP_WIF_PROVIDER` and `GCP_SA_EMAIL` (the variables can stay).

The full checklist, including the staging tear-down, is [runbook 10.4](../08-operations-runbook.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Workflow: "Vertex AI eval needs: GCP_WIF_PROVIDER(secret) …" | step 6 incomplete | add the named secret/variable |
| Auth step: `unauthorized_client … rejected by the attribute condition` | run from another repository/fork, or a typo in the condition | step 4.5: exactly `Sriram-Codes-SW/doorprints` (case matters) |
| Auth step or first call: `Permission 'iam.serviceAccounts.getAccessToken' denied` | step 5 missing or wrong project **number** in the principal | redo step 5 |
| `IAM Service Account Credentials API has not been used` | step 2.3 | enable it, wait 2 min |
| Scorecard error `HTTP 403 (SERVICE_DISABLED)` | Vertex AI API not enabled | step 2 |
| `HTTP 403 (IAM_PERMISSION_DENIED)` | service account lacks Vertex AI User | step 3.3 |
| `HTTP 404 (NOT_FOUND)` "Publisher Model … not found" | model not offered in that location | step 8: `AI_VERTEX_EMBEDDING_LOCATION` / `GCP_LOCATION=global` |
| App answers 503 with `"setupHint": "Vertex AI answered 404: the chat model … set GCP_LOCATION=global …"` (also in the scorecard warnings and the backend log) | the chat model is not offered in `GCP_LOCATION` (likely with the default `asia-south1`) | set `GCP_LOCATION=global` (repository variable for the eval, env var for the app), restart; step 8 |
| Result **STOPPED: provider quota exhausted** | HTTP 429 `RESOURCE_EXHAUSTED` (per-minute quota or shared capacity) | wait a few minutes, raise `delay_ms` (e.g. 10000), run fewer `types`; a trial account cannot request quota increases |
| App start: "AI_PROVIDER=vertex needs Google Application Default Credentials" | no ADC locally | step 11 |
| App start: "AI_PROVIDER=vertex needs GCP_PROJECT_ID" | variable missing | set `GCP_PROJECT_ID` |
