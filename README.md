# POracle

## What is this project

POracle ("Product Oracle") answers questions about software projects. It clones GitHub repositories, splits their
source code, documentation and configuration into chunks, stores them in a vector database, and lets an agent search
them to answer a question, citing the files it used. Everything runs locally: the language models are served by
Ollama, the chunks are stored in Qdrant, and Langfuse records the traces and the evaluation runs.

It is a proof of concept built on Java 17, Spring Boot (WebFlux) and LangChain4j. It also contains an evaluator that
measures the quality of the answers against a dataset of questions and expected answers.

## Requirements

| What | Needed for | Notes |
|---|---|---|
| JDK 17 or newer and Maven | everything | There is no Maven wrapper. On Windows on ARM the JDK must be x64, see below. |
| Docker with Compose | Qdrant and Langfuse | `docker compose up -d` starts both. |
| Ollama | every model call | Native, or in Docker with `docker-compose-ollama.yml`. |
| A GitHub token | cloning repositories | Goes in `secrets.properties`. |
| A reranking model (two files) | starting with the reranker on | Or switch the reranker off, see below. |

Git and Git LFS are not needed: repositories are cloned with JGit, and LFS files stay small pointer files.

### Qdrant and Langfuse (Docker)

Copy `docker-compose-langfuse.template.env` to `docker-compose-langfuse.env`, fill it in, then start both:

```bash
docker compose up -d
```

- **Qdrant** is always required. The application connects to it over gRPC on port 6334, without TLS or an API key.
  The compose file is the supported way to run it; any Qdrant server reachable that way works too.
- **Langfuse** is required for the evaluation and for the traces of the model calls. Self-hosting it means several
  containers (database, cache, object storage, web and worker), so in practice it needs Docker. Its web interface is at `http://localhost:3000` (user
  `admin@localhost.local`, password `LANGFUSE_INIT_USER_PASSWORD` from the env file). To only ask questions without
  it, set `LANGFUSE.TRACING.ENABLED=false`; the two Langfuse keys must still be present in `secrets.properties`.

### Ollama

Ollama is expected at `http://localhost:11434`. Pull the models named in `application.properties`:

```bash
ollama pull llama3.1:8b          # answers the questions, writes the project summaries
ollama pull qwen2.5-coder:1.5b   # summarises the file tree during an ingestion
ollama pull phi4:14b             # judge, only needed for the evaluation
```

Set the context window to 64k on the Ollama server (`OLLAMA_CONTEXT_LENGTH=64000`, or *Settings | Context length* in
the desktop app). The application cannot set it per request, and Ollama's small default silently cuts long prompts,
so the model would not see part of the retrieved excerpts. Check it with `ollama ps` after the first request.

For an evaluation also set `OLLAMA_MAX_LOADED_MODELS=2`, so the answering model and the judge stay loaded together,
and `OLLAMA_NUM_PARALLEL` to the value of `EVALUATION.CONCURRENCY` if the machine has the memory for it.

To run Ollama in Docker instead:

```bash
docker compose -f docker-compose-ollama.yml up -d
```

The container uses the same port, so stop a native Ollama first. It pulls the models above plus the embedding model
(about 16 GB the first time; follow it with `docker logs -f ollama`) and already sets the context window. It does not
ask for a GPU, so it is much slower than a native Ollama, and Docker Desktop on macOS cannot use the GPU at all.
When you change a model name in `application.properties`, change it in the compose file too.

### Secrets

Copy `src/main/resources/secrets.template.properties` to `src/main/resources/secrets.properties` and fill it in. The
application does not start without the three keys.

| Key | Meaning |
|---|---|
| `GITHUB.API.KEY` | GitHub personal access token used to clone the repositories. |
| `LANGFUSE.PUBLIC.KEY` | Must be equal to `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` in `docker-compose-langfuse.env`. |
| `LANGFUSE.SECRET.KEY` | Must be equal to `LANGFUSE_INIT_PROJECT_SECRET_KEY` in `docker-compose-langfuse.env`. |

The file is read from a path relative to the working directory, so start the application from the repository root.

### Reranking model

