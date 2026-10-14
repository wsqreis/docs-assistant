# docs-assistant

[![CI](https://github.com/wsqreis/docs-assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/wsqreis/docs-assistant/actions/workflows/ci.yml)

Ask the **Spring Boot 4.1 reference documentation** a question and get a streamed answer that cites the exact sections it used, with deep links to docs.spring.io. The same index is exposed as an **MCP server**, so coding agents (Claude Code, Copilot, Cursor) can search the official docs instead of guessing.

Most of the work is in retrieval: how the docs are chunked, how search is done, and how each choice was measured.

**Stack:** Java 21 · Spring Boot 4.1 · Spring AI 2.0 (chat, local ONNX embeddings, MCP server) · PostgreSQL 17 + pgvector · Flyway · Testcontainers · ArchUnit

## How it works

```
 Spring Boot docs (AsciiDoc, 151 pages)
        │  section-level chunking, Antora macros rewritten, deep link per section
        ▼
 ingestion ── virtual threads (I/O) + fixed pool (CPU-bound embeddings) ──▶ PostgreSQL
        incremental: only new or changed chunks are re-embedded               ├ pgvector (HNSW)
                                                                              ├ tsvector (GIN)
 question ─▶ semantic cache ─▶ off-topic gate ─▶ retrieval ─▶ LLM (streamed) ─┘
                 hit: answer       below threshold:   top 6       cites [n] → links
                 instantly          decline, no LLM   sections
```

### Ingestion

- **Section-level chunks.** Every AsciiDoc section has a stable anchor like `[[web.graceful-shutdown]]`, so each chunk links straight to its place in the published docs. The local embedding model reads at most 256 tokens, so long sections are cut into windows of about 900 characters at paragraph boundaries. Each window is prefixed with its breadcrumb ("Servlet Web Applications › Spring MVC › Auto-configuration") so it keeps its context. The 151 pages produce 1,602 chunks.
- **Antora macros become readable text.** `javadoc:org.x.Foo[format=annotation]` becomes `@Foo`, `configprop:server.port[]` becomes `server.port`, cross-references keep their link text, and `include-code::Example[]` becomes a marker. Comments, attributes and block delimiters are dropped.
- **Incremental.** Each chunk stores a hash of its text, its URL and the embedding model id. A re-run embeds only what changed and deletes sections that disappeared; changing the model re-embeds everything. Reading files runs on virtual threads; embedding is CPU-bound, so it runs on a fixed pool sized to the cores.
- **Free embeddings.** all-MiniLM-L6-v2 runs locally via ONNX. Indexing the whole reference takes about half a minute on a laptop CPU, with no API cost.

### Retrieval

Three modes, all in SQL over the same table:

| Mode | How |
|---|---|
| `VECTOR` | Cosine similarity on pgvector with an HNSW index |
| `KEYWORD` | PostgreSQL full-text search. The question becomes stemmed lexemes OR-ed together, minus the lexemes that appear in more than 10% of chunks. Those document frequencies come from `ts_stat` after every ingestion; in these docs "spring", "use" and "configur" appear in most sections, and `ts_rank_cd` has no notion of rarity. Identifiers such as `spring.jpa.open-in-view` are cast to the query as-is instead of being re-parsed. |
| `HYBRID` | Both lists fused with Reciprocal Rank Fusion (k = 60), which needs no calibration between cosine scores and `ts_rank` |

### Answering

1. **Semantic cache:** a question within 0.95 cosine similarity of one already answered gets the stored answer and sources instantly. Re-indexing clears the cache, since cached answers may quote text that changed.
2. **Off-topic gate:** if no documentation chunk is similar enough to the question, the assistant declines without calling the LLM. The threshold was set by comparing the closest-section similarity of real Spring Boot questions with clearly unrelated ones (recipes, trivia, CSS, prompt-injection attempts).
3. **Grounded generation:** the model gets the top 6 sections as numbered excerpts. It must cite them as `[n]`, which the UI turns into links, and it must say the docs don't cover the question rather than answer from memory. That second layer handles questions that are near the docs but not in them, such as Hibernate Envers.
4. **Streaming:** Server-Sent Events carry `sources` first, then `token`s as the model writes, then `done`. The other outcomes are `declined` and `failure`.

## Evaluation

`eval/questions.json` has 40 questions written the way developers ask them, each labelled with the documentation sections that answer it:
- 28 paraphrased questions, e.g. "let requests in progress finish before the app shuts down".
- 12 questions with exact identifiers, e.g. `@WebMvcTest` or `spring.jpa.open-in-view`.

The eval measures retrieval only (hit@1, hit@3, hit@5 and MRR for each mode), so it needs no LLM and costs nothing to run:

```bash
java -jar target/docs-assistant-0.1.0.jar --spring.main.web-application-type=none --eval=eval/questions.json --exit
```

What the measurements changed:

- **Vector search is the default.** I expected hybrid search to win, as it often does. On this corpus, the local embedding model already handles exact identifiers well, and adding keyword results mostly brought in noise on paraphrased questions. Vector search was best on every metric. Hybrid and keyword stay available, and the console compares all three side by side.
- **Term-frequency filtering for keyword search.** The first version OR-ed every word of the question and ranked mostly by how often common words appeared. Dropping corpus-wide frequent lexemes clearly improved keyword search on identifier questions.
- **A stronger embedding model was not better here.** bge-small-en-v1.5 (same 384 dimensions) retrieved worse than all-MiniLM-L6-v2 on paraphrased questions and was about 4.5× slower to index. A likely reason is that the ONNX integration uses mean pooling, while bge expects CLS pooling and a query instruction.
- **Two labels were corrected after error analysis.** The model's top results for "compile to a native binary" and "start database containers automatically" were valid sections I hadn't listed. All modes were re-scored with the corrected labels.

## MCP server

The app serves MCP over streamable HTTP at `/mcp`, with two tools:

| Tool | |
|---|---|
| `search_spring_boot_docs` | `query`, optional `limit` (1-10) → sections with title, breadcrumb, deep link and excerpt |
| `read_spring_boot_docs_section` | `url` from a search result → the full section text |

Add it to Claude Code:

```bash
claude mcp add --transport http spring-boot-docs http://localhost:8080/mcp
```

The tools only retrieve; the agent's own model writes the answer, so they need no API key.

## Run it

Requires Java 21 and Docker. An [OpenRouter](https://openrouter.ai/keys) key is only needed for the chat answers.

```bash
docker compose up -d                 # PostgreSQL 17 + pgvector on port 5433
./scripts/fetch-docs.sh              # Spring Boot v4.1.1 docs into data/ (sparse checkout)
./mvnw -DskipTests package
java -jar target/docs-assistant-0.1.0.jar \
  --ingest=data/spring-boot-docs/documentation/spring-boot-docs/src/docs/antora/modules
```

Then open http://localhost:8080:
- **Ask:** streamed answers with clickable citations.
- **Compare retrieval:** the same query through vector, keyword and hybrid search side by side.

Set `OPENROUTER_API_KEY` for answers, and optionally `LLM_MODEL`. `--ingest=... --exit` indexes and stops, for CI or a scheduled job.

### API

| | |
|---|---|
| `GET /api/ask?q=` | Server-Sent Events: `sources`, `token`…, `done` / `declined` / `failure` |
| `GET /api/search?q=&mode=VECTOR\|KEYWORD\|HYBRID&limit=` | Ranked sections with deep links and scores |

## Testing

```bash
./mvnw verify      # Docker required for the integration tests
```

- **Unit:** chunking (anchors, breadcrumbs, deep links, macro rewriting, windowing, stable ids), the Reciprocal Rank Fusion arithmetic, and the eval's matching rules.
- **Integration (Testcontainers, real pgvector):** runs on a small fixture doc tree with fake models:
  - incremental ingestion (edit one section → one embedding; delete a page → its chunks removed);
  - keyword search on exact property names, and deep links from vector and hybrid search;
  - the SSE answer flow end to end, including the cache hit on a repeated question, declining off-topic questions without a model call, and model failures;
  - cache invalidation on re-index, and the MCP tools.
- **Architecture (ArchUnit):** ingestion and search know nothing about answering, HTTP or MCP; web and MCP are adapters.

## Limits

- English only: the embedding model and the full-text configuration are English, like the docs.
- Code samples in the docs are pulled from Java files via `include-code`, so they are not indexed; the assistant points to the section instead.
- The 40-question set is small and was labelled by one person. It is enough to compare approaches on this corpus, not to make general claims.
- Content: Spring Boot documentation © the Spring Boot authors, Apache License 2.0. It is downloaded by `scripts/fetch-docs.sh` and not stored in this repository.

## License

[MIT](LICENSE)
