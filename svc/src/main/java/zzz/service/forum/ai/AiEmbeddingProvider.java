package zzz.service.forum.ai;

import java.util.List;

public interface AiEmbeddingProvider {

    boolean isEnabled();

    int dimension();

    List<Double> embed(String text);
}