`application.properties` has the reranker switched on (`RERANK.PROVIDER=in-process`) and expects two files in an
`onnx` folder next to the repository. The model is not bundled; download it once:

```bash
mkdir ../onnx
curl -L -o ../onnx/model.onnx https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx
curl -L -o ../onnx/tokenizer.json https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json
```

To run without it, set `RERANK.PROVIDER=none` and leave `RAG.SEARCH.WEAK.SCORE` empty.

### Windows on ARM: use an x64 JDK

Code is chunked with tree-sitter, which loads a native library that is not published for Windows on ARM64. On an
ARM64 JVM the application fails to start with `Can't open lib/aarch64-windows-tree-sitter-java.dll`. Run it on an
x64 JDK, which Windows executes under emulation:

1. Install an x64 (`x86_64` / `amd64`) JDK 17 or newer. Pick the x64 installer explicitly: the default download on
   an ARM machine is the ARM64 build. `"<jdk>\bin\java" -XshowSettings:properties -version` must print
   `os.arch = amd64`.
2. Run the application with it: in IntelliJ IDEA select it under *Run | Edit Configurations | Modify options | JRE*;
   in a terminal set `JAVA_HOME` to it before `mvn spring-boot:run`.
3. IntelliJ IDEA Ultimate only: press *Ctrl+Shift+A*, open *Registry* and untick `profiler.widget.in.run.console`.
   Otherwise the IDE kills the x64 process two seconds after launch with exit code `-1073741819`.

`CodeChunkingServiceImplTest` is skipped on Windows ARM64 for the same reason; run it with
`mvn test -Dtest=CodeChunkingServiceImplTest "-Djvm=<x64 jdk>\bin\java.exe"`.

## How to run the demo

1. Start Qdrant, Langfuse and Ollama as described above, then start the application from the repository root:

   ```bash
   mvn spring-boot:run
   ```

   It listens on port 8080.

   Every step below can be done with the `curl` command shown or, without a terminal, from the Swagger UI at
   <http://localhost:8080/swagger-ui.html>: open the endpoint named in the step, press *Try it out*, fill in the
   parameters or the body if there are any, then press *Execute*.

2. Ingest the demo projects, the repositories listed in `INGESTION.DEMO.REPOSITORIES` (spring-security and
   spring-ai):

   ```bash
   curl -X POST http://localhost:8080/demo/ingestion/ingest-spring-projects
   ```

   Swagger UI: `POST /demo/ingestion/ingest-spring-projects`.

   The call returns `202` at once and the ingestion runs in the background. Follow it in the application log; it
   ends with a line starting with `Ingestion completed in`. Only one ingestion can run at a time (`409` otherwise).

3. Ask the agent. The request body is the question:

   ```bash
   curl -X POST http://localhost:8080/agent/ask -H "Content-Type: text/plain" \
        -d "What is the default logout URL in spring-security?"
   ```

   Swagger UI: `POST /agent/ask`, with the question typed as the request body.

To ingest something else:

| Endpoint | What it ingests |
|---|---|
| `POST /ingestion/clone-and-ingest?repoUrl=<url>` | One GitHub repository and its wiki. |
| `POST /ingestion/ingest-from-path?path=<folder>` | A local folder as it is; the project name is the folder name. |

Ingesting a project again replaces its chunks. After changing the embedding provider or model, delete the collection
first with `DELETE /rag/delete-collection?collection=projects_collection`, then ingest again.

## How to run the evaluator

The evaluator asks the agent every question of a dataset and scores each answer. It needs the demo projects
ingested, Langfuse running and the judge model pulled. As for the demo, each step can be done with `curl` or from
the Swagger UI at <http://localhost:8080/swagger-ui.html>.

1. Upload the demo dataset to Langfuse. It is the file named in `EVALUATION.DEFAULT.DATASET`, 73 questions about
   the two demo projects in 12 categories:

   ```bash
   curl -X POST http://localhost:8080/demo/evaluation/upload-golden-demo-dataset
   ```

   Swagger UI: `POST /demo/evaluation/upload-golden-demo-dataset`.

   Any other dataset is uploaded with `POST /evaluation/upload-dataset`, the dataset being the request body. For
   example the older 50-question dataset that is also in `src/main/resources/datasets`:

   ```bash
   curl -X POST http://localhost:8080/evaluation/upload-dataset -H "Content-Type: application/json" \
        --data-binary @src/main/resources/datasets/spring-ai-security-golden-dataset.json
   ```

   Both return `204`. Your own dataset has the same shape: `datasetName`, `datasetDescription` and `records`, each
   record with `category`, `question` and `answer`.

