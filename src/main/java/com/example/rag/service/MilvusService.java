package com.example.rag.service;

import com.example.rag.model.DocumentResponse;
import com.example.rag.model.SearchHit;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.IndexType;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MilvusService {

    private static final String COLLECTION_NAME = "rag_documents";
    private static final int VECTOR_DIM = 1024;

    private final MilvusServiceClient milvusClient;

    public MilvusService(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @PostConstruct
    public void init() {
        createCollectionIfNotExists();
    }

    private void createCollectionIfNotExists() {
        R<Boolean> hasResp = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        if (hasResp.getData() != null && hasResp.getData()) {
            log.info("Collection '{}' already exists", COLLECTION_NAME);
            return;
        }

        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build();

        FieldType titleField = FieldType.newBuilder()
                .withName("title")
                .withDataType(DataType.VarChar)
                .withMaxLength(256)
                .build();

        FieldType chunkTextField = FieldType.newBuilder()
                .withName("chunk_text")
                .withDataType(DataType.VarChar)
                .withMaxLength(4096)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(VECTOR_DIM)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withDescription("RAG document chunks")
                .withFieldTypes(List.of(idField, titleField, chunkTextField, embeddingField))
                .build();

        R<RpcStatus> createResp = milvusClient.createCollection(createParam);
        handleResponse(createResp, "Failed to create collection");
        log.info("Created collection '{}'", COLLECTION_NAME);

        // Create index on the vector field
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName("embedding")
                .withIndexType(IndexType.AUTOINDEX)
                .withMetricType(MetricType.COSINE)
                .build();

        R<RpcStatus> indexResp = milvusClient.createIndex(indexParam);
        handleResponse(indexResp, "Failed to create index");
        log.info("Created index on collection '{}'", COLLECTION_NAME);

        // Load collection to memory
        R<RpcStatus> loadResp = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        handleResponse(loadResp, "Failed to load collection");
        log.info("Loaded collection '{}' into memory", COLLECTION_NAME);
    }

    private void handleResponse(R<RpcStatus> resp, String errorMsg) {
        if (resp.getException() != null) {
            throw new RuntimeException(errorMsg + ": " + resp.getException().getMessage());
        }
    }

    public List<Long> insertChunks(List<String> titles, List<String> chunkTexts, List<List<Float>> embeddings) {
        List<String> titleList = titles.stream()
                .map(t -> t.length() > 256 ? t.substring(0, 256) : t)
                .toList();

        List<String> chunkList = chunkTexts.stream()
                .map(t -> t.length() > 4096 ? t.substring(0, 4096) : t)
                .toList();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("title", titleList));
        fields.add(new InsertParam.Field("chunk_text", chunkList));
        fields.add(new InsertParam.Field("embedding", embeddings));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<MutationResult> insertResp = milvusClient.insert(insertParam);
        if (insertResp.getException() != null) {
            throw new RuntimeException("Failed to insert: " + insertResp.getException().getMessage());
        }

        MutationResult result = insertResp.getData();
        List<Long> ids = result.getIDs().getIntId().getDataList();
        log.info("Inserted {} chunks into Milvus", ids.size());
        return ids;
    }

    public List<SearchHit> searchSimilar(List<Float> queryEmbedding, int topK) {
        List<String> queryFields = List.of("id", "title", "chunk_text");

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(List.of(queryEmbedding))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 10}")
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .withOutFields(queryFields)
                .build();

        R<SearchResults> searchResp = milvusClient.search(searchParam);
        if (searchResp.getException() != null) {
            throw new RuntimeException("Search failed: " + searchResp.getException().getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResp.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);

        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            SearchResultsWrapper.IDScore idScore = scores.get(i);
            long id = idScore.getLongID();
            double score = idScore.getScore();

            // Extract field values using index-based access
            String title = "";
            String chunkText = "";
            try {
                List<?> titleData = wrapper.getFieldData("title", 0);
                if (titleData != null && i < titleData.size()) {
                    title = String.valueOf(titleData.get(i));
                }
                List<?> chunkData = wrapper.getFieldData("chunk_text", 0);
                if (chunkData != null && i < chunkData.size()) {
                    chunkText = String.valueOf(chunkData.get(i));
                }
            } catch (Exception e) {
                log.warn("Failed to extract field data from search result", e);
            }

            hits.add(new SearchHit(id, title, chunkText, score));
        }

        return hits;
    }

    public List<DocumentResponse> listAll() {
        String expr = "id >= 0";
        R<QueryResults> queryResp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(List.of("id", "title", "chunk_text"))
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .build());

        if (queryResp.getException() != null) {
            throw new RuntimeException("Query failed: " + queryResp.getException().getMessage());
        }

        QueryResults results = queryResp.getData();
        return extractDocumentList(results);
    }

    public DocumentResponse getById(long id) {
        String expr = "id == " + id;
        R<QueryResults> queryResp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(List.of("id", "title", "chunk_text"))
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .build());

        if (queryResp.getException() != null) {
            throw new RuntimeException("Query failed: " + queryResp.getException().getMessage());
        }

        List<DocumentResponse> docs = extractDocumentList(queryResp.getData());
        return docs.isEmpty() ? null : docs.get(0);
    }

    public void deleteById(long id) {
        String expr = "id == " + id;
        R<MutationResult> deleteResp = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(expr)
                .build());

        if (deleteResp.getException() != null) {
            throw new RuntimeException("Delete failed: " + deleteResp.getException().getMessage());
        }
        log.info("Deleted document with id={}", id);
    }

    private List<DocumentResponse> extractDocumentList(QueryResults results) {
        List<DocumentResponse> docs = new ArrayList<>();

        var fieldsList = results.getFieldsDataList();
        if (fieldsList.isEmpty()) {
            return docs;
        }

        // Extract the three fields
        List<Long> idList = new ArrayList<>();
        List<String> titleList = new ArrayList<>();
        List<String> chunkList = new ArrayList<>();

        for (var field : fieldsList) {
            switch (field.getFieldName()) {
                case "id" -> idList.addAll(field.getScalars().getLongData().getDataList());
                case "title" -> titleList.addAll(field.getScalars().getStringData().getDataList());
                case "chunk_text" -> chunkList.addAll(field.getScalars().getStringData().getDataList());
            }
        }

        int count = idList.size();
        for (int i = 0; i < count; i++) {
            long id = idList.get(i);
            String title = i < titleList.size() ? titleList.get(i) : "";
            String chunk = i < chunkList.size() ? chunkList.get(i) : "";
            docs.add(new DocumentResponse(id, title, chunk));
        }

        return docs;
    }


    public List<Long> getAllIds() {
        String expr = "id >= 0";
        R<QueryResults> queryResp = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(List.of("id"))
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .build());

        if (queryResp.getException() != null) {
            throw new RuntimeException("Query failed: " + queryResp.getException().getMessage());
        }

        List<Long> ids = new ArrayList<>();
        for (var field : queryResp.getData().getFieldsDataList()) {
            if ("id".equals(field.getFieldName())) {
                ids.addAll(field.getScalars().getLongData().getDataList());
            }
        }
        return ids;
    }

    @PreDestroy
    public void cleanup() {
        milvusClient.close();
        log.info("Closed Milvus client");
    }
}
