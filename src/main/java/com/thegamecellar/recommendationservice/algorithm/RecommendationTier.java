package com.thegamecellar.recommendationservice.algorithm;

public enum RecommendationTier {
    ONE,   // 5+ rated games — personalized content-based
    TWO,   // 1–4 rated games — filtered popular
    THREE  // 0 rated games — platform fallback
}
