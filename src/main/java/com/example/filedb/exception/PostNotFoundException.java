package com.example.filedb.exception;

// 커스텀 예외 클래스
public class PostNotFoundException extends RuntimeException {
	public PostNotFoundException(Long id) {
		super("게시글을 찾을 수 없습니다. id="+ id);
	}
}
