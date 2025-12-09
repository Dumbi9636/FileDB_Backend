package com.example.filedb.dto;

import java.time.LocalDateTime;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;

// API 에러 응답 공통 포맷
@Builder
@Getter
public class ErrorResponse {
	private LocalDateTime timestamp; // 에러 발생 시간
	private int status; // HTTP 상태 코드( 400, 404, ...)
	private String code; // 에러 코드 문자열 (POST_NOT_FOUND...)
	private String message; // 에러 메세지
	private Map<String, String> errors; // 필드별 검증 에러
}
