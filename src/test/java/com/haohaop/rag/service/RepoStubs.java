package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import com.haohaop.rag.repository.ChunkRepository;
import com.haohaop.rag.repository.DocumentRepository;
import org.springframework.data.domain.*;
import java.util.*;

@SuppressWarnings("deprecation")
class RepoStubs {

    static class DocRepoStub implements DocumentRepository {
        public final List<DocumentEntity> docs = new ArrayList<>();
        @Override public <S extends DocumentEntity> S save(S e) { docs.add(e); return e; }
        @Override public <S extends DocumentEntity> List<S> saveAll(Iterable<S> es) { es.forEach(this::save); return (List<S>) docs; }
        @Override public Optional<DocumentEntity> findById(Long id) { return docs.stream().filter(d -> d.getId() != null && d.getId().equals(id)).findFirst(); }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<DocumentEntity> findAll() { return new ArrayList<>(docs); }
        @Override public List<DocumentEntity> findAll(Sort s) { return findAll(); }
        @Override public Page<DocumentEntity> findAll(Pageable p) { return new PageImpl<>(docs, p, docs.size()); }
        @Override public List<DocumentEntity> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public <S extends DocumentEntity> List<S> findAll(Example<S> ex) { return List.of(); }
        @Override public <S extends DocumentEntity> List<S> findAll(Example<S> ex, Sort s) { return List.of(); }
        @Override public <S extends DocumentEntity> Page<S> findAll(Example<S> ex, Pageable p) { return Page.empty(); }
        @Override public <S extends DocumentEntity> Optional<S> findOne(Example<S> ex) { return Optional.empty(); }
        @Override public <S extends DocumentEntity> boolean exists(Example<S> ex) { return false; }
        @Override public <S extends DocumentEntity> long count(Example<S> ex) { return 0; }
        @Override public <S extends DocumentEntity, R> R findBy(Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> qf) { return null; }
        @Override public long count() { return docs.size(); }
        @Override public void deleteById(Long id) {}
        @Override public void delete(DocumentEntity e) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends DocumentEntity> es) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends DocumentEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends DocumentEntity> List<S> saveAllAndFlush(Iterable<S> es) { return saveAll(es); }
        @Override public void deleteAllInBatch(Iterable<DocumentEntity> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public DocumentEntity getById(Long id) { return findById(id).orElse(null); }
        @Override public DocumentEntity getReferenceById(Long id) { return findById(id).orElse(null); }
        @Override public DocumentEntity getOne(Long id) { return getReferenceById(id); }
        @Override public Optional<DocumentEntity> findByDocumentId(String id) { return docs.stream().filter(d -> id.equals(d.getDocumentId())).findFirst(); }
        @Override public Page<DocumentEntity> findByTitleContainingIgnoreCaseOrderByCreatedAtDesc(String k, Pageable p) { return new PageImpl<>(docs); }
        @Override public Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable p) { return new PageImpl<>(docs, p, docs.size()); }
        @Override public void deleteByDocumentId(String id) {}
    }

    static class ChunkRepoStub implements ChunkRepository {
        public final List<ChunkEntity> chunks = new ArrayList<>();
        @Override public <S extends ChunkEntity> S save(S e) { chunks.add(e); return e; }
        @Override public <S extends ChunkEntity> List<S> saveAll(Iterable<S> es) { es.forEach(this::save); return (List<S>) chunks; }
        @Override public Optional<ChunkEntity> findById(Long id) { return chunks.stream().filter(c -> c.getId() != null && c.getId().equals(id)).findFirst(); }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<ChunkEntity> findAll() { return new ArrayList<>(chunks); }
        @Override public List<ChunkEntity> findAll(Sort s) { return findAll(); }
        @Override public Page<ChunkEntity> findAll(Pageable p) { return new PageImpl<>(chunks, p, chunks.size()); }
        @Override public List<ChunkEntity> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public <S extends ChunkEntity> List<S> findAll(Example<S> ex) { return List.of(); }
        @Override public <S extends ChunkEntity> List<S> findAll(Example<S> ex, Sort s) { return List.of(); }
        @Override public <S extends ChunkEntity> Page<S> findAll(Example<S> ex, Pageable p) { return Page.empty(); }
        @Override public <S extends ChunkEntity> Optional<S> findOne(Example<S> ex) { return Optional.empty(); }
        @Override public <S extends ChunkEntity> boolean exists(Example<S> ex) { return false; }
        @Override public <S extends ChunkEntity> long count(Example<S> ex) { return 0; }
        @Override public <S extends ChunkEntity, R> R findBy(Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> qf) { return null; }
        @Override public long count() { return chunks.size(); }
        @Override public void deleteById(Long id) {}
        @Override public void delete(ChunkEntity e) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends ChunkEntity> es) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends ChunkEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends ChunkEntity> List<S> saveAllAndFlush(Iterable<S> es) { return saveAll(es); }
        @Override public void deleteAllInBatch(Iterable<ChunkEntity> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public ChunkEntity getById(Long id) { return findById(id).orElse(null); }
        @Override public ChunkEntity getReferenceById(Long id) { return findById(id).orElse(null); }
        @Override public ChunkEntity getOne(Long id) { return getReferenceById(id); }
        @Override public Page<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String id, Pageable p) { return new PageImpl<>(chunks); }
        @Override public List<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String id) { return chunks; }
        @Override public int countByDocumentId(String id) { return chunks.size(); }
        @Override public void deleteByDocumentId(String id) {}
        @Override public List<ChunkEntity> findTop10ByTextContaining(String keyword, Pageable pageable) {
            return chunks.stream().filter(c -> c.getText() != null && c.getText().contains(keyword))
                    .limit(pageable.getPageSize()).toList();
        }
    }
}
