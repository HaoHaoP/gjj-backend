package com.haohaop.rag.model;

public record FeedbackRequest(String question, String answer, String rating, long timestamp) {}