2. Run the experiment on the demo dataset:

   ```bash
   curl -X POST http://localhost:8080/evaluation/run
   ```

   Swagger UI: `POST /evaluation/run`, with the `dataset` parameter left empty.

   To run another dataset, pass its name (the `datasetName` of the file) in the `dataset` parameter, for example
   `/evaluation/run?dataset=spring-ai-security-golden-dataset`.

   It returns `202` and runs in the background, one run at a time (`409` otherwise). A run takes roughly a minute
   per question on a laptop.

3. Read the results. The application log has one line per question with its scores and the files that were
   retrieved, and at the end the average of each score, overall and per category. The same run appears in Langfuse
   under the dataset, with the trace of every answer.

| Score | Meaning |
|---|---|
| `answer-recall` | Share of the facts of the expected answer that the answer states (judge model). |
| `context-recall` | Share of the facts of the expected answer found in the retrieved excerpts (judge model). |
| `context-precision` | Share of the retrieved excerpts that are useful for the question (judge model). |
| `groundedness` | Share of the claims of the answer that the retrieved excerpts support (judge model). |
| `semantic-similarity` | Similarity between the expected and the generated answer, measured with embeddings. |
| `answer-correctness` | Mean of `answer-recall` and `semantic-similarity`. |
| `key-term-recall` | Share of the identifiers of the expected answer that appear in the answer. |
| `refused` | 1 when the guardrails refused the question, otherwise 0. |

The answering model is not deterministic: between two runs on the same code a category of ten questions can move by
about 0.1.

## How the search works

The agent is not given the documents. It has a search tool, and it decides what to search for and how many times.
Every search goes through the same three stages: two independent rankings, their fusion, and a reranking.

### Two representations of every chunk

Each chunk is described by one text: its project, its file path, its context (the enclosing class, or the path of
headings or keys) and its content. Two vectors are computed from that text and stored together.

- **A dense vector**, the embedding. A neural model maps the text to a point in a space where texts with a similar
  meaning are close to each other, so closeness is measured by the cosine of the angle between two vectors. It finds
  a passage that answers the question in other words, but it is weak on exact names: an identifier the model has
  never seen carries little meaning for it.
- **A sparse vector**, the keywords. It has one dimension per term and is zero almost everywhere. Terms are the
  lowercased words, and an identifier counts both whole and by its parts (`getUserName` also gives `get`, `user`
  and `name`). Each term is weighted with BM25, the classical formula of lexical search:

  ```text
  weight(t, d) = idf(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * |d| / avgdl))
  ```

  `tf` is how often the term occurs in the chunk, `|d|` the length of the chunk and `avgdl` the average length.
  The formula says three things: a term that is repeated counts more, but less and less (`k1 = 1.2`); a long chunk
  is penalised, because it contains more terms by chance (`b = 0.75`); a term that is rare in the whole collection
  counts more than a common one (`idf`). The application computes the term-frequency part when a chunk is stored,
  with a fixed average length of 256 terms, and Qdrant applies the `idf` part at search time from the statistics of
  the collection.

The two are complementary. Questions about code are full of class names, annotations and property keys, which the
keywords match exactly, while the embedding covers the questions that describe a behaviour without naming it.

### Fusion of the two rankings

The search query is embedded and split into terms in the same way, and each vector produces its own ranking of the
20 closest chunks. Their scores cannot be compared or added: a cosine lies between -1 and 1, a BM25 score has no
upper bound. So the two rankings are merged with Reciprocal Rank Fusion, which only looks at positions:

```text
score(d) = sum over the rankings of 1 / (k + rank(d))
```

`rank(d)` is the position of the chunk in a ranking and `k` a constant that smooths the difference between the
first positions. A chunk that is absent from a ranking gets nothing from it, so a chunk both rankings found beats
one that a single ranking placed first.

