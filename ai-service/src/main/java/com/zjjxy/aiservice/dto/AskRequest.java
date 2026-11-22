package com.zjjxy.aiservice.dto;

/** 问答请求（契约，给全）：question 是用户的大白话，tenantId 决定检索范围 */
public record AskRequest(String question, Long tenantId) {
}