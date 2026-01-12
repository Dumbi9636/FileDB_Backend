package com.example.filedb.seed;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.filedb.dto.PostDto;
import com.example.filedb.repository.FilePostRepository;

import lombok.RequiredArgsConstructor;

@Component
@Profile("seed")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final FilePostRepository repo;
    private static final DateTimeFormatter F = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public void run(String... args) {
    	// 인덱스 결과 테스트용 10,000 개의 게시글 생성 
        int count = 10_000;
        for (int i = 1; i <= count; i++) {
            PostDto p = new PostDto();
            p.setTitle("테스트 글 " + i + " spring swagger filedb");
            p.setWriter("user" + (i % 200));
            p.setContent("내용 " + i + " 키워드: spring swagger filedb " + (i % 50));

            // 시간값 세팅
            String now = LocalDateTime.now().format(F);
            p.setCreatedAt(now);
            p.setUpdatedAt(now);

            repo.create(p);

            if (i % 100 == 0) {
                System.out.println("Seed progress: " + i);
            }

        }

        System.out.println("Seed done: " + count);
        // seed 후 자동 종료
        System.exit(0);
    }
}
