package com.example.filedb.service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.filedb.dto.PostDto;
import com.example.filedb.dto.PostMetaDto;
import com.example.filedb.dto.PostPageResponse;
import com.example.filedb.exception.PostNotFoundException;
import com.example.filedb.repository.FilePostRepository;
import com.example.filedb.repository.PostMetaIndexRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostService {
	
	// 의존성 주입
	private final FilePostRepository postRepository;
	private final PostMetaIndexRepository postMetaIndexRepository;
	
	// 날짜는 String 으로 저장
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
	
	
	// 페이지 블록 크기 (1~10, 11~20 ... 처럼 보여줄 단위)
	private static final int BLOCK_SIZE = 10;
	
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
		return postRepository.create(request);
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
		return postRepository.updateContent(existing);
	}
	
	
	// 3. 단건 조회(없으면 PostNotFoundException 예외 던지기)
	public PostDto getPost(Long id) {
		return postRepository.findPostById(id)
				.orElseThrow(()-> new PostNotFoundException(id));
	}
	
	
	// 4. 게시글 삭제
    public void deletePost(Long postId) {
    	postRepository.findPostById(postId)
    		.orElseThrow(() -> new PostNotFoundException(postId));
		postRepository.deletePostById(postId);

    }

    /**
     * (공통) 블록 페이징 메타데이터 계산 후 builder 에 주입
     *
     *  page는 0-based(0이 1페이지)로 유지 (기존 코드 호환)
     *  startPage/endPage는 화면에 표시할 1-based 페이지 번호
     *  prev/next 관련 값(prevPage 등)은 프론트가 그대로 요청에 쓸 수 있도록 0-based로 내려줌
     */
    private <T> PostPageResponse.PostPageResponseBuilder<T> applyBlockPagingMeta(
            PostPageResponse.PostPageResponseBuilder<T> b,
            int page, int size, long totalElements, int totalPages
    ) {
        // totalPages가 0이면(데이터 없음) 버튼도 모두 비활성
        if (totalPages <= 0) {
            return b
                    .startPage(0)
                    .endPage(0)
                    .hasPrevPage(false)
                    .hasNextPage(false)
                    .hasPrevBlock(false)
                    .hasNextBlock(false)
                    .prevPage(0)
                    .nextPage(0)
                    .prevBlockPage(0)
                    .nextBlockPage(0);
        }

        // 현재 페이지(표시용, 1-based)
        int currentPageDisplay = Math.min(Math.max(page + 1, 1), totalPages);

        // 현재 블록의 시작/끝 페이지(표시용, 1-based)
        int startPage = ((currentPageDisplay - 1) / BLOCK_SIZE) * BLOCK_SIZE + 1;
        int endPage = Math.min(startPage + BLOCK_SIZE - 1, totalPages);

        boolean hasPrevPage = currentPageDisplay > 1;
        boolean hasNextPage = currentPageDisplay < totalPages;

        boolean hasPrevBlock = startPage > 1;
        boolean hasNextBlock = endPage < totalPages;

        // 프론트에서 API 요청에 바로 쓸 수 있도록 0-based index로 내림
        int prevPageIndex = hasPrevPage ? (page - 1) : 0;
        int nextPageIndex = hasNextPage ? (page + 1) : (totalPages - 1);

        // prevBlock: "이전 블록의 마지막 페이지"로 이동 (예: 11~20에서 << 누르면 10페이지)
        // display 목표 = startPage - 1  → index = (startPage - 2)
        int prevBlockIndex = hasPrevBlock ? (startPage - 2) : 0;

        // nextBlock: "다음 블록의 첫 페이지"로 이동 (예: 1~10에서 >> 누르면 11페이지)
        // display 목표 = endPage + 1 → index = (endPage)
        int nextBlockIndex = hasNextBlock ? endPage : (totalPages - 1);

        return b
                .startPage(startPage)
                .endPage(endPage)
                .hasPrevPage(hasPrevPage)
                .hasNextPage(hasNextPage)
                .hasPrevBlock(hasPrevBlock)
                .hasNextBlock(hasNextBlock)
                .prevPage(prevPageIndex)
                .nextPage(nextPageIndex)
                .prevBlockPage(prevBlockIndex)
                .nextBlockPage(nextBlockIndex);
    }
    
    
    // 5. 검색 결과 페이징 (메타 인덱스 기반)
    // id 의 경우 inverted index 에서 최신순으로 정렬되어 반환
    public PostPageResponse<PostMetaDto> searchPostsPage(String keyword, int page, int size) {
    	page = Math.max(page, 0);
    	size = (size <= 0) ? 10 : size;
    	keyword = (keyword == null) ? "" : keyword.trim();
    	
    	// 검색어가 없으면 빈 결과
        if (keyword.isBlank()) {
            var builder = PostPageResponse.<PostMetaDto>builder()
                    .page(page)
                    .size(size)
                    .totalElements(0)
                    .totalPages(0)
                    .content(List.of());

            return applyBlockPagingMeta(builder, page, size, 0, 0).build();
        }
        
        // 검색 인덱스로 ID만 조회 
        List<Long> ids = postRepository.searchPostIds(keyword);
        
        // 이 메서드는 invertedIndexRepository.searchIds()만 감싼 것
        int total = ids.size();
        int totalPages = (total == 0) ? 0 : (int) Math.ceil((double) total / size);
        
        int from = page * size;
        if (from >= total) {
        	// 범위 밖 페이지 요청이면 빈 목록
        	var builder = PostPageResponse.<PostMetaDto>builder()
                    .page(page)
                    .size(size)
                    .totalElements(total)
                    .totalPages(totalPages)
                    .content(List.of());
            return applyBlockPagingMeta(builder, page, size, total, totalPages).build();
        }
        
        int to = Math.min(from + size, total);
        List<Long> pageIds = ids.subList(from, to);
        
        // ID로 메타만 조회 (본문 JSON x)
        List<PostMetaDto> metas = postMetaIndexRepository.findByIds(pageIds);

        var builder = PostPageResponse.<PostMetaDto>builder()
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .content(metas);
        
        return applyBlockPagingMeta(builder, page, size, total, totalPages).build();
    }

    
    
    // 6. 전체 목록 페이징
    // - 목록 인덱스에서 page, size 가져온 뒤 페이징 처리
    public PostPageResponse<PostMetaDto> getPostsPage(int page, int size) {
    	page = Math.max(page, 0);
    	size = (size <= 0) ? 10 : size;
    	// 인덱스에서 page 수 가져오기
    	List<PostMetaDto> metas = postMetaIndexRepository.findPage(page, size);
    	int total = postMetaIndexRepository.count();
        int totalPages = (total == 0) ? 0 : (int) Math.ceil((double) total / size);
        
        var builder = PostPageResponse.<PostMetaDto>builder()
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .content(metas);
        
        return applyBlockPagingMeta(builder, page, size, total, totalPages).build();
    }
    
    
    // 7. UI 에디터 이미지 업로드 
    public String uploadEditorImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }
        // 원본 파일명에서 확장자 추출
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
        
        // 업로드 디렉토리: application.properties의 filedb.upload-path 값 사용
        File dir = new File(uploadPath, "editor").getAbsoluteFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("에디터 이미지 저장 폴더를 생성할 수 없습니다.");
        }

        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.lastIndexOf(".") != -1) {
            extension = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        }
        if (extension.isBlank()) extension = ".dat";
        
        // 파일 저장
        String savedFilename = UUID.randomUUID() + extension;
        File dest = new File(dir, savedFilename);

        try {
            file.transferTo(dest);
        } catch (IOException e) {
            throw new RuntimeException("에디터 이미지 저장 중 오류 발생", e);
        }

        return ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/images/editor/")
                .path(savedFilename)
                .toUriString();
    }

}
