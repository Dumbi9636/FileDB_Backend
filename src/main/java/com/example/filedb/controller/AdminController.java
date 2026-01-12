package com.example.filedb.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.filedb.repository.FilePostRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final FilePostRepository filePostRepository;

    /**
     * 파일 DB 메타 인덱스를 재생성
     * - 기존 posts/*.json 을 순회하며 meta index 재구성
     * - 검색/목록 성능 최적화를 위한 관리용 API
     */
    @PostMapping("/rebuild-meta")
    public String rebuildMetaIndex() {
        filePostRepository.rebuildMetaIndex();
        return "OK";
    }
}

