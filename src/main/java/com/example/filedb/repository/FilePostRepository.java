package com.example.filedb.repository;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import com.fasterxml.jackson.databind.ObjectMapper; // JSON <-> 객체 변환 라이브러리

import lombok.RequiredArgsConstructor;
// Repository 에서 해야할 작업
/*
	1. JSON 파일로 저장 <작업 ㅇ>
	2. 게시글 ID 생성 (시퀀스 파일 포함) <작업 ㅇ>
	3. 파일 락 처리 
	4. 게시글 목록 가져오기 <작업 ㅇ>
	5. 키워드 검색 (파일 필터링) <작업 ㅇ>
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
	
	// ===== 동시성 제어용 Lock 객체 =====
	// 게시글 데이터 파일에 대한 Lock
	private final Object postLock = new Object();
	
	// 시퀀스 파일에 대한 Lock
	private final Object sequenceLock = new Object();
	
	
	
	
	// 1. 게시글 저장 
	/* - ID 가 없으면 시퀀스로 새 ID 발급 후 {id}.json 으로 저장
	 * - ID 가 있으면 같은 파일명을 가진 JSON을 덮어써서 수정
	 */
	public PostDto save(PostDto post) {
		//게시글 파일에 대한 동시성 제어
		synchronized (postLock) { 
			try {
				List<PostDto> posts = findAllPosts(); // 파일에서 전체 읽기
				
				// 새 게시글이면 ID 시퀀스에서 발급
				if(post.getId()== null) {
					// 새 게시글 -> 시퀀스에서 ID 발급
					post.setId(getNextId());
					posts.add(post);
				}
				
				// /data/posts/ 디렉토리 경로 생성
				Path postDir = Paths.get(basePath, POSTS_DIR_NAME);
				Files.createDirectories(postDir);
				
				// 게시글이 저장될 파일 경로 생성: ./data/posts/title.json
				File file = postDir.resolve(post.getId()+".json").toFile();
				
				// 게시글 객체를 포맷된 JSON 파일로 저장
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, post);
				
				// 저장완료된 객체 반환 
				return post;
				
			}catch(Exception e) {
				throw new RuntimeException("파일 저장 오류", e);
			}
		}
	}
	
	// 2. ID 시퀀스 생성
	/* - sequence.json 파일에 대해 동시성 제어 적용
	 * - sequenceLock 으로 JVM 내부 동시성 제어
	 * - FileChannel + FileLock 으로 OS 레밸 파일락 고려
	 */
	private Long getNextId() {
		// JVM 내부 동시성 제어
		synchronized (sequenceLock) {
			try {
				// 시퀀스를 저장할 파일 경로: ./data/sequences.json
				Path seqPath = Paths.get(basePath, "sequences.json");
				File seqFile = seqPath.toFile();
				
				// 파일이 들어있는 상위 폴더(Path)를 가져올 수 있도록 디렉토리 생성
	            Files.createDirectories(seqPath.getParent());
	            
	            // 파일이 없으면 먼저 빈 파일 생성
	            if (!seqFile.exists()) {
	                seqFile.createNewFile();
	            }
				
	            // OS 레벨 파일 락
	            try (RandomAccessFile raf = new RandomAccessFile(seqFile, "rw");
	                 FileChannel channel = raf.getChannel();
	                 FileLock lock = channel.lock()) {
	
	                // === 여기부터는 OS + JVM 둘 다 lock 이 잡힌 상태 ===
	                // 현재 시퀀스 값 읽기
	                Map<String, Object> map;
	                // 파일이 비어있지 않다면 JSON 읽기
	                if (seqFile.length() > 0) {
	                    map = objectMapper.readValue(seqFile, Map.class);
	                } else {
	                	// 파일이 비어있으면 기본값 세팅
	                    map = new HashMap<>();
	                    map.put("post", 0L);
	                }
	                
	                // 기존 시퀀스 값 읽기 (파일이 Number 또는 Integer 로 저장될 수 있으므로 Object 로 캐스팅)
	                Object raw = map.getOrDefault("post", 0);
	                long current = (raw instanceof Number) ? ((Number) raw).longValue() : 0L;
	                
	                // 다음 ID 생성
	                long next = current + 1;
	                map.put("post", next);
	
	                // 변경된 시퀀스 값을 파일에 다시 저장 
	                objectMapper.writerWithDefaultPrettyPrinter()
	                        .writeValue(seqFile, map);
	                
	                // 새로운 ID 반환
	                return next;
	            }
	        } catch (Exception e) {
	            throw new RuntimeException("시퀀스 생성 오류", e);
	        }
		}
	}
	
	// 3. ID 로 단건 조회 
    public Optional<PostDto> findPostById(Long id) {
        try {
        	// 조회할 파일 경로
        	// ./data/posts/{id}.json
            File file = Paths.get(basePath, "posts", id + ".json").toFile();
            
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
            File dir = Paths.get(basePath, POSTS_DIR_NAME).toFile();
            
            // 디렉토리가 없다면 빈 리스트 반환
            if (!dir.exists()) return List.of();
            
            // 디렉토리 안의 파일 목록 중에서 확장자가 .json 인 것들 선택
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            
            // 파일이 아예 없다면 빈 리스트 반환
            if (files == null) return List.of();
            
            // 결과를 담을 리스트 생성
            List<PostDto> list = new ArrayList<>();
            
            // 각 JSON 파일을 읽어서 PostDto 로 변환 후 리스트에 넣기
            for (File file : files) {
                PostDto post = objectMapper.readValue(file, PostDto.class);
                list.add(post);
            }

            // 최신 글 순으로 정렬(ID 기준 내림차순)
            list.sort(Comparator.comparing(PostDto::getId).reversed());
            
            // 정렬된 리스트 반환
            return list;

        } catch (Exception e) {
            throw new RuntimeException("목록 조회 실패", e);
        }
    }

    // 5. 삭제
    /* ./data/posts/{id}.json 파일 삭제
     * 쓰기(삭제) 작업만 postLock 으로 보호
     */ 
    public void deletePostById(Long id) {
    	synchronized (postLock) {
    		try {
                // 삭제 대상 파일 경로: ./data/posts/{id}.json
                File file = Paths.get(basePath, POSTS_DIR_NAME, id + ".json").toFile();
                
                // 파일이 존재하면 삭제
                if (file.exists()) {
                    if (!file.delete()) {
                        throw new RuntimeException("파일 삭제 실패: " + file.getAbsolutePath());
                    }
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
            File dir = Paths.get(basePath, POSTS_DIR_NAME).toFile();
            if (!dir.exists()) return List.of(); // 없으면 전체목록 리턴
            
            // 디렉토리 내부의 JSON 파일만 목록으로 가져오기
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) return List.of(); // 없으면 전체목록 리턴
            
            // 검색 결과를 저장할 리스트 
            List<PostDto> result = new ArrayList<>();
            
            // 각 파일(게시글 JSON)을 읽어서 매칭 여부 검사
            for (File file : files) {
            	// JSON -> PostDto 로 변환
                PostDto post = objectMapper.readValue(file, PostDto.class);
                
                // 제목 또는 내용에 검색어가 포함되어 있는지 체크 (제목, 내용)
                boolean match =
                        (post.getTitle() != null && post.getTitle().toLowerCase().contains(lowerKeyword)) ||
                        (post.getContent() != null && post.getContent().toLowerCase().contains(lowerKeyword));
                // 매칭되면 검색 결과 리스트에 담기
                if (match) {
                    result.add(post);
                }
            }
            
            // 검색 결과를 최신 글(ID 내림차순) 순 정렬
            result.sort(Comparator.comparing(PostDto::getId).reversed());
            
            // 최종 검색 결과 반환
            return result;	

        } catch (Exception e) {
            throw new RuntimeException("검색 중 오류 발생", e);
        }
    }
}
