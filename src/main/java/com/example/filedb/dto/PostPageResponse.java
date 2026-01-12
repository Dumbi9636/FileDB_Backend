package com.example.filedb.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 게시글 페이징 응답 DTO
/*
 * - 일반 페이징(이전/다음) + 블록 페이징(1~10, <<, >>)을 지원하기 위한 메타데이터 포함
 * - 프론트에서 아래 메타 정보를 그대로 사용하여 페이지 버튼을 렌더링
 * @param <T> 페이징 대상 데이터 타입 (예: PostDto)
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostPageResponse<T> {
	/* =========================
     * 기본 페이징 정보
     * ========================= */
	private int page; // 현제 페이지 번호
	private int size;  // 페이지 크기
	private long totalElements; // 전체 데이터 수
	private int totalPages; // 전체 페이지 수 
	private List<T> content; // 현재 페이지에 포함된 게시글 목록
	
	/* =========================
     * 블록(10단위) 페이징 정보
     * ========================= */
    private int startPage; //페이지 블록에 표시할 시작 페이지 (예: 1, 11, 21...)    
    private int endPage; // 페이지 블록에 표시할 끝 페이지 (예: 10, 20, 30...) 
    private boolean hasPrevPage;  // 이전 페이지 존재 여부 (page > 1) 
    private boolean hasNextPage;  // 다음 페이지 존재 여부 (page < totalPages) 
    private boolean hasPrevBlock; // 이전 블록 존재 여부 (startPage > 1) 
    private boolean hasNextBlock; // 다음 블록 존재 여부 (endPage < totalPages) 
    private int prevPage; // 이전 페이지 번호 (page - 1) 
    private int nextPage; // 다음 페이지 번호 (page + 1) 

    /**
     * 이전 블록으로 이동할 페이지 번호
     * - 일반적으로 startPage - 1 (이전 블록의 마지막 페이지)
     * - 예: 11~20 블록에서 << 누르면 10으로 이동
     */
    private int prevBlockPage;

    /**
     * 다음 블록으로 이동할 페이지 번호
     * - 일반적으로 endPage + 1 (다음 블록의 첫 페이지)
     * - 예: 1~10 블록에서 >> 누르면 11로 이동
     */
    private int nextBlockPage;
	
	
	
	
	
	
	
	
}
