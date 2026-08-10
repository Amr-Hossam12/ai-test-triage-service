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

## Requirements

- Java 21+
- Maven
- [Ollama](https://ollama.com), running locally, with these two models pulled:
  ```bash
  ollama pull llama3.1:8b
  ollama pull nomic-embed-text
  ```

## Run it

```bash
mvn package
java -cp "target/classes;target/lib/*" com.aitriage.Main
```

(On Linux/macOS, use `:` instead of `;` in the classpath.)

This starts the HTTP server and dashboard on port 8787 by default, and creates a
`data/` directory (JSONL knowledge base + a `screenshots/` folder) next to wherever
you run it from.

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
