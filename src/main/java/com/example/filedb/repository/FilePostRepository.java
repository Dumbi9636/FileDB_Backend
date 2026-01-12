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
import com.example.filedb.dto.PostMetaDto;
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
	
	// 인덱스 의존 주입
	private final InvertedIndexRepository invertedIndexRepository;

	// 목록 인덱스 의존 주입 
	private final PostMetaIndexRepository postMetaIndexRepository;
	
	// 디렉토리명 교체 시 유지보수를 위해...
	private static final String POSTS_DIR_NAME = "posts";
	        
	// ===== 동시성 제어용 Lock 객체 =====
	// 게시글 데이터 파일에 대한 Lock
	private final Object postLock = new Object();
	// 시퀀스 파일에 대한 Lock
	private final Object sequenceLock = new Object();
	
	
	
	
	// 1. 게시글 저장 
	// ID 가 없으면 시퀀스로 새 ID 발급 후 {id}.json 으로 저장
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
				
				invertedIndexRepository.addTitle(post.getId(), post.getTitle());
				postMetaIndexRepository.upsert(toMeta(post));

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
    
    /// 5-1) 제목/내용/작성자 같은 "검색/노출" 필드 변경용
    public PostDto updateContent(PostDto post) {
        synchronized (postLock) {
            try {
                if (post.getId() == null) throw new IllegalArgumentException("id가 필요합니다.");

                PostDto existing = findPostById(post.getId())
                        .orElseThrow(() -> new RuntimeException("수정 대상 게시글이 없습니다. id=" + post.getId()));

                // title이 바뀐 경우에만 인덱스 갱신 (최적화)
                String oldTitle = existing.getTitle();
                String newTitle = post.getTitle();
                if (oldTitle != null && newTitle != null && !oldTitle.equals(newTitle)) {
                    invertedIndexRepository.removeTitle(post.getId(), oldTitle);
                    invertedIndexRepository.addTitle(post.getId(), newTitle);
                } else if (oldTitle == null && newTitle != null) {
                    invertedIndexRepository.addTitle(post.getId(), newTitle);
                } else if (oldTitle != null && newTitle == null) {
                    invertedIndexRepository.removeTitle(post.getId(), oldTitle);
                }

                // 파일 저장
                Path postPath = resolvePostPath(post.getId());
                Files.createDirectories(postPath.getParent());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(postPath.toFile(), post);
                postMetaIndexRepository.upsert(toMeta(post));

                return post;
            } catch (Exception e) {
                throw new RuntimeException("게시글 수정 오류(updateContent)", e);
            }
        }
    }

    // 5-2) 이미지 같은 "메타" 필드 변경용 (인덱스 갱신 없음)
    public PostDto updateImage(PostDto post) {
        synchronized (postLock) {
            try {
                if (post.getId() == null) throw new IllegalArgumentException("id가 필요합니다.");

                // 존재 확인만
                findPostById(post.getId())
                        .orElseThrow(() -> new RuntimeException("수정 대상 게시글이 없습니다. id=" + post.getId()));

                // 파일 저장만
                Path postPath = resolvePostPath(post.getId());
                Files.createDirectories(postPath.getParent());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(postPath.toFile(), post);
                postMetaIndexRepository.upsert(toMeta(post));

                return post;
            } catch (Exception e) {
                throw new RuntimeException("이미지/메타 수정 오류(updateImage)", e);
            }
        }
    }
    
    
    // 6. 게시글 삭제
    // ./data/posts/{id}.json 파일 삭제, 쓰기(삭제) 작업만 postLock 으로 보호
    public void deletePostById(Long id) {
    	synchronized (postLock) {
    		try {
    			// 기존 글 읽기(인덱스 remove용)
                PostDto existing = findPostById(id)
                        .orElseThrow(() -> new RuntimeException("삭제 대상 게시글이 없습니다. id=" + id));
                
                invertedIndexRepository.removeTitle(id, existing.getTitle());
                postMetaIndexRepository.remove(id);

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
    
    
    // 7. 게시글 검색 (제목 + 내용, 키워드 포함 여부로 필터링)
    public List<PostDto> searchPosts(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();

        List<Long> ids = invertedIndexRepository.searchIds(keyword);
        if (ids.isEmpty()) return List.of();

        List<PostDto> result = new ArrayList<>();
        for (Long id : ids) {
            findPostById(id).ifPresent(result::add);
        }
        // searchIds가 이미 내림차순 정렬이면 생략 가능
        result.sort(Comparator.comparing(PostDto::getId).reversed());
        return result;
    }

    // 8. 목록/페이징 전용 (JSON 안 읽음)
    public List<Long> searchPostIds(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return invertedIndexRepository.searchIds(keyword);
    }

    
    /**
     * 9. 게시글 ID를 기반으로 저장될 버킷 디렉토리 이름 계산
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
     * 10. 게시글 ID를 기준으로 실제 게시글 JSON 파일이 저장될 경로 계산
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
    
    // 11. 목록 인덱스 용
    private PostMetaDto toMeta(PostDto p) {
        return PostMetaDto.builder()
                .id(p.getId())
                .title(p.getTitle())
                .writer(p.getWriter())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .imagePath(p.getImagePath())
                .build();
    }

    // 12. meta rebuild
    public void rebuildMetaIndex() {
        List<PostDto> all = findAllPosts(); // 여기만 1회 느림
        for (PostDto p : all) {
            postMetaIndexRepository.upsert(toMeta(p));
        }
    }

}
