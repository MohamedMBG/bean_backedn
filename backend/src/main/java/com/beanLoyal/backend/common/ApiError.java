package com.beanLoyal.backend.common;

public record ApiError(boolean ok , String code , String message) {
    public static ApiError of(String code, String message) {
        return new ApiError(false , code , message);
    }
}
