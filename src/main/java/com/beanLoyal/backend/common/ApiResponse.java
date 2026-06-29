package com.beanLoyal.backend.common;

public record ApiResponse<T>(boolean ok, T data) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data);
    }
}
