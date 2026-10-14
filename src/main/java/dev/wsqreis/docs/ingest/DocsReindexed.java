package dev.wsqreis.docs.ingest;

/** Published after an ingestion that changed the indexed documentation. */
public record DocsReindexed(int embedded, int deleted) {
}
