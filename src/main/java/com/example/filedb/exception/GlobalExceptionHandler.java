package com.example.filedb.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.filedb.dto.ErrorResponse;

// 전역 예외 처리 핸들러
// controller 에서 발생하는 예외를 이곳에서 ErrorResponse 형태의 JSON 응답으로 변환
@RestControllerAdvice
public class GlobalExceptionHandler {
	
	// 1. 게시글을 찾지 못했을때 발생하는 예외처리 
	// 서비스/리포지토리에서 PostNotFoundExcpetion 를 던지면 HTTP 404 상태코드와 POST_NOT_FOUND 코드로 응답 
	@ExceptionHandler(PostNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handlePostNotFound(PostNotFoundException e){
		return ErrorResponse.builder()
				.timestamp(LocalDateTime.now())
				.status(HttpStatus.NOT_FOUND.value())
				.code("POST_NOT_FOUND")
				.message(e.getMessage())
				.build();
	}
	
	
	// 2. @Valid 검증 실패시 발생하는 예외 처리
	// 필드별 에러 메시지를 errors(Map) 에 담아 HTTP 400 상태코드와 VALIDATION_ERROR 코드로 응답
	@ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        // 어떤 필드에서 어떤 메세지가 발생했는지 확인
        e.getBindingResult().getFieldErrors().forEach(error ->
            fieldErrors.put(error.getField(), error.getDefaultMessage())
        );
        return ErrorResponse.builder()
        		.timestamp(LocalDateTime.now())
        		.status(HttpStatus.BAD_REQUEST.value())
        		.code("VALIDATION_ERROR")
        		.message("요청 값이 올바르지 않습니다.")
        		.errors(fieldErrors)
        		.build();
    }
}
