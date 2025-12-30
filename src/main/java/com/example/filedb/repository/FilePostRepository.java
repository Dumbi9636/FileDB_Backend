package com.example.filedb.repository;

import java.io.File;
import java.nio.file.Files; // 파일/디렉토리 생성, 존재 여부 확인
import java.nio.file.Path;  // 파일/디렉토리 경로 표현
import java.nio.file.Paths; // 문자열로부터 Path 객체 생성
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value; // application.properties 값 주입용
import org.springframework.stereotype.Repository;

import com.example.filedb.dto.PostDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper; // JSON <-> 객체 변환 라이브러리

import lombok.RequiredArgsConstructor;
// Repository 에서 해야할 작업
/*
	1. JSON 파일로 저장 <작업 ㅇ>
	2. 게시글 ID 생성 (시퀀스 파일 포함) <작업 ㅇ>
	3. 파일 동시성 제어 ㅇ
	4. 게시글 목록 가져오기 <작업 ㅇ>
	5. 키워드 검색 (파일 필터링) <작업 ㅇ>
	- 20251230 개선사항 - 
	6. 파일 저장 구조 개선, 파티션 적용
	7. inverted Index 적용 
	8. 키워드 토큰화 	 
 */


@Repository
@RequiredArgsConstructor
public class FilePostRepository {
	
	
	// application.properties 에서 설정한 커스텀 프로퍼티 값 주입
	// filedb.base-path=./data
	@Value("${filedb.base-path}")
	private String basePath;
	
	// ObjectMapper 를 사용하여 JSON 읽기, 쓰기 및 변환 작업(직렬, 역직렬) 
	// 읽기에는 ObjectReader를, 쓰기에는 ObjectWriter를 구성하고 사용
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	// 디렉토리명 교체 시 유지보수를 위해...
	private static final String POSTS_DIR_NAME = "posts";
	
	// 인덱스용 상수 경로
	private static final String INDEX_DIR_NAME = "index";
	
	// 역인덱스용 상수 경로
	private static final String INV_DIR_NAME   = "inv"; // inverted index
	
	// TypeReference
	private static final TypeReference<Map<String, List<Long>>> INV_INDEX_TYPE =
	        new TypeReference<>() {};

	        
	// ===== 동시성 제어용 Lock 객체 =====
	// 게시글 데이터 파일에 대한 Lock
	private final Object postLock = new Object();
	// 시퀀스 파일에 대한 Lock
	private final Object sequenceLock = new Object();
	
	
	
	
	// 1. 게시글 저장 
	/* - ID 가 없으면 시퀀스로 새 ID 발급 후 {id}.json 으로 저장
	 */
	public PostDto create(PostDto post) {
		//게시글 파일에 대한 동시성 제어
		synchronized (postLock) { 
			try {
				// create 요청에서는 클라이언트 id 주입 방지
				if(post.getId() != null) {
					throw new IllegalArgumentException("새 게시글 생성 요청에는 id를 포함할 수 없습니다.");
				}
				// 시퀀스로 새 ID 발급
				Long newId = getNextId();
				post.setId(newId);
				
				// 버킷 기반 경로 
				Path postPath = resolvePostPath(newId);
				// 버킷 폴더 생성 
				Files.createDirectories(postPath.getParent());
				// 게시글 객체를 포맷된 JSON 파일로 저장
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(postPath.toFile(), post);
				
				// 저장완료된 객체 반환 
				return post;
				
			}catch(Exception e) {
				throw new RuntimeException("게시글 생성 오류", e);
			}
		}
	}
	
	// 2. ID 시퀀스 생성
	/* - sequence.json 파일에 대해 동시성 제어 적용
	 * - sequenceLock 으로 JVM 내부 동시성 제어
	 */
	private Long getNextId() {
	    // JVM 내부 동시성 제어
	    synchronized (sequenceLock) {
	        try {
	            Path seqPath = Paths.get(basePath, "sequences.json");
	            File seqFile = seqPath.toFile();

	            // 상위 디렉토리 생성
	            Files.createDirectories(seqPath.getParent());

	            Map<String, Object> map;

	            // 파일이 있고, 비어있지 않으면 JSON 읽기
	            if (seqFile.exists() && seqFile.length() > 0) {
	                try (var is = Files.newInputStream(seqPath)) {
	                    map = objectMapper.readValue(is, Map.class);
	                }
	            } else {
	                // 처음이면 기본값 세팅
	                map = new HashMap<>();
	                map.put("post", 0L);
	            }

	            // 기존 시퀀스 값 읽기
	            Object raw = map.getOrDefault("post", 0);
	            long current = (raw instanceof Number) ? ((Number) raw).longValue() : 0L;

	            long next = current + 1;
	            map.put("post", next);

	            // 변경된 시퀀스 값을 파일에 다시 저장
	            try (var os = Files.newOutputStream(seqPath)) {
	                objectMapper.writerWithDefaultPrettyPrinter()
	                        .writeValue(os, map);
	            }

	            return next;
	        } catch (Exception e) {
	            throw new RuntimeException("시퀀스 생성 오류", e);
	        }
	    }
	}
	
