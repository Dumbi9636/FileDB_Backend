package com.example.filedb.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import com.example.filedb.dto.PostDto;
import com.example.filedb.dto.PostMetaDto;
import com.example.filedb.dto.PostPageResponse;
import com.example.filedb.repository.FilePostRepository;
import com.example.filedb.service.PostService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 게시글 API 컨트롤러
 *
 * - 컨트롤러는 HTTP 요청/응답 처리(요청 파라미터 매핑, 검증, 상태코드)만 담당
 * - 실제 비즈니스 규칙/파일 저장 구조/인덱싱 처리 등은 Service/Repository 계층으로 위임
 *
 * - 목록/검색 응답은 PostDto(본문 포함)가 아닌 PostMetaDto(목록용 메타)로 반환
 *   파일 기반 저장소 특성상 "목록에서 본문을 매번 읽는 비용"이 크므로, 메타 인덱스를 별도로 운영하여 성능을 확보
 *   
 * - 에디터 이미지 업로드는 게시글 CRUD와 분리된 독립 엔드포인트(/posts/images)로 제공
 *   에디터는 글 저장 이전에도 이미지를 먼저 업로드할 수 있으므로, 게시글 ID에 종속시키지 않는다.
 */
@RestController
@RequestMapping("/posts") // 전체 URL prefix /post/... 
@RequiredArgsConstructor
public class PostController {
	
	private final PostService postService;
	
	/**
     * 1. 게시글 생성
     *
     * - 요청 본문(@RequestBody) 검증(@Valid) 후 서비스에 위임한다.
     * - 생성 성공 시 201(CREATED)을 반환한다.
     */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PostDto createPost(@Valid @RequestBody PostDto request) {
		return postService.createPost(request);
	}
	
	
	/**
     * 2. 게시글 수정
     *
     * - 리소스 식별자는 path variable(id)로 받는다.
     * - 수정 대상이 없거나 검증 실패 등의 예외 처리는 서비스/예외 핸들러에서 통일한다.
     */
	@PutMapping("/{id}")
	public PostDto updatePost(@PathVariable Long id, @Valid @RequestBody PostDto request) {
		return postService.updatePost(id, request);
	}
	
	
	 /**
     * 3. 게시글 단건 조회
     *
     * - 본문(content)을 포함한 전체 DTO를 반환한다.
     */
	@GetMapping("/{id}")
	public PostDto getPost(@PathVariable Long id) {
		return postService.getPost(id);
	}
	
	
	/**
     * 4. 게시글 삭제
     *
     * - 삭제 성공 시 별도 응답 바디 없이  204를 반환한다.
     * - 관련 인덱스/메타데이터 정합성 처리는 서비스에서 처리한다.
     */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePost(@PathVariable Long id) {
		postService.deletePost(id);
	}
	
	
	 /**
     * 5. 게시글 목록(페이지네이션)
     *
     * - 파일 DB 구조에서는 전체 본문을 매번 읽는 비용이 크므로,
     *   목록은 PostMetaDto 기반의 "메타 인덱스"를 사용해 빠르게 제공한다.
     *
     * Query
     * - page: 0부터 시작
     * - size: 페이지 크기
     */
	@GetMapping
	public PostPageResponse<PostMetaDto> getPosts(
			@RequestParam(defaultValue ="0") int page, 
			@RequestParam(defaultValue="10") int size) {
		return postService.getPostsPage(page, size);
	}
	
	
	/**
     * 6. 게시글 검색 + 페이지네이션
     *
     * - inverted index(역색인) 기반 검색 결과를 페이지로 반환한다.
     * - 검색 결과 또한 목록과 동일하게 PostMetaDto로 반환하여 I/O 비용을 최소화한다.
     */
	@GetMapping("/search")
	public PostPageResponse<PostMetaDto> searchPosts(
			@RequestParam String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue ="10") int size) {
		return postService.searchPostsPage(keyword, page, size);
	}
	
	
	/**
     * 7. 에디터(Toast UI) 이미지 업로드
     *
     * - 에디터는 "글 저장 이전"에 이미지 업로드가 선행될 수 있으므로,
     *   /posts/{id}/image 같은 형태로 게시글에 종속시키지 않고 독립 엔드포인트로 제공한다.
     *
     * 응답 형식
     * - Toast UI 에디터가 요구하는 형태로 {"url": "..."} 반환
     */
	@PostMapping(
	        value = "/images",
	        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> uploadEditorImage(@RequestPart("file") MultipartFile file) {
	    String url = postService.uploadEditorImage(file); // 저장 후 접근 가능한 URL 반환
	    return Map.of("url", url);
	}

	
}
