package com.haohaop.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;

    public ApiResponse() {}

    public ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ── Factory methods ──

    /** Success with data (default message) */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /** Success with data and custom message */
    public static <T> ApiResponse<T> ok(T data, String msg) {
        return new ApiResponse<>(200, msg, data);
    }

    /** Success without data (e.g. delete, feedback) */
    public static <T> ApiResponse<T> ok(String msg) {
        return new ApiResponse<>(200, msg, null);
    }

    /** Failure */
    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    // ── Getters / Setters ──
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
