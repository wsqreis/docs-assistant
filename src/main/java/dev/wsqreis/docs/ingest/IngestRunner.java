package dev.wsqreis.docs.ingest;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * {@code --ingest=<path to antora/modules>} indexes the docs at startup; add {@code --exit} to stop afterwards
 * (for CI or a cron job), otherwise the app keeps serving.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private final IngestionService ingestion;
    private final ApplicationContext context;

    public IngestRunner(IngestionService ingestion, ApplicationContext context) {
        this.ingestion = ingestion;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("ingest")) {
            return;
        }
        ingestion.ingest(Path.of(args.getOptionValues("ingest").getFirst()));
        if (args.containsOption("exit")) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
