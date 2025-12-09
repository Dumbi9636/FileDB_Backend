package com.example.filedb.service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.filedb.dto.PostDto;
import com.example.filedb.dto.PostPageResponse;
import com.example.filedb.exception.PostNotFoundException;
import com.example.filedb.repository.FilePostRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostService {
	
	// 의존성 주입
	private final FilePostRepository postRepository;
	
	// 날짜는 String 으로 저장
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
	
	// 이미지 파일이 저장될 물리 경로 (application.properties 에서 주입)
	@Value("${filedb.upload-path}")
	private String uploadPath;
	
		
	// 1. 새 게시글 생성
	public PostDto createPost(PostDto request) {
		String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
		
		// createdAt / updatedAt 세팅 
		request.setCreatedAt(now);
		request.setUpdatedAt(now);
		
		// ID 는 Repositroy 에서 시퀀스로 채우기
		return postRepository.save(request);
	}
	
	
	// 2. 기존 게시글 수정
	public PostDto updatePost(Long id, PostDto request) {
		
		// 기존 게시글 조회 (없으면 PostNotFoundException 예외 던지기)
		PostDto existing = postRepository.findPostById(id)
				.orElseThrow(()-> new PostNotFoundException(id));
		
		// 변경 가능한 필드만 교체
		existing.setTitle(request.getTitle());
		existing.setContent(request.getContent());
		existing.setWriter(request.getWriter());
		
		// 수정 시간 갱신
		existing.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
		
		// 다시 저장
		return postRepository.save(existing);
	}
	
	
	// 3. 단건 조회(없으면 PostNotFoundException 예외 던지기)
	public PostDto getPost(Long id) {
		return postRepository.findPostById(id)
				.orElseThrow(()-> new PostNotFoundException(id));
	}
	
	
	// 4. 전체 게시글 목록 조회
	public List<PostDto> getAllPosts(){
		return postRepository.findAllPosts();
	}
	
	
	// 5. 키워드 검색(제목+내용)
	public List<PostDto> searchPosts(String keyword) {
		// 키워드를 입력하지 않으면 전체 목록 반환
	    if (keyword == null || keyword.isBlank()) {
	        return getAllPosts();
	    }
	    // searchPosts 메소드를 이용해서 키워드 검색 후 반환
	    return postRepository.searchPosts(keyword);
	}
	
	
	// 6. 게시글 삭제
    public void deletePost(Long postId) {
        // ID 가 없다면 Post 예외 던지기
        if (postRepository.findPostById(postId).isEmpty()) {
            throw new PostNotFoundException(postId);
        }
        // 있다면 ID 를 이용해서 삭제
        postRepository.deletePostById(postId);
    }
    
    
    // 7. 게시글 이미지 업로드 처리
    // 파일을 {id}.확장자 형태로 저장
    // 저장된 경로를 PostDto.imagePath 에 반영 후, 게시글 저장
    public PostDto uploadPostImage(Long id, MultipartFile file) {
    	
    	// 게시글 존재 여부 확인, 없으면 PostNotFoundExcetpion
    	PostDto post = postRepository.findPostById(id)
    			.orElseThrow(()-> new PostNotFoundException(id));
    	
    	// 업로드할 파일이 비어있는지 검증
    	if(file == null || file.isEmpty()) {
    		throw new IllegalArgumentException("업로드할 파일이 없습니다.");
    	}
    	
    	// 원본 파일명에서 확장자 추출
    	String originalName = file.getOriginalFilename();
    	String extension = "";
    	
    	if(originalName != null && originalName.lastIndexOf(".") != -1) {
    		extension = originalName.substring(originalName.lastIndexOf("."));
    	}
    	
    	// 확장자가 없는 경우 기본 확장자 부여
    	if(extension.isBlank()) {
    		extension = ".dat";
    	}
    	
    	// 저장할 파일명: {id}.확장자
    	String savedFileName = id + extension;
    	
    	// 업로드 디렉토리 생성(절대경로)
    	File dir = new File(uploadPath).getAbsoluteFile();
    	if(!dir.exists()) {
    		boolean created = dir.mkdirs();
    		if(!created) {
    			throw new RuntimeException("이미지 저장 폴더를 생성할 수 없습니다");
    		}
    	}
    	
    	// 실제 저장될 파일 객체 생성
    	File dest = new File(dir, savedFileName);
    	try {
    		file.transferTo(dest);
    	}catch(IOException e) {
    		e.printStackTrace();
    		throw new RuntimeException("이미지 파일 저장 중 오류가 발생했습니다.");
    	}
    	
    	// 게시글 DTO 에 이미지 경로 세팅
    	// WebConfig 에서 /images/** -> uploadPath 로 매핑함
    	post.setImagePath("/images/" + savedFileName);
    	
    	// 수정 시간 갱신 후 게시글 다시 저장
    	post.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
    	return postRepository.save(post);
    }
    
    
    // 8. 내부 공통 유틸: List<PostDto> -> PostPageResponse 로 변환
    /*
     *  - 전체/검색 결과에 공통으로 사용되는 페이징 처리
     *  - DB 없는 파일 기반으로 메모리 리스트를 자르는 방식으로 페이징 구현 
     */
    private PostPageResponse slicePage(List<PostDto> source, int page, int size) {
        // page, size 기본값/이상치 보정
        if (page < 0) page = 0;
        if (size <= 0) size = 10;
        
        // 전체 데이터 개수
        int totalElements = source.size();
        // 전체 페이지 수
        int totalPages = (totalElements == 0) ? 0
                : (int) Math.ceil((double) totalElements / size);
        
        // 자르기 시작 지점 계산
        int fromIndex = page * size;
        
        // 시작 인덱스가 전체 개수보다 크다면 빈 페이지 반환
        if (fromIndex >= totalElements) {
            // 범위를 넘어가면 빈 페이지 반환
            return PostPageResponse.builder()
                    .page(page)
                    .size(size)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .content(List.of())
                    .build();
        }
        
        // 종료 지점
        int toIndex = Math.min(fromIndex + size, totalElements);
        
        // 실제 page 범위에 해당하는 데이터 자르기
        List<PostDto> content = source.subList(fromIndex, toIndex);
        
        // 페이징 응답 DTO 생성
        return PostPageResponse.builder()
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .content(content)
                .build();
    }
    
    // 9. 검색 결과 페이징
    // - 검색 수행 후 결과 리스트 페이징 처리
    public PostPageResponse searchPostsPage(String keyword, int page, int size) {
    	List<PostDto> result = searchPosts(keyword);
        return slicePage(result, page, size);
    }
    
    
    // 10. 전체 목록 페이징
    // - 전체 목록을 가져온 뒤 페이징 처리
    public PostPageResponse getPostsPage(int page, int size) {
    	List<PostDto> all = getAllPosts();
    	return slicePage(all, page, size);
    }
    
}
