# ai-test-triage-service

A standalone service that automatically diagnoses why an automated test failed,
using a local LLM grounded in past incidents from the same project — with a
web dashboard to browse the results. It doesn't know anything about any specific
test framework, language, or project; it just accepts failure evidence over HTTP
and returns a diagnosis.

For a ready-made Cucumber + Selenium integration, see
[ai-test-triage-sdk-java](https://github.com/Amr-Hossam12/ai-test-triage-sdk-java) —
it's the client library that talks to this service. Any other test tool can
integrate the same way: send it a `POST /api/triage` request.

## How it works

1. **Retrieval** — a failure's exception + page-source evidence gets embedded
   (`nomic-embed-text`) and compared, via cosine similarity over a flat JSONL file,
   against past incidents from the *same project* (`projectId`-scoped, so unrelated
   projects don't contaminate each other's context). No vector database — at the
   realistic scale of one project's failure history (dozens to hundreds of
   incidents), a linear in-memory scan is simpler and just as fast. Human-verified
   cases are preferred over unverified AI guesses at equal similarity. **If nothing
   in the current project clears the similarity threshold, retrieval falls back to
   a search across all *other* projects — but only considers cases another human
   has already verified there**, so a brand-new project isn't starting from zero
   grounding, without risking an unverified guess from one project corrupting
   another's diagnoses.
2. **Generation** — the current evidence plus the most similar past incidents get
   sent to a local LLM (`llama3.1:8b` via Ollama) using JSON-mode, constrained to
   return a classification (one of 5 fixed categories), a reasoning explanation,
   a suggested next step, and a self-reported **confidence** (`HIGH`/`MEDIUM`/`LOW`
   — the model is instructed not to default to `HIGH` and to use `LOW` whenever the
   evidence is thin). Each retrieved past incident is tagged with a short
   `[Case xxxxxxxx]` id and labeled human-confirmed vs. unconfirmed vs.
   cross-project, and the model is asked to cite that tag in its reasoning if it
   relied on it. The classification is validated against the allowed set —
   malformed output falls back to `UNKNOWN` rather than corrupting the store.
3. **Self-check pass** — a second, independent classification call runs over the
   same evidence with no knowledge of the first pass's answer. If it disagrees
   with the first pass, the stored classification isn't overridden (a human
   should be the one to pick a winner), but confidence is forced to `LOW` and the
   case is flagged (`selfCheckFlag`) so it surfaces at the top of the Review
   Queue instead of blending in as a normal-confidence case. Any failure in this
   second call is swallowed — it's a bonus signal, not a required part of the
   pipeline.
4. **Structured locator extraction** — a best-effort, framework-agnostic regex
   pass (`LocatorExtractor`) pulls the failing selector/strategy (e.g.
   `css selector: #checkout-submit`) out of the raw exception message, when the
   message follows a recognizable shape (Selenium's JSON locator format,
   `By.xpath(...)`-style calls, or a bare CSS id/class token). This is what
   locator-health tracking and flaky-test detection below are built on.
5. **Storage** — every triaged case (with its screenshot, if one was sent, its
   confidence, self-check flag, extracted locator, and the list of past cases
   that grounded its diagnosis) is appended to the knowledge base, so it's
   available as grounding context for the next failure, even seconds later.
6. **Human verification (feedback loop)** — a person can confirm or correct any
   case's classification (`POST /api/failures/{id}/verify`). This is what step 1's
   "human-verified" preference and step 2's "human-confirmed" labeling actually
   draw on — corrections made today change tomorrow's diagnosis for the same
   failure pattern, including across projects. Verification is manual, not
   automatic: an unverified wrong classification will keep being treated as an
   "unconfirmed AI guess" (lower trust, but not excluded) until someone reviews it
   from the dashboard's Review Queue, which now sorts lowest-confidence-first
   rather than just oldest-first.
7. **Notifications** — after every diagnosis, a pluggable notifier chain fires
   (console logging always on; an MS Teams webhook opt-in via
   `-Dtriage.teamsWebhookUrl=...` - the messaging tool actually used internally - and a Slack
   webhook opt-in via `-Dtriage.slackWebhookUrl=...` kept available as an equally pluggable
   option, no code changes needed to enable either later). Both post a rich card with a
   screenshot thumbnail (if one was captured) and a "View in Dashboard" button that deep-links
   straight to the case (`/?case={id}`).
8. **Dashboard** — a single self-contained HTML page lists every case, filterable
   by project or classification, with: a Model Accuracy panel; a Locator Health
   panel (recurring broken locators, grouped and counted); a Flaky Tests panel
   (tests that have failed with more than one distinct classification across
   runs, marked with a ⚡ icon on the case itself too); a Failures Over Time
   trend chart (stacked by category, per day); a Review Queue (unverified cases,
   lowest-confidence first); and a click-through detail view showing the full
   reasoning, suggestion, confidence, self-check flag (if any), extracted
   locator, screenshot, the human-verification control, and a clickable
   "Similar Past Incidents Used" list that jumps straight to whatever case(s)
   grounded that diagnosis — even one from a different project.

