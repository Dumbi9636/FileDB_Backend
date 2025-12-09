package com.example.filedb.controller;

import java.util.List;

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
import com.example.filedb.dto.PostPageResponse;
import com.example.filedb.service.PostService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/posts") // 전체 URL prefix /post/... 
@RequiredArgsConstructor
public class PostController {
	
	private final PostService postService;
	
	// 1. 새 게시글 등록
	// POST /posts
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PostDto createPost(@Valid @RequestBody PostDto request) {
		return postService.createPost(request);
	}
	
	
	// 2. 기존 게시글 수정
	// PUT /posts/{id}
	@PutMapping("/{id}")
	public PostDto updatePost(@PathVariable Long id, @Valid @RequestBody PostDto request) {
		return postService.updatePost(id, request);
	}
	
	
	// 3. 단일 게시글 조회
	// GET /posts/{id}
	@GetMapping("/{id}")
	public PostDto getPost(@PathVariable Long id) {
		return postService.getPost(id);
	}
	
	
	// 4. 게시글 삭제
	// DELETE /posts/{id}
	@DeleteMapping("/{id}")
	public void deletePost(@PathVariable Long id) {
		postService.deletePost(id);
	}
	
	// 5. 게시글 이미지 업로드
	// POST /posts/{id}image
	// form-data 로 file 필드에 파일 첨부 
	@PostMapping(
			value = "/{id}/image",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PostDto uploadPostImage(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
		return postService.uploadPostImage(id, file);
	}
	
	// 6. 전체 목록 페이징 조회
	// GET /posts?page=0&size=10
	@GetMapping
	public PostPageResponse getPosts(
			@RequestParam(defaultValue ="0") int page, 
			@RequestParam(defaultValue="10") int size) {
		return postService.getPostsPage(page, size);
	}
	
	// 7. 검색 + 페이징
	// GET /posts/search?keyword=aaa&page=0&size=10
	@GetMapping("/search")
	public PostPageResponse searchPosts(
			@RequestParam String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue ="10") int size) {
		return postService.searchPostsPage(keyword, page, size);
	}
	
}
