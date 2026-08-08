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

    // ── 工厂方法 ──

    /** 成功返回数据（默认消息） */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "成功", data);
    }

    /** 成功返回数据与自定义消息 */
    public static <T> ApiResponse<T> ok(T data, String msg) {
        return new ApiResponse<>(200, msg, data);
    }

    /** 成功但不返回数据（如删除、反馈） */
    public static <T> ApiResponse<T> ok(String msg) {
        return new ApiResponse<>(200, msg, null);
    }

    /** 失败 */
    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    // ── Getter / Setter ──
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
