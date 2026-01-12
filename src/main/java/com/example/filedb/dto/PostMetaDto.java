package com.example.filedb.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostMetaDto {
    private Long id;
    private String title;
    private String writer;
    private String createdAt;
    private String updatedAt;
    private String imagePath; 
}
