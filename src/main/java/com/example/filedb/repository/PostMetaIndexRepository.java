package com.example.filedb.repository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.example.filedb.dto.PostMetaDto;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class PostMetaIndexRepository {

    @Value("${filedb.base-path}")
    private String basePath;

    private static final String INDEX_DIR_NAME = "index";
    private static final String META_DIR_NAME  = "meta";
    private static final String META_FILE_NAME = "posts_meta.jsonl";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 동시성 제어
    private final Object metaLock = new Object();

    // 캐시: id -> meta
    private final Map<Long, PostMetaDto> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    private Path metaFilePath() {
        return Paths.get(basePath, INDEX_DIR_NAME, META_DIR_NAME, META_FILE_NAME);
    }

    /** 서버 시작 후 최초 1회 로딩 */
    private void ensureLoaded() {
        if (cacheLoaded) return;
        synchronized (metaLock) {
            if (cacheLoaded) return;
            loadFromDisk();
            cacheLoaded = true;
        }
    }

    private void loadFromDisk() {
        cache.clear();
        Path path = metaFilePath();
        File f = path.toFile();
        if (!f.exists() || f.length() == 0) return;

        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                PostMetaDto meta = objectMapper.readValue(line, PostMetaDto.class);
                if (meta.getId() != null) {
                    cache.put(meta.getId(), meta);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("메타 인덱스 로드 실패: " + path, e);
        }
    }

    private void persistAll() {
        Path path = metaFilePath();
        try {
            Files.createDirectories(path.getParent());
            // 임시 파일에 쓰고 원자적 교체(가능하면)
            Path tmp = path.resolveSibling(META_FILE_NAME + ".tmp");

            try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                // 최신순으로 저장(큰 id가 먼저)
                List<PostMetaDto> metas = new ArrayList<>(cache.values());
                metas.sort(Comparator.comparing(PostMetaDto::getId).reversed());

                for (PostMetaDto meta : metas) {
                    bw.write(objectMapper.writeValueAsString(meta));
                    bw.newLine();
                }
            }

            // Windows에서도 대체로 잘 동작. (실패하면 fallback)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignore) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (Exception e) {
            throw new RuntimeException("메타 인덱스 저장 실패", e);
        }
    }

    /** create 시 호출 */
    public void upsert(PostMetaDto meta) {
        if (meta == null || meta.getId() == null) return;
        synchronized (metaLock) {
            ensureLoaded();
            cache.put(meta.getId(), meta);
            persistAll();
        }
    }

    /** delete 시 호출 */
    public void remove(Long id) {
        if (id == null) return;
        synchronized (metaLock) {
            ensureLoaded();
            cache.remove(id);
            persistAll();
        }
    }

    /** 목록 페이징: 최신순 기준 */
    public List<PostMetaDto> findPage(int page, int size) {
        ensureLoaded();

        if (page < 0) page = 0;
        if (size <= 0) size = 10;

        // 최신순 id 정렬된 리스트 만들기
        List<PostMetaDto> metas = new ArrayList<>(cache.values());
        metas.sort(Comparator.comparing(PostMetaDto::getId).reversed());

        int total = metas.size();
        int from = page * size;
        if (from >= total) return List.of();
        int to = Math.min(from + size, total);
        return metas.subList(from, to);
    }

    
    public int count() {
        ensureLoaded();
        return cache.size();
    }
    
    
    public List<PostMetaDto> findByIds(List<Long> ids) {
        ensureLoaded();

        List<PostMetaDto> result = new ArrayList<>();
        for (Long id : ids) {
            PostMetaDto meta = cache.get(id);
            if (meta != null) result.add(meta);
        }
        return result;
    }

}
