package zzz.service.forum.service;

public interface AiCodeIndexService {
    void indexAsync(Long projectId);

    void requestCancel(Long projectId);
}
