package com.example.filedb.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PostDto {
	private Long id; // PK 
	
	@NotBlank(message="제목은 필수입니다.")
	private String title;
	
	@NotBlank(message="내용은 필수입니다.")
	private String content;
	
	@NotBlank(message="작성자는 필수입니다.")
	private String writer;
	
	private String createdAt;
	private String updatedAt;
	private String imageFilename; // 이미지 업로드 파일명
	
	// 게시글에 첨부된 이미지 경로
	// /images/1.jpg
	private String imagePath;
}
