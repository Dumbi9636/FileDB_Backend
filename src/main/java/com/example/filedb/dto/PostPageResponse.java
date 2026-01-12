package com.example.filedb.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 게시글 페이징 응답 DTO
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostPageResponse<T> {
	private int page; // 현제 페이지 번호
	private int size;  // 페이지 크기
	private long totalElements; // 전체 데이터 수
	private int totalPages; // 전체 페이지 수 
	private List<T> content; // 현재 페이지에 포함된 게시글 목록
}
