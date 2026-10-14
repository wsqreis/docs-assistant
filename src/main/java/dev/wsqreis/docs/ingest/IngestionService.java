package dev.wsqreis.docs.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Incremental ingestion of an Antora docs tree ({@code modules/<module>/pages/**.adoc}).
 *
 * <p>Two kinds of work, two kinds of threads: reading and parsing files is I/O-bound and runs on virtual
 * threads; computing embeddings is CPU-bound and runs on a fixed pool sized to the cores. Only chunks whose
 * text (or embedding model) changed are re-embedded, and chunks that disappeared from the docs are deleted.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BATCH = 32;

    private final AsciidocChunker chunker = new AsciidocChunker();
    private final ChunkStore store;
    private final EmbeddingModel embeddings;
    private final String modelId;
    private final ApplicationEventPublisher events;

    public IngestionService(ChunkStore store, EmbeddingModel embeddings, ApplicationEventPublisher events,
                            @Value("${docs.embedding-model-id:all-MiniLM-L6-v2}") String modelId) {
        this.store = store;
        this.embeddings = embeddings;
        this.events = events;
        this.modelId = modelId;
    }

    public record Report(int chunks, int embedded, int deleted, int unchanged, long millis) {
    }

    public Report ingest(Path modulesRoot) throws IOException, InterruptedException {
        long started = System.nanoTime();
        var chunks = readAll(modulesRoot);

        var stored = store.hashes();
        var changed = new ArrayList<Chunk>();
        var changedHashes = new ArrayList<String>();
        for (var chunk : chunks) {
            var hash = hash(chunk);
            if (!hash.equals(stored.get(chunk.id()))) {
                changed.add(chunk);
                changedHashes.add(hash);
            }
        }
        var current = chunks.stream().map(Chunk::id).collect(java.util.stream.Collectors.toSet());
        var removed = stored.keySet().stream().filter(id -> !current.contains(id)).toList();

        embedAndStore(changed, changedHashes);
        int deleted = store.delete(removed);
        if (!changed.isEmpty() || deleted > 0 || store.lexemeStatsEmpty()) {
            store.refreshLexemeStats();
        }
        if (!changed.isEmpty() || deleted > 0) {
            events.publishEvent(new DocsReindexed(changed.size(), deleted));
        }

        var report = new Report(chunks.size(), changed.size(), deleted, chunks.size() - changed.size(),
                (System.nanoTime() - started) / 1_000_000);
        log.info("Ingestion done: {} chunks, {} embedded, {} deleted, {} unchanged in {} ms",
                report.chunks(), report.embedded(), report.deleted(), report.unchanged(), report.millis());
        return report;
    }

    private List<Chunk> readAll(Path modulesRoot) throws IOException, InterruptedException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(modulesRoot)) {
            files = walk.filter(p -> p.toString().endsWith(".adoc") && p.getParent() != null)
                    .filter(p -> modulesRoot.relativize(p).getNameCount() >= 3
                            && modulesRoot.relativize(p).getName(1).toString().equals("pages"))
                    .sorted()
                    .toList();
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<List<Chunk>>>();
            for (var file : files) {
                var relative = modulesRoot.relativize(file);
                var module = relative.getName(0).toString();
                var page = relative.subpath(2, relative.getNameCount()).toString();
                futures.add(executor.submit(() -> chunker.chunk(module, page, Files.readString(file, StandardCharsets.UTF_8))));
            }
            var chunks = new ArrayList<Chunk>();
            for (var future : futures) {
                chunks.addAll(get(future));
            }
            log.info("Parsed {} pages into {} chunks", files.size(), chunks.size());
            return chunks;
        }
    }

    private void embedAndStore(List<Chunk> chunks, List<String> hashes) throws InterruptedException {
        if (chunks.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int start = 0; start < chunks.size(); start += BATCH) {
                var batch = chunks.subList(start, Math.min(start + BATCH, chunks.size()));
                var batchHashes = hashes.subList(start, Math.min(start + BATCH, chunks.size()));
                Callable<Integer> task = () -> {
                    var vectors = embeddings.embed(batch.stream().map(Chunk::indexText).toList());
                    store.upsert(batch, batchHashes, vectors);
                    return batch.size();
                };
                futures.add(executor.submit(task));
            }
            int done = 0;
            for (var future : futures) {
                done += get(future);
                if (done % (BATCH * 10) == 0 || done == chunks.size()) {
                    log.info("Embedded {}/{}", done, chunks.size());
                }
            }
        }
    }

    private String hash(Chunk chunk) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(modelId.getBytes(StandardCharsets.UTF_8));
            digest.update(chunk.indexText().getBytes(StandardCharsets.UTF_8));
            digest.update(chunk.url().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            throw new IllegalStateException("Ingestion failed", e.getCause());
        }
    }
}
