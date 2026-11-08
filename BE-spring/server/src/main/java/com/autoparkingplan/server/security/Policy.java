package com.autoparkingplan.server.security;

/** 요청 제한 묶음 하나: 분당 허용량과 순간 허용량(버킷 크기). */
public record Policy(String name, int perMinute, int burst) {
}