### Reranking

The first stage compares vectors that were computed separately, the one of the chunk at ingestion and the one of
the query at search time. That is what makes it fast over a whole collection, and also what limits it: the model
never sees the question and the chunk together.

A cross-encoder does. It reads the question and one chunk as a single input and returns one relevance score, so it
can relate each word of the question to the words of the chunk. It is far more accurate and far too slow to run
over a collection, which is why it only reorders the 20 candidates of the fusion; the best 8 go to the agent.

The candidates are found with the query the agent wrote, but they are reranked against the question the user
asked. A badly worded query can still bring back the right chunks, and they are then judged on what was really
asked.

### Feedback to the agent

When the best score after reranking is under a threshold, the search result tells the agent that the excerpts
match poorly and that it should search again with other words. After two such notes in the same request it is told
to stop and to say that the knowledge base does not contain the answer. This keeps the model from building an
answer on excerpts that are only loosely related to the question.

The settings of these stages are under [Qdrant](#qdrant) and [Reranking and search](#reranking-and-search).

## How the evaluation works

The evaluation is reference-based: it relies on a golden dataset, a list of questions each with the answer that is
expected. For every question the run produces two more texts, so there are three to compare:

- the **expected answer**, from the dataset;
- the **generated answer**, written by the agent;
- the **retrieved context**, the excerpts the search tool returned while the agent was answering.

Each score compares two of them, and that is what makes a bad result readable: it tells whether the search or the
writing of the answer failed.

| Score | Compares | Question it answers |
|---|---|---|
| `context-recall` | retrieved context and expected answer | Did the search find what the answer needs? |
| `context-precision` | retrieved context and question | How much of what the search returned is useful? |
| `groundedness` | generated answer and retrieved context | Is the answer supported by what was retrieved? |
| `answer-recall`, `semantic-similarity`, `key-term-recall` | generated and expected answer | Is the answer right? |

A low `context-recall` is a retrieval problem: the model could not have answered. A high `context-recall` with a
low `answer-recall` is a generation problem: the facts were in front of the model and it did not use them. A
low `groundedness` means the model answered from its own memory instead of the excerpts, which is where invented
details come from.

A note on the names: some evaluation frameworks call `groundedness` faithfulness; it is the same measure.

### Scores given by a judge model

Four scores need a reader that understands the texts, so they are given by a language model, the judge. It is a
different and larger model than the one that answers, because a model grading its own output is lenient, and it has
no tools and no randomness.

The judge is never asked for a number. Language models are poor at placing a text on a scale, and two runs give two
different marks. Instead the text is decomposed into units that can only be true or false:

1. the judge splits the expected answer into separate facts (or the generated answer into claims, or takes the
   excerpts one by one);
2. it gives each unit a verdict, `YES` or `NO`;
3. the score is the share of `YES`.

A score of 0.75 therefore means "three facts out of four", and the list of verdicts is kept in the trace, so every
score can be checked by reading it. A reply without verdicts gives no score. The excerpts are shown to the judge
numbered and all of them are kept: when they are too long together, each one gets the same share of the space.

### Scores computed without a judge

- **`semantic-similarity`** is the cosine between the embeddings of the expected and of the generated answer. A raw
  cosine is misleading, because any two texts about the same subject are already close, even when they answer
  different questions. So the run first measures a baseline `b`, the mean similarity between all the pairs of
  expected answers, and rescales each similarity `s` to `(s - b) / (1 - b)`. A score of 0 then means "as close as
  two different answers of this dataset", and 1 means the same meaning.
- **`key-term-recall`** extracts the identifiers of the expected answer (camelCase names, `@Annotations`, dotted
  names, names with hyphens) and measures the share of them that appears in the generated answer. It is fully
  deterministic and checks what a paraphrase loses: the exact name of a class or of a property.
- **`answer-correctness`** is the mean of `answer-recall` and `semantic-similarity`, one judged and one measured
  view of the same comparison.
- **`refused`** is 1 when the guardrails refused the question.

A score that does not apply to a question is left out instead of being counted as 0: nothing is measured against the
retrieved context when the agent retrieved nothing, and `key-term-recall` is skipped when the expected answer names
no identifier.

### Reading the results

The scores are averaged over the whole run and per category of question. They are measurements with noise: the
answering model is not deterministic and the judge makes mistakes too. They are meant to compare two runs of the
same dataset, before and after a change, more than to be read as absolute values.

## Properties file

All settings are in `src/main/resources/application.properties`. The "Value" column is what the file contains now.

### Qdrant

| Key | Value | Meaning |
|---|---|---|
| `QDRANT.HOST` | `localhost` | Host of the Qdrant server. |
| `QDRANT.GRPC.PORT` | `6334` | gRPC port of the Qdrant server. |
| `QDRANT.INDEXING.THRESHOLD` | `20000` | Indexing threshold restored on the collection when an ingestion ends. Indexing is switched off while ingesting to keep writes fast. |
| `QDRANT.SEARCH.TOP.K` | `20` | Number of results a search takes from Qdrant. With the reranker on, these are the candidates it chooses from. |
| `QDRANT.SEARCH.MIN.SCORE` | `0` | Minimum vector similarity of a result; `0` keeps everything. |
| `QDRANT.SEARCH.HYBRID.ENABLED` | `true` | `true`: combine the vector similarity with a keyword match. `false`: vector similarity only. No new ingestion is needed to switch. |
| `QDRANT.SEARCH.PREFETCH.LIMIT` | `20` | With the hybrid search, how many results each of the two rankings contributes before they are fused. |

### Ollama

| Key | Value | Meaning |
|---|---|---|
| `OLLAMA.BASE.URL` | `http://localhost:11434` | Address of the Ollama server. |
| `OLLAMA.GENERAL.PURPOSE.MODEL.NAME` | `llama3.1:8b` | Model that answers the questions and writes the project and module summaries. It must support tool calling. |
| `OLLAMA.CODING.MODEL.NAME` | `qwen2.5-coder:1.5b` | Model that summarises the file tree of a project during an ingestion. |
| `OLLAMA.JUDGE.MODEL.NAME` | `phi4:14b` | Model that grades the answers in an evaluation. Choose one larger than the answering model and from another family: a model grading itself is lenient. |
| `OLLAMA.EMBEDDING.MODEL.NAME` | `qwen3-embedding:0.6b` | Embedding model, used only when `EMBEDDING.PROVIDER=ollama`. |
| `OLLAMA.EMBEDDING.QUERY.PREFIX` | an instruction | Text put in front of a question before it is embedded with the Ollama model. Change it together with the model. |
| `OLLAMA.EMBEDDING.PASSAGE.PREFIX` | empty | Text put in front of a chunk before it is embedded with the Ollama model. |

### Embeddings

| Key | Value | Meaning |
|---|---|---|
| `EMBEDDING.PROVIDER` | `in-process` | `in-process`: the bundled `e5-small-v2` model, no server needed, general-text and weak on code. `ollama`: the model named in `OLLAMA.EMBEDDING.MODEL.NAME`. After a change, delete the collection and ingest again. |
| `EMBEDDING.MAX.THREADS` | `0` | Threads used for embedding and reranking; `0` means one per CPU core. |

### Reranking and search

| Key | Value | Meaning |
|---|---|---|
| `RERANK.PROVIDER` | `in-process` | `in-process`: reorder the search results with a cross-encoder model that scores each one against the question. `none`: keep the order of the search. |
| `RERANK.TOP.K` | `8` | Number of results kept after reranking, which is what the agent receives. |
| `RERANK.MODEL.PATH` | `../onnx/model.onnx` | Path of the ONNX reranking model. |
| `RERANK.TOKENIZER.PATH` | `../onnx/tokenizer.json` | Path of its tokenizer. |
| `RAG.SEARCH.MAX.CHUNK.CHARS` | `1500` | Longest excerpt of a chunk given to the agent; longer chunks are cut. |
| `RAG.SEARCH.WEAK.SCORE` | `3` | When the best result of a search scores below this, the agent is told to search again with other words. The value fits the scores of the `in-process` reranker; leave it empty to switch this off, and always with `RERANK.PROVIDER=none`. |

### Agents and guardrails

| Key | Value | Meaning |
|---|---|---|
| `AGENT.LOG.REQUESTS` | `false` | `true` logs every request sent to a model. |
| `AGENT.LOG.RESPONSES` | `false` | `true` logs every response of a model. |
| `GUARDRAILS.ENABLED` | `true` | Checks on the question (prompt injection, length) and on the answer (leaked system prompt, secret tokens). A blocked question gets a fixed refusal. |
| `GUARDRAILS.SELF.CHECK.ENABLED` | `false` | `true` adds two model-based checks, one on the question and one on the answer, each costing one more model call. |
| `GUARDRAILS.MAX.INPUT.CHARS` | `10000` | Longest question accepted. |
| `GUARDRAILS.TOPICS` | a sentence | What the assistant is allowed to talk about, used in its instructions and by the self-check. |
| `SECRETS.REDACTION.ENABLED` | `true` | Replace tokens, private keys and passwords found in the ingested files before they are stored. |

### Langfuse and evaluation

| Key | Value | Meaning |
|---|---|---|
| `LANGFUSE.HOST` | `http://localhost:3000` | Address of Langfuse. |
| `LANGFUSE.TRACING.ENABLED` | `true` | Send a trace of every model call to Langfuse. `false` sends nothing. |
| `EVALUATION.DEFAULT.DATASET` | `datasets/spring-ai-security-golden-dataset_v2.json` | Dataset on the classpath that is uploaded by `/demo/evaluation/upload-golden-demo-dataset` and run when no dataset is named. |
| `EVALUATION.MAX.SOURCES.CHARS` | `12000` | Total size of the retrieved excerpts shown to the judge; each excerpt gets an equal share. |
| `EVALUATION.CONCURRENCY` | `4` | Questions evaluated at the same time. It only shortens a run when Ollama serves that many requests in parallel; `1` evaluates them one by one. |

### GitHub and ingestion

| Key | Value | Meaning |
|---|---|---|
| `GITHUB.CLONE.DEPTH` | `1` | Number of commits cloned; `1` takes only the latest. |
| `GITHUB.CLONE.ALL.BRANCHES` | `false` | `true` clones every branch, `false` only the default one. |
| `INGESTION.DEMO.REPOSITORIES` | two URLs | Comma-separated repositories ingested by `/demo/ingestion/ingest-spring-projects`. |
| `INGESTION.BATCH.SIZE` | `75` | Chunks embedded and stored in one batch. |
| `INGESTION.BATCH.TIMEOUT.SECONDS` | `5` | A smaller batch is sent after this many seconds without reaching the batch size. |
| `INGESTION.MAX.BUFFERED.BATCHES` | `5000` | Batches that may wait to be stored before the ingestion is slowed down. |
| `PROJECTS.DOWNLOAD.FOLDER` | `target/projects` | Folder the repositories are cloned into, relative to the working directory. |
| `IGNORE.HIDDEN.FILES.AND.FOLDERS` | `true` | Skip files and folders whose name starts with a dot. |

### Chunking

| Key | Value | Meaning |
|---|---|---|
| `CODE.FILE.EXTENSIONS` | a list | Extensions ingested as source code. Java, Kotlin, Python, JavaScript, TypeScript, SQL and HTML are split by declaration; the others are stored as whole files. |
| `DOCS.FILE.EXTENSIONS` | a list | Extensions ingested as documentation, split by headings (plain text by paragraphs). |
| `CONFIG.FILE.EXTENSIONS` | a list | Extensions ingested as configuration, split by keys or elements. |
| `TEXT.CHUNK.MAX.CHARS` | `1500` | Longest chunk of documentation or configuration. |
| `PROJECT.TREE.MAX.DEPTH` | `3` | Depth of the file tree that is summarised for each project. |
| `SUMMARY.MAX.MODULES` | `40` | Most module summaries written per project, one per nested README. |
| `SUMMARY.MODULE.MAX.DEPTH` | `3` | Deepest folder level whose README gets a module summary. |
| `SUMMARY.MAX.INPUT.CHARS` | `12000` | Longest README text given to the model for a summary. |

A file whose extension is in none of the three lists is not ingested.
