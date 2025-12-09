package com.example.filedb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// /images/ 요청을 로컬 파일 시스템의 이미지 폴더와 매핑
@Configuration
public class WebConfig implements WebMvcConfigurer {
	/*
		브라우저에서 http://localhost:8080/images/1.jpg 요청하면
		실제로는 ./uploads/1.jpg 파일을 읽어서 보여줄 수 있도록 
	 */
	@Value("${filedb.upload-path}")
	private String uploadPath;
	
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// /images/1.jpg -> {uploadPath}/1.jpg
		registry.addResourceHandler("/images/**")
		.addResourceLocations("file:" + uploadPath + "/");
	}
}
