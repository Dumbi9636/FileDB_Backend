package com.example.filedb.repository;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;



@Repository
public class InvertedIndexRepository {
	
	@Value("${filedb.base-path}")
	private String basePath;
	
	
	// 인덱스용 상수 경로
	private static final String INDEX_DIR_NAME = "index";
	
	// 역인덱스용 상수 경로
	private static final String INV_DIR_NAME   = "inv"; // inverted index
	
	
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	private static final TypeReference<Map<String, List<Long>>> INV_INDEX_TYPE = new TypeReference<>() {};
	
	// shard 파일 읽기/쓰기 동시성 제어
	private final Object indexLock = new Object();
	
	/**
     * 1. 역색인(Inverted Index)에서 사용할 shard key 계산
     *
     * 토큰의 앞 2글자를 기준으로 shard를 분리하여 인덱스 파일 크기 증가를 방지하기 위함
     * 토큰 길이가 1인 경우 '_'로 패딩한다.
     * 토큰이 null 또는 빈 문자열인 경우 "__"를 반환한다.
     *
     * ex)
     *   "spring"  -> "sp"
     *   "swagger" -> "sw"
     *   "a"       -> "a_"
     *   ""        -> "__"
     */
    private String resolveShardKeyFromToken(String token) {
        if (token == null) return "__";

        token = token.trim().toLowerCase();
        if (token.isEmpty()) return "__";

        return token.length() >= 2
                ? token.substring(0, 2)
                : token + "_";
    }
    
    
    /**
     * 2. 토큰을 기준으로 역색인 shard 파일 경로 계산
     *
     * 저장 구조:
     *   {basePath}/index/inv/{shardKey}.json
     *
     * ex)
     *   token = "spring"  -> ./data/index/inv/sp.json
     *   token = "a"       -> ./data/index/inv/a_.json
     */
    private Path resolveInvShardPath(String token) {
        return Paths.get(
            basePath,
            INDEX_DIR_NAME,
            INV_DIR_NAME,
            resolveShardKeyFromToken(token) + ".json"
        );
    }
    
    
 // 3. 검색 키워드 토큰화 규정 
    private List<String> tokenize(String text) {
    	// 내용이 없으면 빈 list 반환
        if (text == null || text.isBlank()) return List.of();

        // 소문자 + 구분자 기준 분리
        String[] parts = text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", " ") // 문자/숫자 외는 공백 처리
                .trim()
                .split("\\s+");

        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p); // 1글자 토큰은 제외
        }
        return tokens;
    }

    
    // 4. shard 파일 읽기
    private Map<String, List<Long>> loadShard(Path shardPath) {
        try {
            File file = shardPath.toFile();
            if (!file.exists() || file.length() == 0) {
            	return new HashMap<>();
        }
            return objectMapper.readValue(file, INV_INDEX_TYPE);
            
        } catch (Exception e) {
            throw new RuntimeException("인덱스 shard 로드 실패: " + shardPath, e);
        }
    }

    
    // 5. shard 파일 저장 
    private void saveShard(Path shardPath, Map<String, List<Long>> shard) {
        try {
            Files.createDirectories(shardPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(shardPath.toFile(), shard);
        } catch (Exception e) {
            throw new RuntimeException("인덱스 shard 저장 실패: " + shardPath, e);
        }
    }
    
    
    // 6. 인덱스에 token -> id 추가 
    private void addToIndex(String title, Long id) {
        if (id == null) return;
        List<String> tokens = tokenize(title);
        if (tokens.isEmpty()) return;

        Map<Path, Map<String, List<Long>>> shardCache = new HashMap<>();

        for (String token : tokens) {
            Path shardPath = resolveInvShardPath(token);
            Map<String, List<Long>> shard = shardCache.computeIfAbsent(shardPath, this::loadShard);

            List<Long> ids = shard.getOrDefault(token, new ArrayList<>());
            if (!ids.contains(id)) ids.add(id);
            shard.put(token, ids);
        }

        for (var entry : shardCache.entrySet()) {
            saveShard(entry.getKey(), entry.getValue());
        }
    }

    
    // 7. 인덱스에서 token -> id 제거 
    private void removeFromIndex(String title, Long id) {
        if (id == null) return;
        List<String> tokens = tokenize(title);
        if (tokens.isEmpty()) return;

        Map<Path, Map<String, List<Long>>> shardCache = new HashMap<>();

        for (String token : tokens) {
            Path shardPath = resolveInvShardPath(token);
            Map<String, List<Long>> shard = shardCache.computeIfAbsent(shardPath, this::loadShard);

            List<Long> ids = shard.get(token);
            if (ids == null) continue;

            ids.remove(id);
            if (ids.isEmpty()) shard.remove(token);
            else shard.put(token, ids);
        }

        for (var entry : shardCache.entrySet()) {
            saveShard(entry.getKey(), entry.getValue());
        }
    }

    
    // 8. keyword로 후보 id 검색(AND 교집합) 
    public List<Long> searchIds(String keyword) {
        List<String> tokens = tokenize(keyword);
        if (tokens.isEmpty()) return List.of();

        synchronized (indexLock) {
            Set<Long> candidates = null;

            for (String token : tokens) {
                Path shardPath = resolveInvShardPath(token);
                Map<String, List<Long>> shard = loadShard(shardPath);

                List<Long> ids = shard.getOrDefault(token, List.of());
                Set<Long> idSet = new HashSet<>(ids);

                if (candidates == null) candidates = idSet;
                else candidates.retainAll(idSet);

                if (candidates.isEmpty()) break;
            }

            if (candidates == null || candidates.isEmpty()) return List.of();

            List<Long> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.reverseOrder());
            return sorted;
        }
    }
    
    // 제목 인덱스 추가
    public void addTitle(Long postId, String title) {
        synchronized (indexLock) {
            addToIndex(title, postId);
        }
    }

    // 제목 인덱스 제거
    public void removeTitle(Long postId, String title) {
        synchronized (indexLock) {
            removeFromIndex(title, postId);
        }
    }

}
