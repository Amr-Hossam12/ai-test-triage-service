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
   incidents), a linear in-memory scan is simpler and just as fast.
2. **Generation** — the current evidence plus the most similar past incidents get
   sent to a local LLM (`llama3.1:8b` via Ollama) using JSON-mode, constrained to
   return a classification (one of 5 fixed categories), a reasoning explanation,
   and a suggested next step. The classification is validated against the allowed
   set — malformed output falls back to `UNKNOWN` rather than corrupting the store.
3. **Storage** — every triaged case (with its screenshot, if one was sent) is
   appended to the knowledge base, so it's available as grounding context for the
   next failure, even seconds later.
4. **Dashboard** — a single self-contained HTML page lists every case, filterable
   by project, with a click-through detail view showing the full reasoning,
   suggestion, and screenshot.

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

## Dashboard

Open **http://localhost:8787/** in a browser — lists every triaged failure with a
color-coded classification badge, click any row for the full detail (reasoning,
suggestion, screenshot).

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
{ "id": "...", "classification": "...", "reasoning": "...", "suggestion": "..." }
```

**`GET /api/failures?projectId=`** — list all triaged cases, optionally filtered by project.

**`GET /api/failures/{id}`** — full detail for one case.

**`GET /api/screenshots/{id}`** — raw PNG, if that case had one.

## Known limitations

- **No human-confirm gate before a diagnosis joins the knowledge base.** Every
  classification, right or wrong, is persisted immediately and becomes retrieval
  context for future failures. A wrong early diagnosis can bias later ones toward
  the same mistake.
- **The 5-category classification taxonomy is fixed** (`SITE_RENDERING_ISSUE`,
  `UI_OVERLAY_BLOCKING`, `ASSERTION_MISMATCH`, `LOCATOR_BROKEN`, `UNKNOWN`) and was
  defined before any real failure data existed — some real failures (timing/race
  conditions in particular) don't cleanly fit any of the five.
- **No authentication.** Intended for local or trusted-network use, not the public internet.
- **Page-source evidence is truncated to the first 1500 characters** of whatever
  string is sent — for a real page, that's often just `<head>` boilerplate. Sending
  a more targeted excerpt (e.g. around the failing element) produces better results.