## Setup, from scratch

### 1. Install Ollama

Download and install from **[ollama.com](https://ollama.com)** (Windows/Mac/Linux
installers available there). After installing, Ollama runs as a background
service/tray app and exposes its API at `http://localhost:11434`.

Verify it's running:

```bash
ollama --version
```

If that hangs or errors, open the Ollama app once, or run any `ollama` command
(e.g. `ollama list`) — it starts the background server automatically if it isn't
already running.

### 2. Pull the two required models

```bash
ollama pull llama3.1:8b
ollama pull nomic-embed-text
```

`llama3.1:8b` is the model that does the actual classification/reasoning (~4.9 GB
download). `nomic-embed-text` is a small embedding model used for retrieval
(~274 MB). Both run entirely locally — no API key, no data leaves your machine.

Confirm both downloaded correctly:

```bash
ollama list
```

You should see both `llama3.1:8b` and `nomic-embed-text` in the output.

### 3. Confirm Ollama's API is actually reachable

```bash
curl http://localhost:11434/api/tags
```

This should return JSON listing your installed models. If this fails, the rest
of this service won't work — fix this first before continuing.

### 4. Install Java 21+ and Maven

Check what you already have:

```bash
java -version
mvn -version
```

If either is missing: get a JDK 21+ build from
[adoptium.net](https://adoptium.net) (Eclipse Temurin), and Maven from
[maven.apache.org](https://maven.apache.org/download.cgi). Make sure both are on
your `PATH` (`java`/`mvn` should run from any terminal) and that `JAVA_HOME`
points at the JDK.

### 5. Clone and build this repo

```bash
git clone https://github.com/Amr-Hossam12/ai-test-triage-service.git
cd ai-test-triage-service
mvn package
```

This compiles the service and copies its dependencies into `target/lib/`.

### 6. Run it

```bash
java -cp "target/classes;target/lib/*" com.aitriage.Main
```

(On Linux/macOS, use `:` instead of `;` in the classpath.)

You should see console output confirming the dashboard URL, the API URL, and
which Ollama models it's configured to use. Leave this running in its own
terminal — it's a server, it doesn't exit on its own.

### 7. Verify it's actually working end-to-end

In a **separate** terminal, with the service still running:

```bash
curl -X POST http://localhost:8787/api/triage -H "Content-Type: application/json" -d "{\"projectId\":\"smoke-test\",\"testName\":\"manual check\",\"exceptionType\":\"TimeoutException\",\"exceptionMessage\":\"element not found\",\"pageSource\":\"<html><body></body></html>\"}"
```

This exercises the full pipeline — embedding, retrieval, and the LLM call — and
should return a JSON object with `classification`, `reasoning`, and `suggestion`
filled in (it may take several seconds the first time, while Ollama loads the
model into memory). Then open **http://localhost:8787/** in a browser — that
same test case should now appear in the dashboard.

If this step works, the service is fully set up. From here, either integrate
[ai-test-triage-sdk-java](https://github.com/Amr-Hossam12/ai-test-triage-sdk-java)
into a Cucumber+Selenium project, or point any other tool at
`POST /api/triage` directly.

### Configuration

Optional system properties, all with sensible defaults:

| Property                 | Default                   | Description                          |
|---------------------------|----------------------------|---------------------------------------|
| `-Dtriage.port`           | `8787`                     | HTTP port                             |
| `-Dtriage.dataDir`        | `data`                     | Where the knowledge base + screenshots are stored |
| `-Dtriage.ollamaBaseUrl`  | `http://localhost:11434`   | Ollama's API URL                      |
| `-Dtriage.embedModel`     | `nomic-embed-text`         | Embedding model                       |
| `-Dtriage.chatModel`      | `llama3.1:8b`              | Generation model                      |
| `-Dtriage.slackWebhookUrl`| unset                       | If set, also posts each diagnosis to this Slack incoming webhook |
| `-Dtriage.teamsWebhookUrl`| unset                       | If set, also posts each diagnosis to this MS Teams incoming webhook - this is the notification channel actually used internally; Slack is kept as an equally pluggable option in case that changes |
| `-Dtriage.dashboardBaseUrl`| `http://localhost:<port>` | Base URL used to build the "View in Dashboard" links in Slack/Teams notifications - override this to the machine's real hostname/IP if the service isn't running on the same box as whoever's viewing the notification |

## Dashboard

Open **http://localhost:8787/** in a browser:

- **Stat cards** — total failures and a count per classification.
- **Model Accuracy panel** — overall and per-category confirm/correct rate,
  computed from real human verifications (empty until at least one case has
  been verified).
- **Locator Health panel** — failures grouped by (project, test, extracted
  locator), showing any locator that has broken more than once with an
  occurrence count and last-seen time; click a row to jump to that case.
- **Flaky Tests panel** — tests that have failed with more than one *distinct*
  classification across their recorded runs (same test, different failure
  shape each time), with the mix of classifications and run count; click a row
  to jump to the latest occurrence. Matching cases in the main table and detail
  view also get a ⚡ icon next to the classification.
- **Failures Over Time** — a stacked bar chart (one bar per day, segments
  colored by classification) so a category trending up or down is visible at a
  glance instead of only ever showing a single snapshot.
- **Review Queue** — a sidebar filter showing only unverified cases, sorted
  lowest-confidence-first (falls back to oldest-first within the same
  confidence level), so the cases most likely to be wrong get reviewed first.
- **Case table** — every triaged failure with a color-coded classification
  badge, a confidence badge, a checkmark on any case a human has verified, a
  ⚠ icon on any case flagged by the self-check pass, and a ⚡ icon on any case
  belonging to a flaky test; click a row for the full detail.
- **Detail panel** — includes a Confirm/Update control to record a human
  verification, a self-check disagreement callout when applicable, the
  extracted failing locator (if one could be parsed), and a "Similar Past
  Incidents Used" list — the actual cases that grounded this diagnosis, each
  clickable to jump straight to that case (even one from a different project,
  fetched on demand if it isn't already loaded).
- **Deep links** — `/?case={id}` opens the dashboard with that case
  pre-selected; this is what the Slack/Teams notification buttons use.

## API

**`POST /api/triage`**

```json
{
  "projectId": "my-project",
  "testName": "the test that failed",
  "exceptionType": "TimeoutException",
  "exceptionMessage": "...",
  "pageSource": "...",
  "screenshotBase64": "... (optional)"
}
```

Returns:

```json
{
  "id": "...",
  "classification": "...",
  "reasoning": "...",
  "suggestion": "...",
  "confidence": "HIGH | MEDIUM | LOW",
  "selfCheckFlag": "... or null",
  "similarCases": [
    { "id": "...", "projectId": "...", "testName": "...", "classification": "...",
      "verified": true, "crossProject": false }
  ]
}
```

**`GET /api/failures?projectId=`** — list all triaged cases, optionally filtered by project.

**`GET /api/failures/{id}`** — full detail for one case.

**`POST /api/failures/{id}/verify`** — record a human confirmation/correction:

```json
{ "classification": "LOCATOR_BROKEN" }
```

**`GET /api/metrics?projectId=`** — aggregate accuracy stats (overall and per
category) computed from verified cases only.

**`GET /api/locator-health?projectId=`** — failures grouped by (project, test,
extracted locator) with occurrence counts, sorted most-recurring first.

**`GET /api/flaky-tests?projectId=`** — (project, test) groups that have more
than one distinct classification across their recorded runs.

**`GET /api/trend?projectId=`** — failure counts bucketed by calendar day (UTC)
and classification.

**`GET /api/screenshots/{id}`** — raw PNG, if that case had one.

## Known limitations

- **Human verification is manual, not automatic.** A wrong AI classification is
  labeled "unconfirmed" and weighted lower in retrieval, but it isn't blocked
  from grounding future diagnoses until a person actually reviews it from the
  Review Queue. The system narrows the blast radius of a wrong guess; it doesn't
  eliminate it on its own.
- **The 5-category classification taxonomy is fixed** (`SITE_RENDERING_ISSUE`,
  `UI_OVERLAY_BLOCKING`, `ASSERTION_MISMATCH`, `LOCATOR_BROKEN`, `UNKNOWN`) and was
  defined before any real failure data existed — some real failures (timing/race
  conditions in particular) don't cleanly fit any of the five. Tracked in
  `../BACKLOG.md` - deliberately deferred until real stress-test data shows
  which new category actually earns its place, instead of guessing upfront.
- **No authentication.** Intended for local or trusted-network use, not the
  public internet. Tracked in `../BACKLOG.md` - low effort, just not urgent for
  the current scope.
- **No vision-model reasoning.** The model only ever sees a text page-source
  snippet, never the captured screenshot, even though screenshots are stored
  and shown in the dashboard - this would materially help
  `UI_OVERLAY_BLOCKING`/`SITE_RENDERING_ISSUE` classification but is blocked on
  company security policy against swapping/adding models right now. Tracked in
  `../BACKLOG.md`.
- **Page-source evidence is truncated to the first 1500 characters** of whatever
  string is sent — for a real page, that's often just `<head>` boilerplate. Sending
  a more targeted excerpt (e.g. around the failing element) produces better results.
- **Cross-project retrieval fallback only ever considers human-verified cases**
  from other projects, by design — but that also means a brand-new project with
  no verified history anywhere yet still gets no grounding at all until at least
  one case, somewhere, has been reviewed by a person.
- **The self-check pass doubles LLM calls per diagnosis** (one classification
  call, one independent critic call), which roughly doubles per-request latency.
  This is a deliberate accuracy-over-speed tradeoff given the service runs
  locally and asynchronously from the test run itself.
- **Locator extraction is heuristic, not a real parser.** `LocatorExtractor`
  recognizes Selenium's standard JSON locator format, `By.xpath(...)`-style
  calls, and bare CSS id/class tokens - anything else yields no extracted
  locator (`failingLocator` stays null), which just means that case is excluded
  from Locator Health grouping, not that anything breaks.
