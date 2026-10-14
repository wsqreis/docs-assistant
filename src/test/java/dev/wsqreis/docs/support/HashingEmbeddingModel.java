package dev.wsqreis.docs.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Deterministic bag-of-words embeddings (feature hashing), so tests exercise pgvector and retrieval
 * without downloading the ONNX model. Texts that share words get similar vectors.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 384;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var embeddings = new ArrayList<Embedding>();
        var texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(embed(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        var vector = new float[DIMENSIONS];
        for (var word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() > 2) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) Math.sqrt(norm);
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
