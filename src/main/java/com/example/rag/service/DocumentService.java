package com.example.rag.service;

import com.example.rag.model.DocumentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class DocumentService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    public DocumentService(ChunkingService chunkingService,
                           EmbeddingService embeddingService,
                           MilvusService milvusService) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
    }

    public int ingest(String title, String content) {
        List<String> chunks = chunkingService.chunk(content);
        if (chunks.isEmpty()) {
            log.warn("No chunks generated for document '{}'", title);
            return 0;
        }

        List<String> titles = new ArrayList<>(Collections.nCopies(chunks.size(), title));
        List<List<Float>> embeddings = embeddingService.encode(chunks);
        List<Long> ids = milvusService.insertChunks(titles, chunks, embeddings);
        log.info("Ingested document '{}' into {} chunks", title, ids.size());
        return ids.size();
    }

    public List<DocumentResponse> listAll() {
        return milvusService.listAll();
    }

    public DocumentResponse getById(long id) {
        return milvusService.getById(id);
    }

    public void deleteById(long id) {
        milvusService.deleteById(id);
    }

    public List<Long> getAllIds() {
        return milvusService.getAllIds();
    }

    public List<DocumentResponse> getByIds(List<Long> ids) {
        return ids.stream().map(milvusService::getById).filter(d -> d != null).toList();
    }

    public int deleteBatch(List<Long> ids) {
        int count = 0;
        for (Long id : ids) {
            try {
                milvusService.deleteById(id);
                count++;
            } catch (Exception e) {
                log.warn("Failed to delete id={}: {}", id, e.getMessage());
            }
        }
        return count;
    }

}