	// 3. ID 로 단건 조회 
    public Optional<PostDto> findPostById(Long id) {
        try {
        	// 조회할 파일 경로(생성된 버킷 기반)
            File file = resolvePostPath(id).toFile();
            
            // 파일이 존재하지 않으면 빈 Optional 반
            if (!file.exists()) return Optional.empty();
            
            // 파일이 존재하면 JSON 을 읽어서 PostDto 객체로 변환
            PostDto post = objectMapper.readValue(file, PostDto.class);
            
            // Optional 로 감싸서 변환 
            return Optional.of(post);

        } catch (Exception e) {
            throw new RuntimeException("파일 읽기 오류", e);
        }
    }

    // 4. 전체 목록 조회 
    public List<PostDto> findAllPosts() {
        try {
        	// 게시글이 저장된 디렉토리 객체 
        	Path root = Paths.get(basePath, POSTS_DIR_NAME);
            // 디렉토리가 없다면 빈 리스트 반환
            if (!Files.exists(root)) return List.of();
         
            
            // 결과를 담을 리스트 생성
            List<PostDto> list = new ArrayList<>();
            
            // 하위 폴더까지 .json 전부 탐색
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              PostDto post = objectMapper.readValue(p.toFile(), PostDto.class);
                              list.add(post);
                          } catch (Exception e) {
                              throw new RuntimeException("목록 조회 실패: " + p, e);
                          }
                      });
            }

            // 최신 글 순으로 정렬(ID 기준 내림차순)
            list.sort(Comparator.comparing(PostDto::getId).reversed());
            
            // 정렬된 리스트 반환
            return list;

        } catch (Exception e) {
            throw new RuntimeException("목록 조회 실패", e);
        }
    }

    // 5. 게시글 삭제
    /* ./data/posts/{id}.json 파일 삭제
     * 쓰기(삭제) 작업만 postLock 으로 보호
     */ 
    public void deletePostById(Long id) {
    	synchronized (postLock) {
    		try {
    			// 기존 글 읽기(인덱스 remove용)
                PostDto existing = findPostById(id)
                        .orElseThrow(() -> new RuntimeException("삭제 대상 게시글이 없습니다. id=" + id));

                // (다음 단계) 인덱스 remove
                // removeFromIndex(existing.getTitle(), id);
    			
                // 삭제 대상 파일 경로(생성된 버킷 기반)
    			File file = resolvePostPath(id).toFile();
                // 파일이 존재하면 삭제
    			if (file.exists() && !file.delete()) {
                    throw new RuntimeException("파일 삭제 실패: " + file.getAbsolutePath());
                }
            } catch (Exception e) {
                throw new RuntimeException("파일 삭제 오류", e);
            }
	    }
    }
    
    
    // 6. 게시글 검색 (제목 + 내용, 키워드 포함 여부로 필터링)
    public List<PostDto> searchPosts(String keyword) {
        try {
        	// 검색어를 소문자로 전환
            String lowerKeyword = keyword.toLowerCase();
            
            // 게시글이 저장된 디렉토리 가져오기
            Path root = Paths.get(basePath, POSTS_DIR_NAME);
            if (!Files.exists(root)) return List.of(); // 없으면 전체목록 리턴

            // 검색 결과를 저장할 리스트 
            List<PostDto> result = new ArrayList<>();
            
            // 각 파일(게시글 JSON)을 읽어서 매칭 여부 검사
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              PostDto post = objectMapper.readValue(p.toFile(), PostDto.class);
                              boolean match =
                                  (post.getTitle() != null && post.getTitle().toLowerCase().contains(lowerKeyword)) ||
                                  (post.getContent() != null && post.getContent().toLowerCase().contains(lowerKeyword));

                              if (match) result.add(post);
                          } catch (Exception e) {
                              throw new RuntimeException("검색 중 오류: " + p, e);
                          }
                      });
            }
            
            // 검색 결과를 최신 글(ID 내림차순) 순 정렬
            result.sort(Comparator.comparing(PostDto::getId).reversed());
            // 최종 검색 결과 반환
            return result;	

        } catch (Exception e) {
            throw new RuntimeException("검색 중 오류 발생", e);
        }
    }
    
    
    /**
     * 7. 게시글 ID를 기반으로 저장될 버킷 디렉토리 이름 계산
     *
     * ID % 256 방식으로 256개 버킷을 사용
     * 디렉토리명은 2자리 16진수 문자열 (00 ~ FF)
     *
     *  ex)
     *  id = 1   -> "01"
     *  id = 255 -> "FF"
     *  id = 256 -> "00"
     */
    private String resolveBucketDirectory(Long id) {
        int bucket = (int)(id % 256);
        return String.format("%02X", bucket); // 00~FF
    }

    /**
     * 8. 게시글 ID를 기준으로 실제 게시글 JSON 파일이 저장될 경로 계산
     *
     * 저장 구조:
     *   {basePath}/posts/{bucket}/{id}.json
     *
     * 예)
     *   ./data/posts/0A/123.json
     */
    private Path resolvePostPath(Long id) {
        return Paths.get(basePath, POSTS_DIR_NAME, resolveBucketDirectory(id), id + ".json");
    }
    
    
    /**
     * 9. 역색인(Inverted Index)에서 사용할 shard key 계산
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
     * 10. 토큰을 기준으로 역색인 shard 파일 경로 계산
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

    
    // 11. 검색 키워드 토큰화 규정 
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

    // 12. shard 파일 읽기
    @SuppressWarnings("unchecked")
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

    // 13. shard 파일 저장 
    private void saveShard(Path shardPath, Map<String, List<Long>> shard) {
        try {
            Files.createDirectories(shardPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(shardPath.toFile(), shard);
        } catch (Exception e) {
            throw new RuntimeException("인덱스 shard 저장 실패: " + shardPath, e);
        }
    }
    
    // 14. 인덱스에 token -> id 추가 
    private void addToIndex(String title, Long id) {
    	// 제목 token 화
        List<String> tokens = tokenize(title);
        
        for (String token : tokens) {
            Path shardPath = resolveInvShardPath(token);
            Map<String, List<Long>> shard = loadShard(shardPath);

            List<Long> ids = shard.getOrDefault(token, new ArrayList<>());
            if (!ids.contains(id)) ids.add(id);

            shard.put(token, ids);
            saveShard(shardPath, shard);
        }
    }
    
    // 15. 인덱스에서 token -> id 제거 
    private void removeFromIndex(String title, Long id) {
        List<String> tokens = tokenize(title);
        for (String token : tokens) {
            Path shardPath = resolveInvShardPath(token);
            Map<String, List<Long>> shard = loadShard(shardPath);

            List<Long> ids = shard.get(token);
            if (ids == null) continue;

            ids.remove(id);
            if (ids.isEmpty()) shard.remove(token);
            else shard.put(token, ids);

            saveShard(shardPath, shard);
        }
    }
    
    // 16. 게시글 수정
    public PostDto update(PostDto post) {
        synchronized (postLock) {
            try {
                if (post.getId() == null) {
                    throw new IllegalArgumentException("update()는 id가 필요합니다.");
                }

                // 1) 기존 글 존재 확인 + 기존 값 확보(인덱스 diff용)
                PostDto existing = findPostById(post.getId())
                        .orElseThrow(() -> new RuntimeException("수정 대상 게시글이 없습니다. id=" + post.getId()));

                // 2) (다음 단계) 인덱스 diff
                // removeFromIndex(existing.getTitle(), post.getId());
                // addToIndex(post.getTitle(), post.getId());

                // 3) 파일 덮어쓰기 저장
                Path postPath = resolvePostPath(post.getId());
                Files.createDirectories(postPath.getParent());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(postPath.toFile(), post);

                return post;

            } catch (Exception e) {
                throw new RuntimeException("게시글 수정 오류", e);
            }
        }
    }
    
}
