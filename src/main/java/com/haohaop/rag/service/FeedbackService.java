package com.haohaop.rag.service;

import com.haohaop.rag.entity.FeedbackEntity;
import com.haohaop.rag.repository.FeedbackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    public void save(String question, String answer, String rating) {
        FeedbackEntity entity = new FeedbackEntity(question, answer, rating);
        feedbackRepository.save(entity);
        log.info("Feedback saved: rating={}, question={}", rating,
                question.substring(0, Math.min(50, question.length())));
    }
}
